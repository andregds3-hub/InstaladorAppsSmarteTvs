package br.com.instaladortv

import android.app.Application
import br.com.instaladortv.api.PanelClient
import br.com.instaladortv.cert.CaBundle
import br.com.instaladortv.cert.ProfileManager
import br.com.instaladortv.cert.SamsungCertClient
import br.com.instaladortv.data.AppSettings
import br.com.instaladortv.data.KeystoreBlobStore
import br.com.instaladortv.discovery.TvScanner
import br.com.instaladortv.tizen.TizenInstaller
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** Monta os serviços do app uma vez só. */
class InstaladorApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val secrets = KeystoreBlobStore(app)
    val settings = AppSettings(app, secrets)
    val panel = PanelClient(http, settings::panel, secrets, allowHttp = BuildConfig.DEBUG)
    val profiles = ProfileManager(secrets, SamsungCertClient(http), CaBundle.fromAssets(app))
    val scanner = TvScanner()
    val installer = TizenInstaller()
}
