package br.com.instaladortv.tizen

import br.com.instaladortv.sdb.FakeSdbd
import br.com.instaladortv.util.sha256Hex
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

const val CONFIG_XML = """<?xml version="1.0" encoding="UTF-8"?>
<widget xmlns="http://www.w3.org/ns/widgets" xmlns:tizen="http://tizen.org/ns/widgets" id="http://example.com/player" version="1.2.3">
    <tizen:application id="AbCdEf1234.Player" package="AbCdEf1234" required_version="2.4"/>
    <name>Player</name>
</widget>"""

fun makeWgt(file: File, signed: Boolean, config: String = CONFIG_XML): File {
    ZipOutputStream(file.outputStream()).use { zip ->
        val entries = mutableMapOf("config.xml" to config, "index.html" to "<html></html>")
        if (signed) {
            entries["author-signature.xml"] = "<Signature/>"
            entries["signature1.xml"] = "<Signature/>"
        }
        entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name))
            zip.write(text.toByteArray())
            zip.closeEntry()
        }
    }
    return file
}

class TizenInstallerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val remote = "/home/owner/share/tmp/sdk_tools/tmp/AbCdEf1234.wgt"
    private val install = "0 vd_appinstall AbCdEf1234 $remote"

    @Test fun `full flow pushes installs and launches`() = runTest {
        val wgt = makeWgt(tmp.newFile("a.wgt"), signed = true)
        val replies = mapOf(
            install to "app_id[AbCdEf1234.Player] installing[100]\n",
            "0 execute AbCdEf1234.Player" to "successfully launched pid = 42",
        )
        FakeSdbd(shellReplies = replies).use { tv ->
            val stages = mutableListOf<Stage>()
            val result = TizenInstaller(tv.port).install("127.0.0.1", wgt, wgt.sha256Hex()) { stages += it.stage }
            assertTrue(result.message, result.ok)
            assertEquals("6.5", result.platformVersion)
            assertArrayEquals(wgt.readBytes(), tv.files[remote])
            assertEquals(listOf(install, "0 execute AbCdEf1234.Player"), tv.commands.toList())
            assertEquals(Stage.DONE, stages.last())
        }
    }

    @Test fun `falls back to was_execute`() = runTest {
        val wgt = makeWgt(tmp.newFile("a.wgt"), signed = true)
        FakeSdbd(shellReplies = mapOf(install to "install completed", "0 was_execute AbCdEf1234.Player" to "launched")).use { tv ->
            assertTrue(TizenInstaller(tv.port).install("127.0.0.1", wgt, null).ok)
            assertEquals("0 was_execute AbCdEf1234.Player", tv.commands.last())
        }
    }

    @Test fun `refused execute falls back to was_execute`() = runTest {
        val wgt = makeWgt(tmp.newFile("a.wgt"), signed = true)
        val replies = mapOf(install to "install completed", "0 was_execute AbCdEf1234.Player" to "app_id[AbCdEf1234] launched")
        FakeSdbd(shellReplies = replies, refuse = setOf("shell:0 execute AbCdEf1234.Player")).use { tv ->
            val result = TizenInstaller(tv.port).install("127.0.0.1", wgt, null)
            assertTrue(result.message, result.ok)
            assertEquals(listOf(install, "0 was_execute AbCdEf1234.Player"), tv.commands.toList())
        }
    }

    @Test fun `permit pushes device profile before the package`() = runTest {
        val wgt = makeWgt(tmp.newFile("a.wgt"), signed = true)
        val profile = tmp.newFile("device-profile.xml").apply { writeText("<Profile><TestDevice>DUID</TestDevice></Profile>") }
        FakeSdbd(shellReplies = mapOf(install to "install completed")).use { tv ->
            val result = TizenInstaller(tv.port).install("127.0.0.1", wgt, null, launch = false, permitProfile = profile)
            assertTrue(result.message, result.ok)
            assertEquals(listOf("/home/developer/device-profile.xml", remote), tv.files.keys.toList())
            assertArrayEquals(profile.readBytes(), tv.files["/home/developer/device-profile.xml"])
        }
    }

    @Test fun `certificate rejection stops before launch`() = runTest {
        val wgt = makeWgt(tmp.newFile("a.wgt"), signed = true)
        FakeSdbd(shellReplies = mapOf(install to "install failed[118, -12]")).use { tv ->
            val result = TizenInstaller(tv.port).install("127.0.0.1", wgt, null)
            assertFalse(result.ok)
            assertEquals("118,-12", result.code)
            assertTrue(result.message.contains("DUID"))
            assertEquals(listOf(install), tv.commands.toList())
        }
    }

    @Test fun `hash mismatch and unsigned package never connect`() = runTest {
        val signed = makeWgt(tmp.newFile("a.wgt"), signed = true)
        assertEquals(Stage.HASH, TizenInstaller(1).install("127.0.0.1", signed, "0".repeat(64)).stage)
        val unsigned = makeWgt(tmp.newFile("b.wgt"), signed = false)
        assertTrue(TizenInstaller(1).install("127.0.0.1", unsigned, null).message.contains("não está assinado"))
    }

    @Test fun `unreachable tv explains developer mode`() = runTest {
        val wgt = makeWgt(tmp.newFile("a.wgt"), signed = true)
        val result = TizenInstaller(1).install("127.0.0.1", wgt, null)
        assertEquals(Stage.CONNECT, result.stage)
        assertTrue(result.message.contains("Modo Desenvolvedor"))
    }

    @Test fun `ids that could inject shell are rejected`() {
        val evil = CONFIG_XML.replace("package=\"AbCdEf1234\"", "package=\"x; reboot\"")
        val result = runCatching { WgtInfo.read(makeWgt(tmp.newFile("e.wgt"), signed = true, config = evil)) }
        assertTrue(result.exceptionOrNull() is WgtException)
    }
}
