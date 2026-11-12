package br.com.instaladortv.cert

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Socket
import java.net.URLEncoder

class SamsungLoginTest {
    private fun form(code: JSONObject, state: String = SamsungEndpoints.STATE) =
        "state=${URLEncoder.encode(state, "UTF-8")}&code=${URLEncoder.encode(code.toString(), "UTF-8")}"

    private val good = JSONObject().put("access_token", "tok").put("userId", "u1").put("inputEmailID", "a@b.c")

    @Test fun `authorize url`() {
        val url = SamsungEndpoints.authorizeUrl()
        assertTrue(url.startsWith("https://account.samsung.com/"))
        assertTrue("clientId=v285zxnl3h" in url)
        assertTrue("redirect_uri=http%3A%2F%2Flocalhost%3A4794%2Fsignin%2Fcallback" in url)
    }

    @Test fun `parse callback`() {
        val auth = SamsungEndpoints.parseCallback(form(good))
        assertEquals("tok", auth.accessToken)
        assertEquals("a@b.c", auth.email)
        assertTrue("tok" !in auth.toString())
    }

    @Test fun `rejects wrong state and missing email`() {
        assertTrue(runCatching { SamsungEndpoints.parseCallback(form(good, state = "x")) }.isFailure)
        val noEmail = JSONObject(good.toString()).put("inputEmailID", JSONObject.NULL)
        val error = runCatching { SamsungEndpoints.parseCallback(form(noEmail)) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("e-mail"))
    }

    @Test fun `callback server receives the browser post`() = runBlocking {
        LoginCallbackServer(port = 0).use { server ->
            val body = form(good).toByteArray()
            val reply = Socket("127.0.0.1", server.port).use { socket ->
                socket.getOutputStream().write(
                    ("POST /signin/callback HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/x-www-form-urlencoded\r\n" +
                        "Content-Length: ${body.size}\r\n\r\n").toByteArray() + body,
                )
                socket.getInputStream().readBytes().toString(Charsets.UTF_8)
            }
            assertTrue(reply.startsWith("HTTP/1.1 200"))
            assertTrue("instaladortv://login" in reply)
            assertEquals("u1", server.await(5_000).userId)
        }
    }
}
