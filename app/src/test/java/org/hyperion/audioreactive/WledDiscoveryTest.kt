package org.hyperion.audioreactive

import org.junit.Assert.*
import org.junit.Test

class WledDiscoveryTest {
 @Test fun privateLiteralIpv4PolicyCoversAllRfc1918AndRejectsHostnames(){
  listOf("192.168.1.2","10.1.2.3","172.20.0.1").forEach { assertTrue(isPrivateLanIpv4(it)); assertTrue(WledDiscovery.isCleartextWledHost(it)) }
  listOf("172.15.0.1","8.8.8.8","127.0.0.1","::1","wled.local","192.168.1.999").forEach { assertFalse(isPrivateLanIpv4(it)); assertFalse(WledDiscovery.isCleartextWledHost(it)) }
 }
 @Test fun scanHostsUseEveryPrivateInterfaceAreBoundedAndEventuallyCoverLargeSubnets(){
  val hosts=WledDiscovery.hostsForNetworks(listOf("192.168.5.7" to 30,"10.3.4.5" to 30,"172.20.0.9" to 30,"8.8.8.8" to 24))
  assertEquals(setOf("192.168.5.5","192.168.5.6","10.3.4.5","10.3.4.6","172.20.0.9","172.20.0.10"),hosts.toSet())
  val networks=listOf("10.0.0.1" to 16)
  val first=WledDiscovery.hostBatch(networks) { 0L }
  val second=WledDiscovery.hostBatch(networks) { key -> first.nextCursors.getValue(key) }
  assertTrue(first.hosts.size<=512); assertEquals("10.0.0.1", first.hosts.first())
  assertTrue(first.hosts.intersect(second.hosts.toSet()).isEmpty())
  assertTrue(first.nextCursors.values.all { it > 0L })
 }
 @Test fun parserRequiresMacIdentityLedCountAndUsesApiPort(){val info="""{"ver":"0.14.4","mac":"AA:BB:CC:DD:EE:FF","name":"TV","leds":{"count":182},"udpport":21324}""";val d=WledDiscovery.parseInfo("10.1.2.3",info)!!;assertEquals("mac:AABBCCDDEEFF",d.identity);assertEquals(182,d.leds);assertEquals(21324,d.realtimePort);assertNull(WledDiscovery.parseInfo("8.8.8.8",info));assertNull(WledDiscovery.parseInfo("wled.local",info))}
 @Test fun persistedSelectionRequiresStableMacIdentity(){val d=WledDevice("mac:AABBCCDDEEFF","Desk","192.168.1.152",12,21324);val settings=AudioSettings(Effect.FIRE,.5f,1f,20,OutputMode.WLED,listOf(d),setOf(d.identity));assertTrue(settings.valid());assertEquals(listOf(d),settings.selectedWledDevices());assertFalse(WledDevice("json:0.14|Desk|12","Desk","192.168.1.152",12,21324).valid());assertFalse(settings.copy(selectedWledIdentities=setOf("unknown")).valid())}
 @Test fun legacyMigrationNeverRetainsEndpointData(){assertEquals(OutputMode.HYPERION,OutputModeMigration.fromLegacyIds("HYPERION"));assertEquals(OutputMode.WLED,OutputModeMigration.fromLegacyIds("WLED_TV"));assertEquals(emptyList<WledDevice>(),AudioSettings.defaults().wledDevices)}
}
