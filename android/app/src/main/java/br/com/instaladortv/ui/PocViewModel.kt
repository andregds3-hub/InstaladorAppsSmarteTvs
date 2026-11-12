package br.com.instaladortv.ui

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.instaladortv.discovery.LocalNetwork
import br.com.instaladortv.discovery.Platform
import br.com.instaladortv.discovery.TvInfo
import br.com.instaladortv.discovery.TvScanner
import br.com.instaladortv.sdb.SdbClient
import br.com.instaladortv.sdb.SdbException
import br.com.instaladortv.tizen.InstallId
import br.com.instaladortv.tizen.InstallResult
import br.com.instaladortv.tizen.Stage
import br.com.instaladortv.tizen.TizenInstaller
import br.com.instaladortv.tizen.WgtException
import br.com.instaladortv.tizen.WgtInfo
import br.com.instaladortv.util.sha256Hex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class PocState(
    val phoneIp: String? = null,
    val scanning: Boolean = false,
    val scanProgress: Float = 0f,
    val tvs: List<TvInfo> = emptyList(),
    val selected: TvInfo? = null,
    val manualIp: String = "",
    val sdbReport: String? = null,
    val packageFile: File? = null,
    val packageInfo: WgtInfo? = null,
    val packageSha256: String = "",
    val packageError: String? = null,
    val expectedSha256: String = "",
    val permitFile: File? = null,
    val permitDevices: List<String> = emptyList(),
    val permitError: String? = null,
    val installId: InstallId = InstallId.PACKAGE,
    val busy: Boolean = false,
    val stage: Stage? = null,
    val percent: Int? = null,
    val result: InstallResult? = null,
    val log: List<String> = emptyList(),
)

class PocViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(PocState())
    val state: StateFlow<PocState> = _state.asStateFlow()

    private val scanner = TvScanner()
    private val installer = TizenInstaller()

    init {
        refreshPhoneIp()
    }

    fun refreshPhoneIp() = _state.update { it.copy(phoneIp = LocalNetwork.phoneIpv4(getApplication())) }

    fun scan() {
        val ip = LocalNetwork.phoneIpv4(getApplication())
        if (ip == null) {
            log("Conecte o celular ao Wi-Fi da TV para procurar.")
            return
        }
        _state.update { it.copy(phoneIp = ip, scanning = true, scanProgress = 0f, tvs = emptyList()) }
        viewModelScope.launch {
            val found = scanner.scan(
                ip,
                onProgress = { done, total -> _state.update { it.copy(scanProgress = done / total.toFloat()) } },
                onFound = { tv -> _state.update { it.copy(tvs = (it.tvs + tv).sortedBy { t -> t.ip.substringAfterLast('.').toInt() }) } },
            )
            _state.update { it.copy(scanning = false) }
            log("Busca concluída: ${found.size} TV(s).")
        }
    }

    fun select(tv: TvInfo) = _state.update { it.copy(selected = tv, sdbReport = null, result = null) }

    fun setManualIp(value: String) = _state.update { it.copy(manualIp = value.trim()) }

    fun useManualIp() {
        val ip = _state.value.manualIp
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ip)) {
            log("IP inválido: $ip")
            return
        }
        viewModelScope.launch {
            val tv = scanner.probe(ip) ?: TvInfo(ip, Platform.TIZEN)
            select(tv)
            log("TV em $ip: SDB ${if (tv.sdbOpen) "aberta" else "fechada"}.")
        }
    }

    /** Banner, capability e DUID pelo SDB: os dados que o relatório da Fase 0 pede. */
    fun testSdb() {
        val tv = _state.value.selected ?: return
        _state.update { it.copy(busy = true, sdbReport = null) }
        viewModelScope.launch {
            val report = withContext(Dispatchers.IO) {
                try {
                    SdbClient(tv.ip).use { client ->
                        val banner = client.connect()
                        buildString {
                            appendLine("banner: ${banner.raw}")
                            appendLine("versão: 0x${client.deviceVersion.toString(16)}  maxdata: ${client.maxPayload}")
                            client.capability().forEach { (k, v) -> appendLine("$k: $v") }
                            appendLine("0 getduid: " + runCatching { client.shell("0 getduid", 20_000).trim() }.getOrElse { "falhou (${it.message})" })
                        }
                    }
                } catch (e: SdbException) {
                    "Falha: ${e.message}\nConfira o Modo Desenvolvedor (IP ${_state.value.phoneIp}) e reinicie a TV."
                }
            }
            _state.update { it.copy(busy = false, sdbReport = report) }
            log(report)
        }
    }

    fun pickPackage(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val target = File(app.cacheDir, "poc.wgt")
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    app.contentResolver.openInputStream(uri)!!.use { input -> target.outputStream().use { input.copyTo(it) } }
                    WgtInfo.read(target) to target.sha256Hex()
                }
            }
            outcome.onSuccess { (info, sha) ->
                _state.update { it.copy(packageFile = target, packageInfo = info, packageSha256 = sha, packageError = null, result = null) }
            }.onFailure { e ->
                val message = if (e is WgtException) e.message else "Não foi possível ler o arquivo: ${e.message}"
                _state.update { it.copy(packageFile = null, packageInfo = null, packageSha256 = "", packageError = message) }
            }
        }
    }

    /** device-profile.xml do perfil (resposta da Samsung): enviado antes de instalar ("Permit to install"). */
    fun pickPermit(uri: Uri?) {
        uri ?: return
        viewModelScope.launch {
            val app = getApplication<Application>()
            val target = File(app.cacheDir, "device-profile.xml")
            val devices = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = app.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    require(bytes.size < 64 * 1024) { "arquivo grande demais" }
                    val text = String(bytes, Charsets.UTF_8)
                    require("<Profile>" in text) { "não é um device-profile.xml da Samsung" }
                    target.writeBytes(bytes)
                    Regex("<TestDevice>([^<]+)</TestDevice>").findAll(text).map { it.groupValues[1] }.toList()
                }
            }
            devices.onSuccess { list ->
                _state.update { it.copy(permitFile = target, permitDevices = list, permitError = null) }
            }.onFailure { e ->
                _state.update { it.copy(permitFile = null, permitDevices = emptyList(), permitError = e.message) }
            }
        }
    }

    fun setExpectedSha256(value: String) = _state.update { it.copy(expectedSha256 = value.trim()) }

    fun setInstallId(value: InstallId) = _state.update { it.copy(installId = value) }

    fun install() {
        val s = _state.value
        val tv = s.selected ?: return
        val file = s.packageFile ?: return
        _state.update { it.copy(busy = true, result = null, stage = null, percent = null) }
        viewModelScope.launch {
            val result = installer.install(
                tv.ip, file, s.expectedSha256.ifBlank { null }, s.installId, permitProfile = s.permitFile,
            ) { p ->
                _state.update { it.copy(stage = p.stage, percent = p.percent ?: it.percent) }
                p.log?.takeIf { it.isNotBlank() }?.let { line -> log(line) }
            }
            _state.update { it.copy(busy = false, result = result) }
            log((if (result.ok) "OK: " else "FALHA: ") + result.message)
        }
    }

    /** Texto para colar no relatório da Fase 0 (docs/fase0-relatorio.md). */
    fun report(): String {
        val s = _state.value
        val tv = s.selected
        return buildString {
            appendLine("## Teste Fase 0 — ${tv?.modelName ?: "?"} (${tv?.ip ?: "?"})")
            appendLine("- Celular: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, IP ${s.phoneIp}")
            if (tv != null) {
                appendLine("- TV: ${tv.name} | model=${tv.model} | modelName=${tv.modelName} | ano=${tv.year} | Tizen de fábrica=${tv.factoryTizen}")
                appendLine("- DUID (api/v2): ${tv.duid.ifEmpty { "(ausente)" }} | developerMode=${tv.developerMode} | developerIP=${tv.developerIp}")
            }
            s.sdbReport?.let { appendLine("- SDB:\n```\n${it.trim()}\n```") }
            s.packageInfo?.let { appendLine("- Pacote: ${it.packageId} / ${it.appId} v${it.version} (required ${it.requiredVersion}) SHA-256 ${s.packageSha256}") }
            appendLine("- vd_appinstall com: ${s.installId}")
            appendLine("- Permit (device-profile.xml): ${if (s.permitFile != null) s.permitDevices.joinToString() else "não enviado"}")
            s.result?.let {
                appendLine("- Resultado: ${if (it.ok) "OK" else "FALHA"} na etapa ${it.stage} — ${it.message} ${it.code?.let { c -> "(código $c)" } ?: ""}")
                appendLine("- Saída vd_appinstall:\n```\n${it.installOutput.trim()}\n```")
                appendLine("- Saída abertura:\n```\n${it.launchOutput.trim()}\n```")
            }
        }
    }

    private fun log(line: String) = _state.update { it.copy(log = (it.log + line).takeLast(500)) }
}
