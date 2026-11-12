package br.com.instaladortv.cert

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread

class SamsungCertException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Token da conta Samsung do cliente. Nunca vai para log nem para o painel. */
class SamsungAuth(val accessToken: String, val userId: String, val email: String) {
    override fun toString() = "SamsungAuth(userId=$userId, email=$email)"
}

/**
 * Endereços da Samsung Certificate Extension (API não documentada, ver docs/certificados-tizen.md).
 * Confirmados com conta real em 2026-11-11.
 */
object SamsungEndpoints {
    const val SIGN_IN_GATE = "https://account.samsung.com/accounts/be1dce529476c1a6d407c4c7578c31bd/signInGate"
    const val CLIENT_ID = "v285zxnl3h"
    const val STATE = "accountcheckdogeneratedstatetext"
    const val CALLBACK_PORT = 4794
    const val CALLBACK_PATH = "/signin/callback"
    const val AUTHOR_URL = "https://svdca.samsungqbe.com/apis/v3/authors"
    const val DISTRIBUTOR_V1_URL = "https://svdca.samsungqbe.com/apis/v1/distributors"
    const val DISTRIBUTOR_V3_URL = "https://svdca.samsungqbe.com/apis/v3/distributors"
    const val PLATFORM = "VD"

    fun redirectUri(port: Int = CALLBACK_PORT) = "http://localhost:$port$CALLBACK_PATH"

    fun authorizeUrl(port: Int = CALLBACK_PORT): String {
        fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        return "$SIGN_IN_GATE?locale=&clientId=$CLIENT_ID&redirect_uri=${enc(redirectUri(port))}&state=$STATE&tokenType=TOKEN"
    }

    /** O login devolve `state` e `code` (um JSON com access_token, userId e inputEmailID). */
    fun parseCallback(form: String): SamsungAuth {
        val fields = form.split('&').mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else URLDecoder.decode(it.substring(0, i), "UTF-8") to URLDecoder.decode(it.substring(i + 1), "UTF-8")
        }.toMap()
        val state = fields["state"].orEmpty()
        if (state.isNotEmpty() && state != STATE) throw SamsungCertException("Resposta de login inválida (state diferente).")
        val code = runCatching { JSONObject(fields["code"].orEmpty()) }.getOrElse {
            throw SamsungCertException("A Samsung não devolveu os dados do login.")
        }
        val token = code.optString("access_token")
        val userId = code.optString("userId")
        val email = code.optString("inputEmailID").takeUnless { it == "null" }.orEmpty()
        if (token.isBlank() || userId.isBlank()) throw SamsungCertException("A Samsung não devolveu o token de acesso.")
        if (email.isBlank()) {
            throw SamsungCertException("Sua conta Samsung não tem e-mail cadastrado. Cadastre um e-mail na conta e tente de novo.")
        }
        return SamsungAuth(token, userId, email)
    }
}

/**
 * Recebe o retorno do login da Samsung no próprio celular: o navegador posta o formulário para
 * `http://localhost:4794/signin/callback`. Escuta só no loopback (nunca na rede Wi-Fi).
 */
class LoginCallbackServer(port: Int = SamsungEndpoints.CALLBACK_PORT) : Closeable {
    private val sockets: List<ServerSocket> = listOf("127.0.0.1", "::1").mapNotNull { host ->
        runCatching { ServerSocket(port, 8, InetAddress.getByName(host)) }.getOrNull()
    }
    private val result = CompletableDeferred<SamsungAuth>()
    val port: Int = sockets.firstOrNull()?.localPort
        ?: throw SamsungCertException("Não foi possível abrir a porta $port para o login da Samsung. Feche outros apps e tente de novo.")

    init {
        sockets.forEach { server ->
            thread(isDaemon = true, name = "samsung-login") {
                while (!server.isClosed && !result.isCompleted) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    client.use { handle(it) }
                }
            }
        }
    }

    suspend fun await(timeoutMs: Long = 5 * 60_000L): SamsungAuth = try {
        withTimeout(timeoutMs) { result.await() }
    } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
        throw SamsungCertException("O login da Samsung não foi concluído a tempo.", e)
    }

    override fun close() {
        sockets.forEach { runCatching { it.close() } }
    }

    private fun handle(socket: Socket) {
        socket.soTimeout = 15_000
        val input = socket.getInputStream()
        val requestLine = readLine(input) ?: return
        var length = 0
        while (true) {
            val line = readLine(input) ?: return
            if (line.isEmpty()) break
            if (line.lowercase().startsWith("content-length:")) length = line.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0).orEmpty()
        val target = parts.getOrNull(1).orEmpty()
        if (!target.startsWith(SamsungEndpoints.CALLBACK_PATH)) {
            respond(socket, 404, "Não encontrado")
            return
        }
        val form = when (method) {
            "POST" -> String(readExactly(input, length.coerceIn(0, 64 * 1024)), Charsets.UTF_8)
            else -> target.substringAfter('?', "")
        }
        try {
            val auth = SamsungEndpoints.parseCallback(form)
            respond(socket, 200, "Login concluído. Volte para o Instalador TV.")
            result.complete(auth)
        } catch (e: SamsungCertException) {
            respond(socket, 400, e.message ?: "Falha no login.")
            result.completeExceptionally(e)
        }
    }

    private fun respond(socket: Socket, status: Int, message: String) {
        val html = """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width">
            |<title>Instalador TV</title></head><body style="font-family:sans-serif;text-align:center;padding:2em">
            |<p style="font-size:1.2em">${message.replace("<", "&lt;")}</p>
            |<p><a href="instaladortv://login" style="font-size:1.2em">Voltar ao app</a></p></body></html>""".trimMargin()
        val body = html.toByteArray(Charsets.UTF_8)
        val reason = if (status == 200) "OK" else "Error"
        val head = "HTTP/1.1 $status $reason\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        socket.getOutputStream().apply { write(head.toByteArray(Charsets.US_ASCII)); write(body); flush() }
    }

    private fun readLine(input: InputStream): String? {
        val bytes = java.io.ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (bytes.size() == 0) null else bytes.toString("UTF-8")
            if (b == '\n'.code) return bytes.toString("UTF-8").trimEnd('\r')
            if (bytes.size() > 8192) return null
            bytes.write(b)
        }
    }

    private fun readExactly(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val n = input.read(buffer, offset, size - offset)
            if (n < 0) break
            offset += n
        }
        return buffer.copyOf(offset)
    }
}
