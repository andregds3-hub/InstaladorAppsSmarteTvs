package br.com.instaladortv.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class DevModeStatusTest {
    private val tv = TvInfo("192.168.1.9", Platform.TIZEN, developerMode = true, developerIp = "192.168.1.12", sdbOpen = true)

    @Test fun ready() = assertEquals(DevModeStatus.READY, DevModeStatus.of(tv, "192.168.1.12"))

    @Test fun `released to another device`() =
        assertEquals(DevModeStatus.OTHER_DEVICE, DevModeStatus.of(tv.copy(developerIp = "192.168.1.2"), "192.168.1.12"))

    @Test fun off() = assertEquals(DevModeStatus.OFF, DevModeStatus.of(tv.copy(developerMode = false), "192.168.1.12"))

    @Test fun `right ip but sdb closed needs restart`() =
        assertEquals(DevModeStatus.NEEDS_RESTART, DevModeStatus.of(tv.copy(sdbOpen = false), "192.168.1.12"))

    @Test fun `no api info but sdb open`() =
        assertEquals(DevModeStatus.READY, DevModeStatus.of(TvInfo("10.0.0.5", Platform.TIZEN, sdbOpen = true), "10.0.0.7"))
}
