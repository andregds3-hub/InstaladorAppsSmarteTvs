package br.com.instaladortv.cert

import br.com.instaladortv.data.MemoryBlobStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.Date

class ProfileManagerTest {
    private val authorCa = TestCa("Samsung VD Author CA (teste)")
    private val distCa = TestCa("VD DEVELOPER Public CA Class (teste)")

    private inner class FakeSamsung(var userId: String = "u1") : SamsungCertApi {
        var authorRequests = 0
        val distributorCsrs = mutableListOf<String>()
        override fun requestAuthor(auth: SamsungAuth, csrPem: String): ByteArray {
            authorRequests++
            return authorCa.issuePem(csrPem)
        }
        override fun requestDistributor(auth: SamsungAuth, csrPem: String, privilege: String): Pair<ByteArray, ByteArray> {
            assertEquals("public", privilege)
            distributorCsrs += csrPem
            return "<Profile><TestDevice>x</TestDevice></Profile>".toByteArray() to distCa.issuePem(csrPem)
        }
    }

    private val store = MemoryBlobStore()
    private val samsung = FakeSamsung()
    private var logins = 0
    private val login: suspend () -> SamsungAuth = { logins++; SamsungAuth("tok", samsung.userId, "dev@example.com") }
    private fun manager(now: Date = Date(), cas: List<java.security.cert.X509Certificate> = listOf(authorCa.cert, distCa.cert)) =
        ProfileManager(store, samsung, CaBundle(cas), now = { now })

    @Test fun `first tv logs in and issues both certificates with the CA chain`() = runTest {
        val profile = manager().ensure("DUIDTV0001", login)
        assertEquals(1, logins)
        assertEquals(1, samsung.authorRequests)
        assertEquals(listOf("DUIDTV0001"), profile.duids)
        assertEquals(authorCa.cert, profile.author.chain[1])
        assertEquals(distCa.cert, profile.distributor.chain[1])
        assertEquals(listOf("DUIDTV0001"), CertParser.duids(profile.distributor.chain[0]))
        assertArrayEquals("<Profile><TestDevice>x</TestDevice></Profile>".toByteArray(), profile.deviceProfileXml)
        assertEquals("dev@example.com", profile.email)
    }

    @Test fun `same tv again needs no login`() = runTest {
        manager().ensure("DUIDTV0001", login)
        assertNotNull(manager().readyFor("DUIDTV0001"))
        manager().ensure("DUIDTV0001", login = { fail("não devia pedir login"); throw IllegalStateException() })
        assertEquals(1, logins)
    }

    @Test fun `new tv keeps the author and adds the duid to the distributor`() = runTest {
        val first = manager().ensure("DUIDTV0001", login)
        val second = manager().ensure("DUIDTV0002", login)
        assertEquals(1, samsung.authorRequests)
        assertEquals(first.author.chain[0], second.author.chain[0])
        assertEquals(listOf("DUIDTV0001", "DUIDTV0002"), CertParser.duids(second.distributor.chain[0]))
        assertEquals(2, samsung.distributorCsrs.size)
    }

    @Test fun `expired profile is renewed`() = runTest {
        manager().ensure("DUIDTV0001", login)
        val later = Date(System.currentTimeMillis() + 400L * 86_400_000)
        assertNull(manager(now = later).readyFor("DUIDTV0001"))
    }

    @Test fun `another samsung account is refused`() = runTest {
        manager().ensure("DUIDTV0001", login)
        samsung.userId = "outra"
        val error = runCatching { manager().ensure("DUIDTV0002", login) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), error is SamsungCertException && "outra conta" in error.message.orEmpty())
    }

    @Test fun `missing CA gives a clear error`() = runTest {
        val error = runCatching { manager(cas = emptyList()).ensure("DUIDTV0001", login) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty(), "CA da Samsung" in error?.message.orEmpty())
    }

    @Test fun `forget removes everything`() = runTest {
        manager().ensure("DUIDTV0001", login)
        manager().forget()
        assertNull(manager().email())
        assertNull(manager().readyFor("DUIDTV0001"))
    }

    @Test fun `csr carries email and duids`() {
        val keys = CsrFactory.newKeyPair()
        val cert = CertParser.parse(distCa.issuePem(CsrFactory.distributorCsr(keys, "dev@example.com", listOf("AAA111", "BBB222"))))[0]
        assertEquals(listOf("AAA111", "BBB222"), CertParser.duids(cert))
        assertTrue(cert.subjectX500Principal.name.contains("CN=TizenSDK"))
        assertTrue(runCatching { CsrFactory.distributorCsr(keys, "x@y", listOf("bad duid")) }.isFailure)
    }

    @Test fun `cert parser accepts pem der base64 and json`() {
        val pem = distCa.issuePem(CsrFactory.authorCsr(CsrFactory.newKeyPair(), "X"))
        val cert = CertParser.parse(pem)[0]
        assertEquals(cert, CertParser.parse(cert.encoded)[0])
        assertEquals(cert, CertParser.parse(org.bouncycastle.util.encoders.Base64.encode(cert.encoded))[0])
        assertEquals(cert, CertParser.parse("{\"certificate\": ${org.json.JSONObject.quote(String(pem))}}".toByteArray())[0])
        assertTrue(runCatching { CertParser.parse("<erro>".toByteArray()) }.isFailure)
    }
}
