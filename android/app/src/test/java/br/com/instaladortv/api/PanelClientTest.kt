package br.com.instaladortv.api

import br.com.instaladortv.data.MemoryBlobStore
import br.com.instaladortv.data.PanelConfig
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest

class PanelClientTest {
    @get:Rule val tmp = TemporaryFolder()
    private val server = MockWebServer().apply { start() }
    private var accessCode = ""
    private val client = PanelClient(
        OkHttpClient(),
        { PanelConfig(server.url("/").toString(), "app-key-123", accessCode) },
        MemoryBlobStore(),
        allowHttp = true,
    )

    @After fun stop() = server.shutdown()

    private fun token(t: String = "tok1") = MockResponse().setBody(JSONObject().put("token", t).put("expires_at", "2030-01-01T00:00:00").toString())

    private fun appJson(sha: String, url: String) = JSONObject()
        .put("id", 7).put("name", "Player").put("description", "").put("platform", "tizen").put("icon_url", JSONObject.NULL)
        .put("version_id", 3).put("version", "1.2.3").put("changelog", "").put("released_at", "2026-01-01T00:00:00")
        .put("size", 5).put("sha256", sha).put("package_id", "AbCdEf1234").put("package_app_id", "AbCdEf1234.Player")
        .put("required_version", "2.4").put("download_url", url)

    @Test fun `auth then list apps with filters`() {
        server.enqueue(token())
        server.enqueue(MockResponse().setBody(JSONArray().put(appJson("ab", server.url("/api/v1/apps/7/download").toString())).toString()))
        val apps = client.apps("tizen", "16_JAZZL_UHD_BASIC", 2016, "2.4.0")
        assertEquals("Player", apps.single().name)
        assertEquals("AbCdEf1234", apps.single().packageId)
        val auth = server.takeRequest()
        assertEquals("/api/v1/auth", auth.path)
        assertEquals("app-key-123", auth.getHeader("X-App-Key"))
        assertEquals(JSONObject.NULL, JSONObject(auth.body.readUtf8()).get("access_code"))
        val list = server.takeRequest()
        assertEquals("Bearer tok1", list.getHeader("Authorization"))
        assertEquals("/api/v1/apps?platform=tizen&model=16_JAZZL_UHD_BASIC&year=2016&os_version=2.4.0", list.path)
    }

    @Test fun `expired token is renewed once`() {
        server.enqueue(token("old"))
        server.enqueue(MockResponse().setBody("[]"))
        client.apps("tizen", "", null, "")
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"detail\":\"Token inválido ou expirado.\"}"))
        server.enqueue(token("new"))
        server.enqueue(MockResponse().setBody("[]"))
        client.apps("tizen", "", null, "")
        repeat(3) { server.takeRequest() }
        assertEquals("/api/v1/auth", server.takeRequest().path)
        assertEquals("Bearer new", server.takeRequest().getHeader("Authorization"))
    }

    @Test fun `access code required is detected`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"detail\":\"Informe o código de acesso.\"}"))
        val error = runCatching { client.apps("tizen", "", null, "") }.exceptionOrNull() as PanelException
        assertTrue(error.needsAccessCode)
        assertEquals("Informe o código de acesso.", error.message)
    }

    @Test fun `download verifies sha256`() {
        val content = "hello".toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }
        val app = PanelApp.fromJson(appJson(sha, server.url("/api/v1/apps/7/download").toString()))
        server.enqueue(token())
        server.enqueue(MockResponse().setBody(okio.Buffer().write(content)).setHeader("X-Content-SHA256", sha))
        val ok = tmp.newFile("ok.wgt")
        client.download(app, ok)
        assertEquals("hello", ok.readText())

        server.enqueue(MockResponse().setBody("tampered").setHeader("X-Content-SHA256", sha))
        val bad = tmp.newFile("bad.wgt")
        val error = runCatching { client.download(app, bad) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("corrompido"))
        assertFalse(bad.exists())
    }

    @Test fun `download from another host is refused`() {
        val app = PanelApp.fromJson(appJson("ab", "http://evil.example/app.wgt"))
        val error = runCatching { client.download(app, tmp.newFile("x.wgt")) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("fora do painel"))
    }

    @Test fun `https is required unless allowed`() {
        val strict = PanelClient(OkHttpClient(), { PanelConfig(server.url("/").toString(), "k", "") }, MemoryBlobStore())
        val error = runCatching { strict.apps("tizen", "", null, "") }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("HTTPS"))
    }
}
