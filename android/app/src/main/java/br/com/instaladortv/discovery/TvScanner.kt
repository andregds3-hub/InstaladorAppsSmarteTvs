package br.com.instaladortv.discovery

import br.com.instaladortv.sdb.Sdb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Varre a /24 do celular procurando TVs: Samsung (8001 e SDB 26101) e Android TV (ADB 5555). */
class TvScanner(
    private val connectTimeoutMs: Int = 400,
    private val parallelism: Int = 48,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2000, TimeUnit.MILLISECONDS)
        .build(),
) {

    suspend fun scan(
        phoneIp: String,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
        onFound: (TvInfo) -> Unit = {},
    ): List<TvInfo> = coroutineScope {
        val hosts = hostsOf24(phoneIp).filter { it != phoneIp }
        val gate = Semaphore(parallelism)
        val done = AtomicInteger()
        hosts.map { ip ->
            async(Dispatchers.IO) {
                gate.withPermit {
                    probe(ip).also {
                        onProgress(done.incrementAndGet(), hosts.size)
                        if (it != null) onFound(it)
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }

    suspend fun probe(ip: String): TvInfo? = withContext(Dispatchers.IO) {
        val infoOpen = portOpen(ip, SAMSUNG_INFO_PORT)
        val sdbOpen = portOpen(ip, Sdb.PORT)
        val adbOpen = portOpen(ip, ADB_PORT)
        when {
            infoOpen || sdbOpen -> {
                val info = (if (infoOpen) fetchSamsungInfo(ip) else null) ?: TvInfo(ip, Platform.TIZEN)
                info.copy(sdbOpen = sdbOpen, adbOpen = adbOpen)
            }
            adbOpen -> TvInfo(ip, Platform.ANDROID_TV, adbOpen = true)
            else -> null
        }
    }

    fun portOpen(ip: String, port: Int, timeoutMs: Int = connectTimeoutMs): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(ip, port), timeoutMs) }
        true
    } catch (_: IOException) {
        false
    }

    fun fetchSamsungInfo(ip: String): TvInfo? = try {
        http.newCall(Request.Builder().url("http://$ip:$SAMSUNG_INFO_PORT/api/v2/").build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            TvInfo.fromSamsungApi(ip, JSONObject(resp.body?.string().orEmpty()))
        }
    } catch (_: Exception) {
        null
    }

    companion object {
        const val SAMSUNG_INFO_PORT = 8001
        const val ADB_PORT = 5555

        fun hostsOf24(ip: String): List<String> {
            val parts = ip.split('.')
            require(parts.size == 4 && parts.all { it.toIntOrNull() in 0..255 }) { "IPv4 inválido: $ip" }
            val prefix = parts.take(3).joinToString(".")
            return (1..254).map { "$prefix.$it" }
        }
    }
}
