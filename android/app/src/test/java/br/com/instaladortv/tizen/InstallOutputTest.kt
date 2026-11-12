package br.com.instaladortv.tizen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallOutputTest {
    @Test fun success() {
        listOf(
            "app_id[Pkg.App] installing[100]\nspend time for pkgcmd is [1520]ms",
            "install completed",
            "Pull skipped or failed\napp_id[Pkg.App] installing[100]",
        ).forEach { assertEquals(it, Outcome.SUCCESS, InstallOutput.diagnose(it).outcome) }
        assertEquals(Outcome.UNKNOWN, InstallOutput.diagnose("installing[88]").outcome)
    }

    @Test fun failures() {
        val cases = mapOf(
            "app_id[Pkg.App] install failed[118]" to ("118" to "assinatura ou certificado"),
            "install failed[118, -22]" to ("118,-22" to "bloqueado"),
            "app_id[Pkg.App] download failed[116]" to ("116" to "espaço"),
            "install failed[118, -4]" to ("118,-4" to "versão de Tizen"),
            "install failed[118019]" to ("118019" to "config.xml"),
            "install failed[118, -12]" to ("118,-12" to "DUID"),
            "install failed[118, -12], reason: Package with the same id and different certificate" to ("118,-12" to "outro certificado"),
            "install failed[118, -12], reason: Certificate in signature is expired.:<-6>" to ("118,-12" to "expirou"),
        )
        cases.forEach { (output, expected) ->
            val d = InstallOutput.diagnose(output)
            assertEquals(output, Outcome.FAILURE, d.outcome)
            assertEquals(output, expected.first, d.code)
            assertTrue("$output -> ${d.message}", d.message.contains(expected.second))
        }
        assertEquals(Outcome.FAILURE, InstallOutput.diagnose("error: transport connection lost").outcome)
    }

    @Test fun launch() {
        assertEquals(Outcome.SUCCESS, InstallOutput.diagnoseLaunch("successfully launched pid = 1"))
        assertEquals(Outcome.FAILURE, InstallOutput.diagnoseLaunch("launch failed"))
        assertEquals(Outcome.UNKNOWN, InstallOutput.diagnoseLaunch(""))
    }
}
