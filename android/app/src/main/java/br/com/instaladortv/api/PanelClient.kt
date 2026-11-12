package br.com.instaladortv.api

import br.com.instaladortv.data.BlobStore
import br.com.instaladortv.data.PanelConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

class PanelException(message: String, val status: Int? = null) : Exception(message) {
    val needsAccessCode: Boolean get() = status == 401 && "código" in message.lowercase()
}

data class PanelApp(
    val id: Long,
    val name: String,
    val description: String,
    val platform: String,
    val iconUrl: String?,
    val versionId: Long,
    val version: String,
    val changelog: String,
    val size: Long,
    val sha256: String,
    val packageId: String?,
    val packageAppId: String?,
    val requiredVersion: String?,
    val downloadUrl: String,
) {
    companion object {
        fun fromJson(o: JSONObject) = PanelApp(
            id = o.getLong("id"),
            name = o.optString("name"),
            description = o.optString("description"),
            platform = o.optString("platform"),
            iconUrl = o.optString("icon_url").takeIf { it.isNotBlank() && it != "null" },
            versionId = o.getLong("version_id"),
            version = o.optString("version"),
            changelog = o.optString("changelog"),
            size = o.optLong("size"),
            sha256 = o.optString("sha256"),
            packageId = o.optString("package_id").takeIf { it.isNotBlank() && it != "null" },
            packageAppId = o.optString("package_app_id").takeIf { it.isNotBlank() && it != "null" },
            requiredVersion = o.optString("required_version").takeIf { it.isNotBlank() && it != "null" },
            downloadUrl = o.getString("download_url"),
        )
    }
}

data class DeviceReport(
    val platform: String,
    val duid: String?,
    val name: String,
    val model: String,
    val modelName: String,
    val year: Int?,
    val firmware: String,
    val osVersion: String,
    val localIp: String,
)

/**
 * Cliente da API do painel (`/api/v1`). Chamadas bloqueantes: use em `Dispatchers.IO`.
 * O token vem de `POST /auth` com a chave do app (e o código de acesso, quando o painel exige)
 * e fica guardado cifrado; um 401 renova o token uma vez.
 */
class PanelClient(
    private val http: OkHttpClient,
    private val config: () -> PanelConfig,
    private val secrets: BlobStore,
    private val allowHttp: Boolean = false,
) {
    private val json = "application/json".toMediaType()

    private fun base(): HttpUrl {
        val cfg = config()
        if (!cfg.isConfigured) throw PanelException("Configure o endereço do painel e a chave do app em Configurações.")
        val url = cfg.baseUrl.toHttpUrlOrNull() ?: throw PanelException("Endereço do painel inválido: ${cfg.baseUrl}")
        if (!url.isHttps && !allowHttp) throw PanelException("O painel precisa usar HTTPS.")
        return url
    }

    private fun endpoint(path: String): HttpUrl = base().newBuilder().addPathSegments("api/v1/$path").build()

    fun clearToken() = secrets.delete(TOKEN)

    fun authenticate(): String {
        val cfg = config()
        val body = JSONObject().put("access_code", cfg.accessCode.ifBlank { JSONObject.NULL })
        val request = Request.Builder().url(endpoint("auth"))
            .header("X-App-Key", cfg.appKey)
            .post(body.toString().toRequestBody(json))
            .build()
        val token = execute(request) { JSONObject(it.body!!.string()).getString("token") }
        secrets.write(TOKEN, token.toByteArray())
        return token
    }

    private fun <T> authed(build: (Request.Builder) -> Request.Builder, read: (Response) -> T): T {
        val token = secrets.read(TOKEN)?.toString(Charsets.UTF_8) ?: authenticate()
        return try {
            execute(build(Request.Builder()).header("Authorization", "Bearer $token").build(), read)
        } catch (e: PanelException) {
            if (e.status != 401) throw e
            clearToken()
            val fresh = authenticate()
            execute(build(Request.Builder()).header("Authorization", "Bearer $fresh").build(), read)
        }
    }

    private fun <T> execute(request: Request, read: (Response) -> T): T {
        val response = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw PanelException("Sem conexão com o painel: ${e.message}")
        }
        response.use {
            if (!it.isSuccessful) throw PanelException(errorMessage(it), it.code)
            return read(it)
        }
    }

    private fun errorMessage(response: Response): String {
        val detail = runCatching {
            when (val d = JSONObject(response.body?.string().orEmpty()).opt("detail")) {
                is String -> d
                is JSONArray -> d.optJSONObject(0)?.optString("msg")
                else -> null
            }
        }.getOrNull()
        return detail ?: when (response.code) {
            401 -> "Acesso não autorizado pelo painel."
            403 -> "Acesso bloqueado pelo painel."
            404 -> "Não encontrado no painel."
            429 -> "Muitas tentativas. Aguarde um minuto."
            else -> "O painel respondeu com erro ${response.code}."
        }
    }

    fun apps(platform: String, model: String, year: Int?, osVersion: String): List<PanelApp> = authed({ b ->
        val url = endpoint("apps").newBuilder().addQueryParameter("platform", platform)
            .apply {
                if (model.isNotBlank()) addQueryParameter("model", model)
                if (year != null) addQueryParameter("year", year.toString())
                if (osVersion.isNotBlank()) addQueryParameter("os_version", osVersion)
            }.build()
        b.url(url).get()
    }) { response ->
        val array = JSONArray(response.body!!.string())
        (0 until array.length()).map { PanelApp.fromJson(array.getJSONObject(it)) }
    }

    /** Baixa a versão atual e confere o SHA-256 com a lista e com o cabeçalho; arquivo inválido é apagado. */
    fun download(app: PanelApp, target: File, onProgress: (Long, Long) -> Unit = { _, _ -> }) {
        val url = app.downloadUrl.toHttpUrlOrNull() ?: throw PanelException("Endereço de download inválido.")
        if (url.host != base().host || (!url.isHttps && !allowHttp)) throw PanelException("Download fora do painel recusado.")
        val digest = MessageDigest.getInstance("SHA-256")
        val header = authed({ it.url(url).get() }) { response ->
            val total = response.body!!.contentLength().takeIf { it > 0 } ?: app.size
            target.outputStream().use { out ->
                response.body!!.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            response.header("X-Content-SHA256").orEmpty()
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        val expected = app.sha256.lowercase()
        if (actual != expected || (header.isNotBlank() && header.lowercase() != expected)) {
            target.delete()
            throw PanelException("O arquivo baixado está corrompido (SHA-256 não confere). Tente de novo.")
        }
    }

    fun registerDevice(device: DeviceReport): Long {
        val body = JSONObject()
            .put("platform", device.platform)
            .put("duid", device.duid ?: JSONObject.NULL)
            .put("name", device.name.take(200))
            .put("model", device.model.take(80))
            .put("model_name", device.modelName.take(80))
            .put("year", device.year ?: JSONObject.NULL)
            .put("firmware", device.firmware.take(80))
            .put("os_version", device.osVersion.take(20))
            .put("local_ip", device.localIp.take(45))
        return authed({ it.url(endpoint("devices")).post(body.toString().toRequestBody(json)) }) {
            JSONObject(it.body!!.string()).getLong("id")
        }
    }

    fun reportInstall(deviceId: Long?, appId: Long, versionId: Long?, ok: Boolean, stage: String, code: String, message: String) {
        val body = JSONObject()
            .put("device_id", deviceId ?: JSONObject.NULL)
            .put("app_id", appId)
            .put("version_id", versionId ?: JSONObject.NULL)
            .put("status", if (ok) "success" else "failure")
            .put("stage", stage.take(40))
            .put("error_code", code.take(40))
            .put("message", message.take(4000))
        authed({ it.url(endpoint("installs")).post(body.toString().toRequestBody(json)) }) { }
    }

    private companion object {
        const val TOKEN = "panel-token"
    }
}
