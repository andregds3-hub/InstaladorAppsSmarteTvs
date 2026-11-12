package br.com.instaladortv.discovery

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvInfoTest {
    @Test fun `parses samsung api v2`() {
        val json = JSONObject(
            """{"device":{"OS":"Tizen","developerIP":"192.168.0.20","developerMode":"1",
               "duid":"uuid:1f2e","model":"18_KANTM2_QTV","modelName":"QN55Q6FNA","name":"[TV] Q6"},
               "name":"[TV] Q6","version":"2.0.25"}""",
        )
        val tv = TvInfo.fromSamsungApi("192.168.0.50", json)
        assertEquals("QN55Q6FNA", tv.modelName)
        assertEquals(2018, tv.year)
        assertEquals("4.0", tv.factoryTizen)
        assertEquals(true, tv.developerMode)
        assertEquals("192.168.0.20", tv.developerIp)
        assertEquals("uuid:1f2e", tv.duid)
    }

    @Test fun `missing fields`() {
        val tv = TvInfo.fromSamsungApi("10.0.0.2", JSONObject("{}"))
        assertNull(tv.developerMode)
        assertNull(tv.year)
    }

    @Test fun `guess year`() {
        assertEquals(2016, TvInfo.guessYear("16_JAZZM_UHD"))
        assertNull(TvInfo.guessYear("KANTM"))
    }

    @Test fun `hosts of 24`() {
        val hosts = TvScanner.hostsOf24("192.168.0.37")
        assertEquals(254, hosts.size)
        assertEquals("192.168.0.1", hosts.first())
        assertEquals("192.168.0.254", hosts.last())
        assertTrue(runCatching { TvScanner.hostsOf24("300.1.1.1") }.isFailure)
    }
}
