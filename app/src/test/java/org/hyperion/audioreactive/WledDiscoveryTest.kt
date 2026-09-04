package org.hyperion.audioreactive

import org.junit.Assert.*
import org.junit.Test

class WledDiscoveryTest {
 @Test fun privateIpv4AndExplicitCleartextPolicyAreSeparate(){assertTrue(isPrivateLanIpv4("192.168.1.2"));assertTrue(isPrivateLanIpv4("10.1.2.3"));assertTrue(isPrivateLanIpv4("172.20.0.1"));assertFalse(isPrivateLanIpv4("172.15.0.1"));assertFalse(isPrivateLanIpv4("8.8.8.8"));assertFalse(isPrivateLanIpv4("127.0.0.1"));assertFalse(isPrivateLanIpv4("::1"));assertTrue(WledDiscovery.isCleartextWledHost("192.168.1.152"));assertTrue(WledDiscovery.isCleartextWledHost("192.168.1.153"));assertFalse(WledDiscovery.isCleartextWledHost("192.168.1.154"));assertFalse(WledDiscovery.isCleartextWledHost("192.168.2.2"));assertFalse(WledDiscovery.isCleartextWledHost("10.1.2.3"))}
 @Test fun parserRequiresMacIdentityLedCountAndUsesApiPort(){val info="""{"ver":"0.14.4","mac":"AA:BB:CC:DD:EE:FF","name":"TV","leds":{"count":182},"udpport":21324}""";val d=WledDiscovery.parseInfo("192.168.1.152",info)!!;assertEquals("mac:AABBCCDDEEFF",d.identity);assertEquals(182,d.leds);assertEquals(21324,d.realtimePort);assertNull(WledDiscovery.parseInfo("8.8.8.8",info));assertNull(WledDiscovery.parseInfo("192.168.1.152","{\"ver\":\"x\",\"name\":\"Desk\",\"leds\":{\"count\":2}}"))}
 @Test fun persistedSelectionRequiresStableMacIdentity(){val d=WledDevice("mac:AABBCCDDEEFF","Desk","192.168.1.152",12,21324);val settings=AudioSettings(Effect.FIRE,.5f,1f,20,OutputMode.WLED,listOf(d),setOf(d.identity));assertTrue(settings.valid());assertEquals(listOf(d),settings.selectedWledDevices());assertFalse(WledDevice("json:0.14|Desk|12","Desk","192.168.1.152",12,21324).valid());assertFalse(settings.copy(selectedWledIdentities=setOf("unknown")).valid())}
 @Test fun legacyMigrationNeverRetainsEndpointData(){assertEquals(OutputMode.HYPERION,OutputModeMigration.fromLegacyIds("HYPERION"));assertEquals(OutputMode.WLED,OutputModeMigration.fromLegacyIds("WLED_TV"));assertEquals(OutputMode.WLED,OutputModeMigration.fromLegacyIds("HYPERION,WLED_CEILING"));assertEquals(emptyList<WledDevice>(),AudioSettings.defaults().wledDevices)}
}
