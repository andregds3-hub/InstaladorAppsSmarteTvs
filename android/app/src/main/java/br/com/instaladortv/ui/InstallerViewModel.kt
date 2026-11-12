package br.com.instaladortv.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import br.com.instaladortv.InstaladorApp
import br.com.instaladortv.api.DeviceReport
import br.com.instaladortv.api.PanelApp
import br.com.instaladortv.api.PanelException
import br.com.instaladortv.cert.LoginCallbackServer
import br.com.instaladortv.cert.SamsungAccountStrategy
import br.com.instaladortv.cert.SamsungAuth
import br.com.instaladortv.cert.SamsungCertException
import br.com.instaladortv.cert.SamsungEndpoints
import br.com.instaladortv.cert.SigningTarget
import br.com.instaladortv.discovery.DevModeStatus
import br.com.instaladortv.discovery.LocalNetwork
import br.com.instaladortv.discovery.Platform
import br.com.instaladortv.discovery.TvInfo
import br.com.instaladortv.sdb.SdbClient
import br.com.instaladortv.sdb.SdbException
import br.com.instaladortv.tizen.InstallId
import br.com.instaladortv.tizen.Stage
import br.com.instaladortv.tizen.WgtException
import br.com.instaladortv.tizen.SigningException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** TV escolhida, com o que o SDB revelou (DUID real e versão do Tizen). */
data class SelectedTv(
    val tv: TvInfo,
    val platform: Platform,
    val year: Int?,
    val duid: String = "",
    val osVersion: String = "",
    val deviceId: Long? = null,
) {
    val connected: Boolean get() = duid.isNotBlank()
    val title: String get() = tv.modelName.ifBlank { tv.name.ifBlank { tv.ip } }
}

enum class InstallStep(val label: String) {
    DOWNLOAD("Baixando o app"),
    CERTIFICATE("Preparando o certificado"),
    LOGIN("Entre na sua conta Samsung"),
    SIGN("Assinando para esta TV"),
    CONNECT("Conectando à TV"),
    PUSH("Enviando para a TV"),
    INSTALL("Instalando"),
    LAUNCH("Abrindo o app"),
    DONE("Concluído"),
}

data class InstallUi(
    val app: PanelApp,
    val step: InstallStep = InstallStep.DOWNLOAD,
    val percent: Int? = null,
    val detail: String = "",
    val running: Boolean = true,
    val ok: Boolean = false,
    val message: String = "",
    val log: List<String> = emptyList(),
)

data class InstallerState(
    val phoneIp: String? = null,
    val scanning: Boolean = false,
    val scanProgress: Float = 0f,
    val tvs: List<TvInfo> = emptyList(),
    val selected: SelectedTv? = null,
    val devStatus: DevModeStatus = DevModeStatus.UNKNOWN,
    val connecting: Boolean = false,
    val connectError: String? = null,
    val apps: List<PanelApp> = emptyList(),
    val appsLoading: Boolean = false,
    val appsError: String? = null,
    val needsAccessCode: Boolean = false,
    val install: InstallUi? = null,
    val samsungEmail: String? = null,
    val samsungDuids: List<String> = emptyList(),
    val panelConfigured: Boolean = false,
    val panelUrl: String = "",
    val panelBuiltIn: Boolean = false,
    val message: String? = null,
)

class InstallerViewModel(app: Application) : AndroidViewModel(app) {
    private val c = (app as InstaladorApp).container
    private val _state = MutableStateFlow(InstallerState())
    val state: StateFlow<InstallerState> = _state.asStateFlow()

    /** URLs para a tela abrir no navegador (login da Samsung). */
    private val _openUrl = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val openUrl: SharedFlow<String> = _openUrl.asSharedFlow()

    private var pollJob: Job? = null
    private var installJob: Job? = null

    init {
        refreshPhoneIp()
        refreshAccount()
        refreshPanel()
    }

    // --- rede e busca ------------------------------------------------------------------------

    fun refreshPhoneIp() = _state.update { s ->
        val ip = LocalNetwork.phoneIpv4(getApplication())
        s.copy(phoneIp = ip, devStatus = s.selected?.let { DevModeStatus.of(it.tv, ip) } ?: s.devStatus)
    }

    fun scan() {
        val ip = LocalNetwork.phoneIpv4(getApplication())
        if (ip == null) {
            _state.update { it.copy(phoneIp = null, message = "Conecte o celular ao Wi-Fi da TV.") }
            return
        }
        _state.update { it.copy(phoneIp = ip, scanning = true, scanProgress = 0f, tvs = emptyList(), message = null) }
        viewModelScope.launch {
            c.scanner.scan(
                ip,
                onProgress = { done, total -> _state.update { it.copy(scanProgress = done / total.toFloat()) } },
                onFound = { tv -> _state.update { s -> s.copy(tvs = (s.tvs + tv).sortedBy { it.ip.substringAfterLast('.').toIntOrNull() ?: 0 }) } },
            )
            _state.update { it.copy(scanning = false, message = if (it.tvs.isEmpty()) "Nenhuma TV encontrada. Confira se a TV está ligada e no mesmo Wi-Fi." else null) }
        }
    }

    fun addManualIp(ip: String) {
        if (!Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(ip.trim())) {
            _state.update { it.copy(message = "IP inválido.") }
            return
        }
        viewModelScope.launch {
            val tv = c.scanner.probe(ip.trim())
            if (tv == null) {
                _state.update { it.copy(message = "Nenhuma TV respondeu em ${ip.trim()}.") }
            } else {
                _state.update { s -> s.copy(tvs = (s.tvs.filter { it.ip != tv.ip } + tv), message = null) }
                select(tv)
            }
        }
    }

    fun select(tv: TvInfo) = _state.update {
        it.copy(
            selected = SelectedTv(tv, tv.platform, tv.year),
            devStatus = DevModeStatus.of(tv, it.phoneIp),
            connectError = null,
            apps = emptyList(),
            appsError = null,
            install = null,
        )
    }

    fun correctModel(platform: Platform, year: Int?) = _state.update { s ->
        s.copy(selected = s.selected?.copy(platform = platform, year = year), apps = emptyList())
    }

    /** Relê a TV (porta 8001 e 26101) e recalcula a situação do Modo Desenvolvedor. */
    suspend fun refreshDevStatus(): DevModeStatus {
        val selected = _state.value.selected ?: return DevModeStatus.UNKNOWN
        val tv = c.scanner.probe(selected.tv.ip) ?: selected.tv.copy(sdbOpen = false)
        val phoneIp = LocalNetwork.phoneIpv4(getApplication())
        val status = DevModeStatus.of(tv, phoneIp)
        _state.update { s -> s.copy(phoneIp = phoneIp, selected = s.selected?.copy(tv = tv), devStatus = status) }
        return status
    }

    /** Assistente: verifica a cada 3 s até a TV ficar pronta para este celular. */
    fun startDevPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                if (refreshDevStatus() == DevModeStatus.READY) break
                delay(3_000)
            }
        }
    }

    fun stopDevPolling() {
        pollJob?.cancel()
    }

    /** Conecta pelo SDB, lê o DUID real e a versão do Tizen, e registra a TV no painel. */
    fun connect(onConnected: () -> Unit = {}) {
        val selected = _state.value.selected ?: return
        _state.update { it.copy(connecting = true, connectError = null) }
        viewModelScope.launch {
            val status = refreshDevStatus()
            if (status == DevModeStatus.OTHER_DEVICE || status == DevModeStatus.OFF) {
                _state.update { it.copy(connecting = false, connectError = devModeMessage(status)) }
                return@launch
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    SdbClient(selected.tv.ip).use { client ->
                        client.connect()
                        val caps = client.capability()
                        val duid = client.shell("0 getduid", 20_000).lines().map { it.trim() }
                            .firstOrNull { it.matches(Regex("[A-Za-z0-9]{6,64}")) }.orEmpty()
                        duid to caps["platform_version"].orEmpty()
                    }
                }
            }
            result.onSuccess { (duid, osVersion) ->
                _state.update { s -> s.copy(connecting = false, selected = s.selected?.copy(duid = duid, osVersion = osVersion)) }
                registerDevice()
                onConnected()
            }.onFailure { e ->
                val msg = if (e is SdbException) "Não foi possível conectar: ${e.message}. ${devModeMessage(DevModeStatus.UNKNOWN)}" else "Falha: ${e.message}"
                _state.update { it.copy(connecting = false, connectError = msg) }
            }
        }
    }

    fun devModeMessage(status: DevModeStatus): String {
        val s = _state.value
        val phone = s.phoneIp ?: "?"
        return when (status) {
            DevModeStatus.OTHER_DEVICE ->
                "Esta TV está liberada para ${s.selected?.tv?.developerIp}. No Modo Desenvolvedor da TV, troque o IP para $phone e reinicie a TV."
            DevModeStatus.OFF -> "O Modo Desenvolvedor está desligado nesta TV. Use o assistente para ligar."
            DevModeStatus.NEEDS_RESTART -> "A TV já está liberada para $phone. Reinicie a TV (segure o botão de ligar) para ativar."
            else -> "Confira se o Modo Desenvolvedor está ligado com o IP $phone e se a TV foi reiniciada."
        }
    }

    private fun registerDevice() {
        val sel = _state.value.selected ?: return
        if (!c.settings.panel().isConfigured) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                c.panel.registerDevice(
                    DeviceReport(
                        platform = sel.platform.apiName, duid = sel.duid.ifBlank { null }, name = sel.tv.name, model = sel.tv.model,
                        modelName = sel.tv.modelName, year = sel.year, firmware = sel.tv.firmware, osVersion = sel.osVersion, localIp = sel.tv.ip,
                    ),
                )
            }.onSuccess { id -> _state.update { s -> s.copy(selected = s.selected?.copy(deviceId = id)) } }
        }
    }

    // --- apps do painel ----------------------------------------------------------------------

    fun loadApps() {
        val sel = _state.value.selected ?: return
        _state.update { it.copy(appsLoading = true, appsError = null, needsAccessCode = false) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { c.panel.apps(sel.platform.apiName, sel.tv.model, sel.year, sel.osVersion) }
            }
            result.onSuccess { apps -> _state.update { it.copy(appsLoading = false, apps = apps) } }
                .onFailure { e ->
                    val needsCode = e is PanelException && e.needsAccessCode
                    _state.update { it.copy(appsLoading = false, appsError = e.message, needsAccessCode = needsCode) }
                }
        }
    }

    fun submitAccessCode(code: String) {
        c.settings.saveAccessCode(code)
        c.panel.clearToken()
        loadApps()
    }

    // --- instalação --------------------------------------------------------------------------

    fun install(app: PanelApp) {
        val sel = _state.value.selected ?: return
        if (installJob?.isActive == true) return
        _state.update { it.copy(install = InstallUi(app)) }
        installJob = viewModelScope.launch {
            val work = File(getApplication<Application>().cacheDir, "install").apply { deleteRecursively(); mkdirs() }
            var stage = InstallStep.DOWNLOAD
            var code = ""
            var tvOutput = ""
            try {
                if (sel.platform != Platform.TIZEN) throw IllegalStateException("A instalação em ${sel.platform.label} chega na próxima versão do app.")
                val download = File(work, "${app.packageId ?: app.id}.wgt")
                step(InstallStep.DOWNLOAD, 0)
                withContext(Dispatchers.IO) {
                    c.panel.download(app, download) { done, total -> step(InstallStep.DOWNLOAD, if (total > 0) (done * 100 / total).toInt() else null) }
                }
                stage = InstallStep.CERTIFICATE
                step(stage, null)
                val strategy = SamsungAccountStrategy(c.profiles, { samsungLogin() }, work) { detail(it) }
                val prepared = strategy.prepare(download, SigningTarget(sel.duid, sel.title))
                refreshAccount()
                stage = InstallStep.CONNECT
                step(stage, null)
                val result = c.installer.install(sel.tv.ip, prepared.file, null, InstallId.PACKAGE, permitProfile = prepared.permitProfile) { p ->
                    val mapped = when (p.stage) {
                        Stage.HASH, Stage.CONNECT -> InstallStep.CONNECT
                        Stage.PUSH -> InstallStep.PUSH
                        Stage.INSTALL -> InstallStep.INSTALL
                        Stage.LAUNCH -> InstallStep.LAUNCH
                        Stage.DONE -> InstallStep.DONE
                    }
                    stage = mapped
                    step(mapped, p.percent)
                    p.log?.takeIf { it.isNotBlank() }?.let { line -> log(line) }
                }
                code = result.code.orEmpty()
                tvOutput = result.installOutput
                finish(result.ok, result.message.ifBlank { if (result.ok) "App instalado." else "Falha na instalação." })
            } catch (e: Exception) {
                val message = when (e) {
                    is PanelException, is SamsungCertException, is SigningException, is WgtException -> e.message
                    is kotlinx.coroutines.CancellationException -> "Instalação cancelada."
                    else -> e.message ?: e.javaClass.simpleName
                } ?: "Falha na instalação."
                finish(false, message)
            }
            val final = _state.value.install ?: return@launch
            withContext(Dispatchers.IO) {
                runCatching {
                    if (c.settings.panel().isConfigured) {
                        val report = if (final.ok) final.message else "${final.message}\n$tvOutput".trim()
                        val deviceId = _state.value.selected?.deviceId ?: sel.deviceId
                        c.panel.reportInstall(deviceId, app.id, app.versionId, final.ok, stage.name.lowercase(), code, report)
                    }
                }
            }
            work.listFiles()?.forEach { if (it.name.endsWith(".wgt")) it.delete() }
        }
    }

    fun cancelInstall() {
        installJob?.cancel()
    }

    fun clearInstall() = _state.update { it.copy(install = null) }

    /** Abre o login da Samsung no navegador e espera o retorno em localhost:4794. */
    private suspend fun samsungLogin(): SamsungAuth {
        step(InstallStep.LOGIN, null)
        detail("Faça login na sua conta Samsung no navegador e depois volte para este app.")
        val server = withContext(Dispatchers.IO) { LoginCallbackServer() }
        return try {
            _openUrl.emit(SamsungEndpoints.authorizeUrl(server.port))
            server.await().also {
                detail("Conta ${it.email} conectada.")
                step(InstallStep.CERTIFICATE, null)
            }
        } finally {
            server.close()
        }
    }

    private fun step(step: InstallStep, percent: Int?) = _state.update { s -> s.copy(install = s.install?.copy(step = step, percent = percent)) }

    private fun detail(text: String) = _state.update { s ->
        s.copy(install = s.install?.let { i -> i.copy(detail = text, log = (i.log + text).takeLast(200)) })
    }

    private fun log(text: String) = _state.update { s ->
        s.copy(install = s.install?.let { i -> i.copy(log = (i.log + text).takeLast(200)) })
    }

    private fun finish(ok: Boolean, message: String) = _state.update { s ->
        s.copy(install = s.install?.let { i -> i.copy(running = false, ok = ok, message = message, step = if (ok) InstallStep.DONE else i.step) })
    }

    // --- configurações -----------------------------------------------------------------------

    fun refreshAccount() = _state.update { it.copy(samsungEmail = c.profiles.email(), samsungDuids = c.profiles.duids()) }

    fun refreshPanel() = _state.update {
        val cfg = c.settings.panel()
        it.copy(panelConfigured = cfg.isConfigured, panelUrl = cfg.baseUrl, panelBuiltIn = c.settings.builtInPanel)
    }

    fun savePanel(url: String, key: String) {
        c.settings.savePanel(url, key)
        c.panel.clearToken()
        refreshPanel()
    }

    fun logoutSamsung() {
        c.profiles.forget()
        refreshAccount()
    }

    fun dismissMessage() = _state.update { it.copy(message = null) }
}
