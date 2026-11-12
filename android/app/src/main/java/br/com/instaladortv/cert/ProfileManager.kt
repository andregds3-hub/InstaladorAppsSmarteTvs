package br.com.instaladortv.cert

import br.com.instaladortv.data.BlobStore
import br.com.instaladortv.tizen.SigningIdentity
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/** Tudo o que a assinatura e a instalação numa TV precisam. */
class SigningProfile(
    val author: SigningIdentity,
    val distributor: SigningIdentity,
    val deviceProfileXml: ByteArray,
    val duids: List<String>,
    val email: String,
)

/**
 * Estratégia A: certificados da conta Samsung do próprio cliente, guardados cifrados no celular.
 *
 * - O certificado de autor é único e reaproveitado: a TV só aceita atualizar um app assinado pelo
 *   mesmo autor. Só é refeito quando expira (1 ano).
 * - O de distribuidor lista os DUIDs de todas as TVs já usadas; uma TV nova gera um novo, com a lista
 *   inteira, junto com o device-profile.xml do "Permit to install".
 */
class ProfileManager(
    private val store: BlobStore,
    private val api: SamsungCertApi,
    private val cas: CaBundle,
    private val now: () -> Date = { Date() },
) {
    fun email(): String? = account()?.optString("email")?.takeIf { it.isNotBlank() }

    fun duids(): List<String> = account()?.optJSONArray("duids")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()

    /** Perfil pronto para esta TV, sem falar com a Samsung; null se for preciso gerar certificados. */
    fun readyFor(duid: String): SigningProfile? {
        val profile = load() ?: return null
        val soon = Date(now().time + RENEW_MARGIN_MS)
        val valid = profile.author.chain[0].notAfter.after(soon) && profile.distributor.chain[0].notAfter.after(soon)
        return profile.takeIf { valid && duid in CertParser.duids(profile.distributor.chain[0]) }
    }

    /** Garante certificados para a TV: só chama [login] (e a Samsung) quando necessário. */
    suspend fun ensure(duid: String, login: suspend () -> SamsungAuth, progress: (String) -> Unit = {}): SigningProfile {
        readyFor(duid)?.let { return it }
        val auth = login()
        val previous = account()
        if (previous != null && previous.optString("userId") != auth.userId) {
            throw SamsungCertException(
                "Esta é outra conta Samsung (${auth.email}). Apps instalados com a conta anterior só atualizam com ela. " +
                    "Para trocar de conta, use \"Sair da conta Samsung\" em Configurações.",
            )
        }
        val soon = Date(now().time + RENEW_MARGIN_MS)
        val author = loadIdentity("author")?.takeIf { it.chain[0].notAfter.after(soon) } ?: run {
            progress("Gerando o certificado de autor…")
            val keys = CsrFactory.newKeyPair()
            val response = api.requestAuthor(auth, CsrFactory.authorCsr(keys, AUTHOR_NAME))
            SigningIdentity(keys.private, chain(response, "autor")).also { saveIdentity("author", it) }
        }
        progress("Gerando o certificado desta TV…")
        val duids = (duids() + duid).distinct()
        val keys = CsrFactory.newKeyPair()
        val (deviceProfile, response) = api.requestDistributor(auth, CsrFactory.distributorCsr(keys, auth.email, duids))
        val distributor = SigningIdentity(keys.private, chain(response, "distribuidor"))
        val granted = CertParser.duids(distributor.chain[0])
        if (duid !in granted) throw SamsungCertException("A Samsung emitiu o certificado sem esta TV ($duid). Tente de novo mais tarde.")
        if (!String(deviceProfile, Charsets.UTF_8).contains("<Profile")) {
            throw SamsungCertException("A Samsung não devolveu o perfil de aparelho (device-profile.xml).")
        }
        saveIdentity("distributor", distributor)
        store.write("device-profile.xml", deviceProfile)
        store.write(ACCOUNT, JSONObject().put("userId", auth.userId).put("email", auth.email).put("duids", JSONArray(granted)).toString().toByteArray())
        return load() ?: throw SamsungCertException("Falha ao guardar os certificados.")
    }

    /** "Sair da conta Samsung": apaga chaves e certificados deste celular. */
    fun forget() {
        listOf("author.key", "author.crt", "distributor.key", "distributor.crt", "device-profile.xml", ACCOUNT).forEach(store::delete)
    }

    private fun chain(response: ByteArray, label: String): List<X509Certificate> {
        val certs = CertParser.parse(response)
        if (certs.size > 1) return certs
        val ca = cas.issuerOf(certs[0])
            ?: throw SamsungCertException("O app não tem a CA da Samsung para o certificado de $label (${certs[0].issuerX500Principal.name}). Atualize o app.")
        return listOf(certs[0], ca)
    }

    private fun account(): JSONObject? = store.read(ACCOUNT)?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }

    private fun load(): SigningProfile? {
        val author = loadIdentity("author") ?: return null
        val distributor = loadIdentity("distributor") ?: return null
        val deviceProfile = store.read("device-profile.xml") ?: return null
        return SigningProfile(author, distributor, deviceProfile, duids(), email().orEmpty())
    }

    private fun loadIdentity(kind: String): SigningIdentity? {
        val key = store.read("$kind.key") ?: return null
        val chain = store.read("$kind.crt") ?: return null
        val privateKey: PrivateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(key))
        return SigningIdentity(privateKey, CertParser.parse(chain))
    }

    private fun saveIdentity(kind: String, identity: SigningIdentity) {
        store.write("$kind.key", identity.key.encoded)
        val pem = identity.chain.joinToString("") {
            "-----BEGIN CERTIFICATE-----\n" + org.bouncycastle.util.encoders.Base64.toBase64String(it.encoded).chunked(64).joinToString("\n") +
                "\n-----END CERTIFICATE-----\n"
        }
        store.write("$kind.crt", pem.toByteArray())
    }

    companion object {
        private const val ACCOUNT = "samsung-account.json"
        private const val AUTHOR_NAME = "InstaladorTV"
        /** Renova com 7 dias de folga para não instalar algo que expira logo em seguida. */
        private const val RENEW_MARGIN_MS = 7L * 24 * 3600 * 1000
    }
}
