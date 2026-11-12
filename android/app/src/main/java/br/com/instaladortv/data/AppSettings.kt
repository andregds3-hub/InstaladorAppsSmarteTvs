package br.com.instaladortv.data

import android.content.Context
import br.com.instaladortv.BuildConfig

/** Endereço do painel e chave do app: embutidos no build ou digitados em Configurações. */
data class PanelConfig(val baseUrl: String, val appKey: String, val accessCode: String) {
    val isConfigured: Boolean get() = baseUrl.isNotBlank() && appKey.isNotBlank()
}

class AppSettings(context: Context, private val secrets: BlobStore) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    val builtInPanel: Boolean get() = BuildConfig.PANEL_URL.isNotBlank()

    fun panel(): PanelConfig = PanelConfig(
        baseUrl = BuildConfig.PANEL_URL.ifBlank { prefs.getString("panel_url", "").orEmpty() },
        appKey = BuildConfig.PANEL_APP_KEY.ifBlank { secrets.read("app-key")?.toString(Charsets.UTF_8).orEmpty() },
        accessCode = secrets.read("access-code")?.toString(Charsets.UTF_8).orEmpty(),
    )

    fun savePanel(baseUrl: String, appKey: String) {
        prefs.edit().putString("panel_url", baseUrl.trim().trimEnd('/')).apply()
        if (appKey.isNotBlank()) secrets.write("app-key", appKey.trim().toByteArray())
    }

    fun saveAccessCode(code: String) {
        if (code.isBlank()) secrets.delete("access-code") else secrets.write("access-code", code.trim().toByteArray())
    }
}
