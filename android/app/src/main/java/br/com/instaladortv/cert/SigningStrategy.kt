package br.com.instaladortv.cert

import br.com.instaladortv.tizen.WgtSigner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** TV de destino da assinatura: o certificado de distribuidor precisa conter este DUID (do `0 getduid`). */
data class SigningTarget(val duid: String, val modelName: String)

/** Pacote pronto para instalar e, quando a TV exige, o device-profile.xml do "Permit to install". */
data class PreparedPackage(val file: File, val permitProfile: File?)

/**
 * Como o .wgt fica assinado para uma TV. Isolado para poder trocar a estratégia:
 * - [SamsungAccountStrategy] (A): conta Samsung do cliente, certificados e assinatura no celular.
 * - [PreSignedPackage]: o pacote já vem assinado (diagnóstico).
 * - Estratégia B (VPS assina com a conta do admin): prevista, ainda não implementada.
 */
interface SigningStrategy {
    val name: String
    suspend fun prepare(packageFile: File, target: SigningTarget): PreparedPackage
}

object PreSignedPackage : SigningStrategy {
    override val name = "Pacote já assinado"
    override suspend fun prepare(packageFile: File, target: SigningTarget) = PreparedPackage(packageFile, null)
}

class SamsungAccountStrategy(
    private val profiles: ProfileManager,
    private val login: suspend () -> SamsungAuth,
    private val workDir: File,
    private val progress: (String) -> Unit = {},
) : SigningStrategy {
    override val name = "Conta Samsung do cliente"

    override suspend fun prepare(packageFile: File, target: SigningTarget): PreparedPackage {
        if (target.duid.isBlank()) throw SamsungCertException("Não foi possível ler o DUID da TV. Toque em Conectar de novo.")
        val profile = profiles.ensure(target.duid, login, progress)
        return withContext(Dispatchers.IO) {
            workDir.mkdirs()
            progress("Assinando o app para esta TV…")
            val signed = File(workDir, "assinado-${packageFile.name}")
            WgtSigner.sign(packageFile, signed, profile.author, profile.distributor)
            val permit = File(workDir, "device-profile.xml").apply { writeBytes(profile.deviceProfileXml) }
            PreparedPackage(signed, permit)
        }
    }
}
