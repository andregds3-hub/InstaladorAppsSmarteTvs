package br.com.instaladortv.sdb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder

class SdbClientTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun `header layout matches ADB`() {
        val packed = SdbMessage(Sdb.A_WRTE, 1, 2, "abc".toByteArray()).pack()
        val h = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("WRTE", String(packed, 0, 4))
        h.position(4)
        assertEquals(1, h.int)
        assertEquals(2, h.int)
        assertEquals(3, h.int)
        assertEquals(0x61 + 0x62 + 0x63, h.int)
        assertEquals(Sdb.A_WRTE xor -1, h.int)
    }

    @Test(expected = SdbException::class) fun `bad magic rejected`() {
        val packed = SdbMessage(Sdb.A_CNXN, 0, 0).pack()
        packed[20] = (packed[20].toInt() xor 0xFF).toByte()
        SdbMessage.read(packed.inputStream())
    }

    @Test fun `banner and capability parsing`() {
        assertEquals("QE55Q60", parseBanner("device::QE55Q60::0\u0000".toByteArray()).model)
        val body = "platform_version:6.5\nsdk_toolpath:/x\n".toByteArray()
        val withPrefix = byteArrayOf(body.size.toByte(), 0) + body
        assertEquals("6.5", parseCapability(withPrefix)["platform_version"])
        assertEquals("/x", parseCapability(body)["sdk_toolpath"])
        assertEquals("6.5", parseCapability("%04x".format(body.size).toByteArray() + body)["platform_version"])
    }

    @Test fun `handshake capability and shell`() {
        FakeSdbd(shellReplies = mapOf("0 applist" to "a\nb\n")).use { tv ->
            SdbClient("127.0.0.1", tv.port).use { client ->
                assertEquals("QE55Q60", client.connect().model)
                assertEquals(0x3FF9C, client.maxPayload) // min(maxdata da TV, 0x40000 do host)
                assertEquals("armv7", client.capability()["cpu_arch"])
                assertEquals("a\nb\n", client.shell("0 applist"))
            }
            assertEquals(Sdb.HOST_VERSION, tv.hostCnxn!!.arg0)
            assertArrayEquals(Sdb.HOST_BANNER, tv.hostCnxn!!.data)
        }
    }

    @Test fun `push respects device maxdata`() {
        val file = tmp.newFile("app.wgt").apply { writeBytes(ByteArray(300 * 1024 + 7) { it.toByte() }) }
        FakeSdbd(maxdata = 4096).use { tv ->
            var last = 0L
            SdbClient("127.0.0.1", tv.port).use { client ->
                client.connect()
                client.push(file, "/tmp/app.wgt") { sent, _ -> last = sent }
            }
            assertTrue(tv.maxWrte <= 4096)
            assertArrayEquals(file.readBytes(), tv.files["/tmp/app.wgt"])
            assertEquals(file.length(), last)
        }
    }

    @Test fun `push failure message surfaces`() {
        val file = tmp.newFile("a.wgt").apply { writeText("x") }
        FakeSdbd(syncFail = "No space left on device").use { tv ->
            SdbClient("127.0.0.1", tv.port).use { client ->
                client.connect()
                val error = runCatching { client.push(file, "/tmp/a.wgt") }.exceptionOrNull()
                assertTrue(error?.message.orEmpty().contains("No space left"))
            }
        }
    }
}
