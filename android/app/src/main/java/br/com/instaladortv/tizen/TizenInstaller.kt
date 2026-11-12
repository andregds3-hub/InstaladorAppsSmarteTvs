package br.com.instaladortv.tizen

import br.com.instaladortv.sdb.Sdb
import br.com.instaladortv.sdb.SdbClient
import br.com.instaladortv.sdb.SdbException
import br.com.instaladortv.sdb.SdbServiceRefusedException
import br.com.instaladortv.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

enum class Stage(val label: String) {
    HASH("Conferindo o arquivo"),
    CONNECT("Conectando à TV"),
    PUSH("Enviando o app"),
    INSTALL("Instalando"),
    LAUNCH("Abrindo o app"),
    DONE("Concluído"),
}

/** Qual ID vai para o `vd_appinstall`: a confirmar na Fase 0 (ver docs/sdb-protocolo.md). */
enum class InstallId { PACKAGE, APP }

data class InstallProgress(val stage: Stage, val percent: Int? = null, val log: String? = null)

data class InstallResult(
    val ok: Boolean,
    val stage: Stage,
    val message: String,
    val packageId: String = "",
    val appId: String = "",
    val platformVersion: String = "",
    val capability: Map<String, String> = emptyMap(),
    val installOutput: String = "",
    val launchOutput: String = "",
    val code: String? = null,
)

/** Envia, instala e abre um .wgt já assinado: o mesmo caminho que o Tizen Studio usa. */
class TizenInstaller(private val port: Int = Sdb.PORT) {

    suspend fun install(
        host: String,
        wgt: File,
        expectedSha256: String?,
        installId: InstallId = InstallId.PACKAGE,
        launch: Boolean = true,
        permitProfile: File? = null,
        onProgress: (InstallProgress) -> Unit = {},
    ): InstallResult = withContext(Dispatchers.IO) {
        val log = { line: String -> onProgress(InstallProgress(Stage.CONNECT, log = line)) }
        if (!expectedSha256.isNullOrBlank()) {
            onProgress(InstallProgress(Stage.HASH))
            val actual = wgt.sha256Hex()
            if (!actual.equals(expectedSha256.trim(), ignoreCase = true)) {
                return@withContext InstallResult(false, Stage.HASH, "O arquivo baixado está corrompido (SHA-256 não confere).")
            }
            log("SHA-256 conferido: $actual")
        }
        val info = try {
            WgtInfo.read(wgt)
        } catch (e: WgtException) {
            return@withContext InstallResult(false, Stage.HASH, e.message ?: "Pacote inválido.")
        }
        if (!info.isSigned) {
            return@withContext InstallResult(
                false, Stage.HASH, "O pacote não está assinado (faltam author-signature.xml ou signature1.xml).",
                info.packageId, info.appId,
            )
        }
        val target = if (installId == InstallId.PACKAGE) info.packageId else info.appId

        var stage = Stage.CONNECT
        var capability = emptyMap<String, String>()
        var installOutput = ""
        var launchOutput = ""
        try {
            onProgress(InstallProgress(stage))
            SdbClient(host, port).use { client ->
                val banner = client.connect()
                log("Conectado: ${banner.raw} (versão 0x${client.deviceVersion.toString(16)}, maxdata ${client.maxPayload})")
                capability = client.capability()

                if (permitProfile != null) {
                    // "Permit to install": o perfil de aparelho assinado pela Samsung libera a TV para o
                    // certificado de distribuidor. Sem ele a UN49KU6300 respondia install failed[118].
                    onProgress(InstallProgress(stage, log = "Permit to install: $PERMIT_DIR/device-profile.xml"))
                    client.push(permitProfile, "$PERMIT_DIR/device-profile.xml", mode = 420)
                }

                ensureActive()
                stage = Stage.PUSH
                var remote: String? = null
                val failures = mutableListOf<String>()
                for (directory in uploadDirs(capability)) {
                    val candidate = "$directory/${info.packageId}.wgt"
                    onProgress(InstallProgress(stage, 0, "Enviando para $candidate"))
                    try {
                        client.push(wgt, candidate) { sent, total ->
                            onProgress(InstallProgress(Stage.PUSH, if (total > 0) (sent * 100 / total).toInt() else 100))
                        }
                    } catch (e: SdbException) {
                        failures += "$directory: ${e.message}"
                        continue
                    }
                    remote = candidate
                    break
                }
                if (remote == null) throw SdbException("nenhuma pasta temporária aceitou o pacote (${failures.joinToString("; ")})")

                ensureActive()
                stage = Stage.INSTALL
                val command = "0 vd_appinstall $target $remote"
                onProgress(InstallProgress(stage, log = "$ $command"))
                installOutput = client.shell(command, timeoutMs = 300_000)
                log(installOutput.trim())
                val diagnosis = InstallOutput.diagnose(installOutput)
                if (diagnosis.outcome == Outcome.FAILURE) {
                    return@withContext InstallResult(
                        false, stage, diagnosis.message, info.packageId, info.appId,
                        capability["platform_version"].orEmpty(), capability, installOutput, code = diagnosis.code,
                    )
                }

                if (launch) {
                    stage = Stage.LAUNCH
                    for (template in LAUNCH_COMMANDS) {
                        val launchCommand = template + info.appId
                        onProgress(InstallProgress(stage, log = "$ $launchCommand"))
                        launchOutput = try {
                            client.shell(launchCommand, timeoutMs = 60_000)
                        } catch (e: SdbServiceRefusedException) {
                            // Tizen 2.4 (UN49KU6300) fecha o canal para "0 execute"; só aceita "0 was_execute".
                            log("comando recusado pela TV; tentando o próximo")
                            continue
                        }
                        log(launchOutput.trim())
                        if (InstallOutput.diagnoseLaunch(launchOutput) == Outcome.SUCCESS) break
                    }
                }
                onProgress(InstallProgress(Stage.DONE, 100))
                InstallResult(
                    true, Stage.DONE, diagnosis.message, info.packageId, info.appId,
                    capability["platform_version"].orEmpty(), capability, installOutput, launchOutput,
                )
            }
        } catch (e: SdbException) {
            InstallResult(
                false, stage, explain(stage, e), info.packageId, info.appId,
                capability["platform_version"].orEmpty(), capability, installOutput, launchOutput,
            )
        }
    }

    private fun explain(stage: Stage, e: SdbException): String = if (stage == Stage.CONNECT) {
        "Não foi possível conectar à TV (${e.message}). Confira se o Modo Desenvolvedor está ligado com o IP " +
            "deste celular, se a TV foi reiniciada depois disso e se o Tizen Studio no PC está fechado " +
            "(a TV aceita uma conexão por vez)."
    } else {
        "Falha na etapa \"${stage.label}\": ${e.message}"
    }

    companion object {
        /** Do mais novo para o mais antigo (`was_execute` em Tizen 2.4/3.0). */
        val LAUNCH_COMMANDS = listOf("0 execute ", "0 was_execute ")
        const val LEGACY_UPLOAD_DIR = "/opt/usr/apps/tmp"
        const val PERMIT_DIR = "/home/developer"

        /**
         * Pastas para o push, na ordem de tentativa. TVs novas informam `sdk_toolpath`; uma
         * UN49KU6300 (2016, Tizen 2.4) não informa, aceita `/opt/usr/apps/tmp` e recusa a pasta do sdk_tools.
         */
        fun uploadDirs(capability: Map<String, String>): List<String> = buildList {
            capability["sdk_toolpath"]?.takeIf { it.isNotBlank() }?.let { add("${it.trimEnd('/')}/tmp") }
            add(LEGACY_UPLOAD_DIR)
            add("${Sdb.DEFAULT_SDK_TOOLPATH}/tmp")
        }.distinct()
    }
}
