package org.hyperion.audioreactive

import org.junit.Assert.*
import org.junit.Test

class HyperionDiscoveryTest {
    private val uuid = "123e4567-e89b-12d3-a456-426614174000"
    private val response = """{"success":true,"info":{"uuid":"$uuid","hostname":"TV Hyperion"}}"""

    @Test fun parserRequiresServerUuidAndExactTrustedHost() {
        val device = HyperionDiscovery.parseServerInfo("192.168.1.158", response)!!
        assertEquals("uuid:$uuid", device.identity)
        assertEquals("192.168.1.158", device.host)
        assertNull(HyperionDiscovery.parseServerInfo("192.168.2.25", response))
        assertNull(HyperionDiscovery.parseServerInfo("192.168.1.25", response))
        assertNull(HyperionDiscovery.parseServerInfo("192.168.1.158", "{\"hostname\":\"mutable name\",\"id\":1}"))
        assertFalse(HyperionDiscovery.isPermittedHost("192.168.1.0"))
        assertFalse(HyperionDiscovery.isPermittedHost("10.0.0.1"))
    }

    @Test fun parserFallsBackOnlyToImmutableSysInfoHyperionId() {
        val serverInfoWithoutUuid = """{"success":true,"info":{"hostname":"TV Hyperion","instance":[{"instance":0}]}}"""
        val sysInfo = """{"success":true,"info":{"hyperion":{"id":"$uuid","version":"2.0.17"}}}"""
        val device = HyperionDiscovery.parseServerInfo("192.168.1.158", serverInfoWithoutUuid, sysInfo)!!
        assertEquals("uuid:$uuid", device.identity)
        assertEquals("TV Hyperion", device.name)
        assertNull(HyperionDiscovery.parseServerInfo("192.168.1.158", serverInfoWithoutUuid, """{"info":{"id":"$uuid"}}"""))
        assertNull(HyperionDiscovery.parseServerInfo("192.168.1.158", serverInfoWithoutUuid, """{"info":{"other":{"hyperion":{"id":"$uuid"}},"hyperion":{"version":"2.0.17"}}}"""))
        assertNull(HyperionDiscovery.parseServerInfo("192.168.1.25", serverInfoWithoutUuid, sysInfo))
    }

    @Test fun validationSkipsSysInfoWhenServerInfoAlreadyHasUuid() {
        var sysInfoRequests = 0
        val device = HyperionDiscovery.validate(
            "192.168.1.158",
            requestServerInfo = { response },
            requestSysInfo = { sysInfoRequests++; error("sysinfo must not be requested") },
            verifyDataPort = { true },
        )
        assertNotNull(device)
        assertEquals(0, sysInfoRequests)
    }

    @Test fun inventoryRetainsStaleSelectionAndUpdatesHostForSameIdentity() {
        val old = HyperionDevice("uuid:$uuid", "Old", "192.168.1.158")
        val fresh = old.copy(name = "New")
        val other = HyperionDevice("uuid:123e4567-e89b-12d3-a456-426614174001", "Other", "192.168.1.158")
        val before = AudioSettings.defaults().copy(hyperionDevices = listOf(old), selectedHyperionIdentity = old.identity)
        val stale = before.copy(hyperionDevices = HyperionInventory.merge(before.hyperionDevices, listOf(other)))
        assertEquals(listOf(old, other), stale.hyperionDevices); assertEquals(old.identity, stale.selectedHyperionIdentity)
        assertEquals(listOf(fresh), HyperionInventory.merge(listOf(old), listOf(fresh)))
    }

    @Test fun bindingRejectsZeroOrChangedAndIsOneShot() {
        val selected = HyperionDevice("uuid:$uuid", "TV", "192.168.1.158")
        val settings = AudioSettings.defaults().copy(hyperionDevices = listOf(selected), selectedHyperionIdentity = selected.identity)
        assertNull(HyperionCapturePreflight.bind(settings) { emptyList() })
        assertNull(HyperionCapturePreflight.bind(settings) { listOf(selected.copy(host = "192.168.1.157")) })
        val binding = HyperionCapturePreflight.bind(settings) { listOf(selected) }
        assertNotNull(binding); assertEquals(selected, HyperionRouteBindings.consume(binding, settings)); assertNull(HyperionRouteBindings.consume(binding, settings))
    }

    @Test fun uiOrderAndExclusiveUncheckedRestoreAreStatic() {
        assertEquals(listOf("toggle", "capture-mode", "effects", "outputs", "discovery", "settings"), OutputUiPolicy.sections)
        assertEquals("Увімкнути", OutputUiPolicy.ENABLE); assertEquals("Вимкнути", OutputUiPolicy.DISABLE)
        assertEquals(OutputMode.WLED, OutputUiPolicy.modeAfterToggle(OutputMode.WLED, OutputMode.WLED, false))
        assertEquals(OutputMode.HYPERION, OutputUiPolicy.modeAfterToggle(OutputMode.WLED, OutputMode.HYPERION, true))
    }

    @Test fun synchronizedOutputCheckboxChangesDoNotSelectOutput() {
        assertFalse(OutputUiPolicy.handlesCheckboxChange(true))
        assertTrue(OutputUiPolicy.handlesCheckboxChange(false))
    }
}
