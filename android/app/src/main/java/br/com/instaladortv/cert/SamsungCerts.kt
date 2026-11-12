package br.com.instaladortv.cert

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.ExtensionsGenerator
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder
import org.bouncycastle.util.encoders.Base64
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

/** Pedidos de certificado (CSR) no formato que a Samsung aceita: RSA 2048, SHA256withRSA. */
object CsrFactory {
    private val DUID = Regex("^[A-Za-z0-9._:-]{4,64}$")

    fun newKeyPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    fun authorCsr(keys: KeyPair, name: String): String {
        val subject = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, name).build()
        return pem(JcaPKCS10CertificationRequestBuilder(subject, keys.public), keys)
    }

    /** CN=TizenSDK, e-mail da conta e um `URN:tizen:deviceid=<DUID>` no SAN por TV. */
    fun distributorCsr(keys: KeyPair, email: String, duids: List<String>): String {
        if (duids.isEmpty()) throw SamsungCertException("Nenhuma TV para o certificado.")
        duids.forEach { if (!DUID.matches(it)) throw SamsungCertException("DUID inválido: $it") }
        val subject = X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.CN, "TizenSDK").addRDN(BCStyle.EmailAddress, email).build()
        val names = listOf("URN:tizen:packageid=") + duids.map { "URN:tizen:deviceid=$it" }
        val extensions = ExtensionsGenerator().apply {
            addExtension(Extension.subjectAlternativeName, false, GeneralNames(names.map { GeneralName(GeneralName.uniformResourceIdentifier, it) }.toTypedArray()))
        }.generate()
        val builder = JcaPKCS10CertificationRequestBuilder(subject, keys.public)
            .addAttribute(PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, extensions)
        return pem(builder, keys)
    }

    private fun pem(builder: JcaPKCS10CertificationRequestBuilder, keys: KeyPair): String {
        val csr = builder.build(JcaContentSignerBuilder("SHA256withRSA").build(keys.private))
        val body = Base64.toBase64String(csr.encoded).chunked(64).joinToString("\n")
        return "-----BEGIN CERTIFICATE REQUEST-----\n$body\n-----END CERTIFICATE REQUEST-----\n"
    }
}

object CertParser {
    private val factory: CertificateFactory get() = CertificateFactory.getInstance("X.509")
    private val PEM_BLOCK = Regex("-----BEGIN CERTIFICATE-----.+?-----END CERTIFICATE-----", RegexOption.DOT_MATCHES_ALL)

    /** Aceita PEM (um ou vários), DER, DER em base64 ou JSON com um desses dentro. */
    fun parse(data: ByteArray): List<X509Certificate> {
        val text = String(data, Charsets.UTF_8).trim()
        if (text.startsWith("{")) {
            val json = runCatching { JSONObject(text) }.getOrNull()
            json?.keys()?.forEach { key ->
                val value = json.opt(key)
                if (value is String && value.length > 100) runCatching { return parse(value.toByteArray()) }
            }
        }
        val blocks = PEM_BLOCK.findAll(text).map { it.value }.toList()
        if (blocks.isNotEmpty()) {
            return factory.generateCertificates(ByteArrayInputStream(blocks.joinToString("\n").toByteArray())).map { it as X509Certificate }
        }
        runCatching { return listOf(factory.generateCertificate(ByteArrayInputStream(data)) as X509Certificate) }
        runCatching { return listOf(factory.generateCertificate(ByteArrayInputStream(Base64.decode(text))) as X509Certificate) }
        throw SamsungCertException("A Samsung respondeu sem um certificado reconhecível: ${text.take(200)}")
    }

    fun duids(cert: X509Certificate): List<String> = cert.subjectAlternativeNames.orEmpty()
        .filter { it.getOrNull(0) == 6 }
        .mapNotNull { it.getOrNull(1) as? String }
        .filter { it.startsWith("URN:tizen:deviceid=") }
        .map { it.removePrefix("URN:tizen:deviceid=") }
}

/**
 * CAs intermediárias públicas da Samsung (arquivos da Samsung Certificate Extension), embutidas em
 * `assets/samsung-ca`. A cadeia só usa uma CA que comprova ter assinado o certificado.
 */
class CaBundle(private val cas: List<X509Certificate>) {
    fun issuerOf(cert: X509Certificate): X509Certificate? = cas.firstOrNull { ca ->
        ca.subjectX500Principal == cert.issuerX500Principal && runCatching { cert.verify(ca.publicKey) }.isSuccess
    }

    companion object {
        fun fromAssets(context: Context): CaBundle {
            val files = context.assets.list("samsung-ca").orEmpty()
            return CaBundle(files.flatMap { name -> context.assets.open("samsung-ca/$name").use { CertParser.parse(it.readBytes()) } })
        }
    }
}

/** As chamadas à svdca.samsungqbe.com (interface para os testes simularem a Samsung). */
interface SamsungCertApi {
    fun requestAuthor(auth: SamsungAuth, csrPem: String): ByteArray

    /** Devolve (device-profile.xml, certificado). */
    fun requestDistributor(auth: SamsungAuth, csrPem: String, privilege: String = "public"): Pair<ByteArray, ByteArray>
}

class SamsungCertClient(private val http: OkHttpClient) : SamsungCertApi {
    override fun requestAuthor(auth: SamsungAuth, csrPem: String): ByteArray =
        post(SamsungEndpoints.AUTHOR_URL, mapOf("access_token" to auth.accessToken, "user_id" to auth.userId, "platform" to SamsungEndpoints.PLATFORM), "author.csr", csrPem)

    override fun requestDistributor(auth: SamsungAuth, csrPem: String, privilege: String): Pair<ByteArray, ByteArray> {
        // A svdca só entende o nível em minúsculas; "Partner" gera um certificado public sem avisar.
        val fields = mapOf(
            "access_token" to auth.accessToken,
            "user_id" to auth.userId,
            "privilege_level" to privilege.lowercase(),
            "developer_type" to "Individual",
            "platform" to SamsungEndpoints.PLATFORM,
        )
        val profile = post(SamsungEndpoints.DISTRIBUTOR_V1_URL, fields, "distributor.csr", csrPem)
        val cert = post(SamsungEndpoints.DISTRIBUTOR_V3_URL, fields, "distributor.csr", csrPem)
        return profile to cert
    }

    private fun post(url: String, fields: Map<String, String>, filename: String, csrPem: String): ByteArray {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM).apply {
            fields.forEach { (k, v) -> addFormDataPart(k, v) }
            addFormDataPart("csr", filename, csrPem.toByteArray().toRequestBody("application/octet-stream".toMediaType()))
        }.build()
        val response = try {
            http.newCall(Request.Builder().url(url).post(body).build()).execute()
        } catch (e: IOException) {
            throw SamsungCertException("Sem conexão com o servidor de certificados da Samsung: ${e.message}", e)
        }
        response.use {
            val bytes = it.body?.bytes() ?: ByteArray(0)
            if (!it.isSuccessful) {
                throw SamsungCertException("O servidor de certificados da Samsung recusou o pedido (${it.code}): ${String(bytes).take(300)}")
            }
            return bytes
        }
    }
}
