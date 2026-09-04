package org.hyperion.audioreactive

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

class OutputRoutingTest {
 @Test fun exclusiveModesHaveOnlyTwoValues(){assertEquals(listOf("Hyperion","WLED"),OutputMode.entries.map{it.label});assertEquals(OutputMode.HYPERION,AudioSettings.defaults().outputMode)}
 @Test fun wledPacketMappingUsesEveryLedAndBlackout(){val src=ByteArray(48){it.toByte()};val out=ByteArray(2+182*3);resample16To(src,out,2,182);assertEquals(src[0],out[2]);assertEquals(src[45],out[out.size-3]);assertEquals(548,out.size)}
 @Test fun wledDatagramsAreStableAndRealtimeDataMutatesInPlace(){val packets=WledRealtimePackets(InetAddress.getLoopbackAddress(),21324,2);val realtime=packets.realtime;val first=ByteArray(48){it.toByte()};packets.updateRealtime(first);assertSame(realtime,packets.realtime);assertSame(realtime.data,packets.realtime.data);assertEquals(first[0],realtime.data[2]);packets.updateRealtime(ByteArray(48){(100+it).toByte()});assertEquals(100.toByte(),realtime.data[2]);assertTrue(packets.blackout.data.drop(2).all{it==0.toByte()})}
 @Test fun wledSourceZonesAreBoundedReusableAndReachPhysicalLeds(){listOf(16,128,512).forEach { zones -> val source=WledSourceFrame(SourceFrameSpec(zones,1,20),zones);val input=ByteArray(zones*3){it.toByte()};val first=source.write(input);val second=source.write(input);assertSame(first,second);assertEquals(zones*3,first.size);val packets=WledRealtimePackets(InetAddress.getLoopbackAddress(),21324,3,zones);packets.updateRealtime(first);assertEquals(first[0],packets.realtime.data[2]);assertEquals(first[((2*zones)/3)*3],packets.realtime.data[8]) }}
 @Test fun wledVideoAndAudioVideoReduceSpatialColorsBeforePhysicalResample(){val settings=AudioSettings.defaults().copy(outputMode=OutputMode.WLED,wledSourceZones=16,renderMode=RenderMode.VIDEO_AUDIO);assertEquals(SourceFrameSpec(128,72,settings.fps),settings.captureFrame());val video=ByteArray(16*2*3);for(i in 0 until 16*2){val at=i*3;if(i%16<8)video[at]=255.toByte()else video[at+2]=255.toByte()};val source=WledSourceFrame(SourceFrameSpec(16,2,settings.fps),16);val zones=source.write(video);assertEquals(255, zones[0].toInt()and 255);assertEquals(0,zones[2].toInt()and 255);assertEquals(0,zones[24].toInt()and 255);assertEquals(255,zones[26].toInt()and 255);val packets=WledRealtimePackets(InetAddress.getLoopbackAddress(),21324,4,16);packets.updateRealtime(zones);assertEquals(255,packets.realtime.data[2].toInt()and 255);assertEquals(0,packets.realtime.data[11].toInt()and 255);assertEquals(255,packets.realtime.data[13].toInt()and 255)}
 @Test fun calibratedMaximumVideoAndAudioVideoFramesAreReducedAndRouteToCorrectPhysicalPackets(){
  listOf(RenderMode.VIDEO,RenderMode.VIDEO_AUDIO).forEach { renderMode ->
   DatagramSocket(0,InetAddress.getLoopbackAddress()).use { receiver ->
    receiver.soTimeout=2_000
    val device=WledDevice("mac:001122334455","TV","127.0.0.1",4,receiver.localPort)
    val calibration=WledScreenCalibration(device.identity,4,0,PerimeterDirection.CW,4,0,0,0,depthPercent=25,samplesPerEdge=16,gamma=1f,brightnessLimit=1f)
    val settings=AudioSettings.defaults().copy(outputMode=OutputMode.WLED,renderMode=renderMode,videoQuality=VideoQuality.MAXIMUM,wledSourceZones=16)
    assertEquals(SourceFrameSpec(320,180,settings.fps),settings.captureFrame())
    val frame=ByteArray(settings.captureFrame().bytes)
    for(y in 0 until 180) for(x in 0 until 320){ val at=(y*320+x)*3; val color=when { x<80 -> intArrayOf(255,0,0); x<160 -> intArrayOf(0,255,0); x<240 -> intArrayOf(0,0,255); else -> intArrayOf(255,255,255) }; frame[at]=color[0].toByte();frame[at+1]=color[1].toByte();frame[at+2]=color[2].toByte() }
    val output=WledRealtimeOutput(device,16,calibration,settings.captureFrame())
    val router=OutputRouter.forTest(OutputMode.WLED,wled=arrayOf(output),wledSource=WledSourceFrame(settings.captureFrame(),16)); router.start(); router.send(frame)
    val packet=DatagramPacket(ByteArray(14),14); receiver.receive(packet); router.stop()
    assertEquals(14,packet.length); assertArrayEquals(byteArrayOf(2,2,255.toByte(),0,0,0,255.toByte(),0,0,0,255.toByte(),255.toByte(),255.toByte(),255.toByte()),packet.data)
   }
  }
 }
 @Test fun routerDoesNotSendBeforeStartAndCleansWledOnly(){val h=FakeHyperion();val a=FakeWled();val b=FakeWled();val r=OutputRouter.forTest(OutputMode.WLED,h,arrayOf(a,b));r.send(ByteArray(48));assertEquals(0,a.frames);r.start();r.send(ByteArray(48));r.stop();assertEquals(0,h.registers);assertEquals(1,a.frames);assertEquals(1,b.frames);assertEquals(1,a.blacks);assertEquals(1,b.closes)}
 @Test fun protectedOrBlackVideoImmediatelyBlackoutsAndStopsItsWledRoute(){val output=FakeWled();val router=OutputRouter.forTest(OutputMode.WLED,wled=arrayOf(output));router.start();router.send(ByteArray(48));VideoCaptureFailurePolicy.blackoutAndTerminate{router.stop()};assertEquals(1,output.frames);assertEquals(1,output.blacks);assertEquals(1,output.closes);router.send(ByteArray(48));assertEquals(1,output.frames)}
 @Test fun hyperionRouterRegistersAndClears(){val h=FakeHyperion();val r=OutputRouter.forTest(OutputMode.HYPERION,h);r.start();r.send(ByteArray(48));r.stop();assertEquals(1,h.registers);assertEquals(1,h.frames);assertEquals(1,h.clears)}
 @Test fun failedHyperionRegistrationDoesNotClearPriority(){val h=object:HyperionOutput{var clears=0;var closes=0;override fun register(){throw IllegalStateException("register failed")};override fun send(frame:ByteArray)=Unit;override fun clear(){clears++};override fun close(){closes++}};try{OutputRouter.forTest(OutputMode.HYPERION,h).start();fail("expected registration failure")}catch(_:IllegalStateException){};assertEquals(0,h.clears);assertEquals(1,h.closes)}
 private class FakeHyperion:HyperionOutput{var registers=0;var frames=0;var clears=0;override fun register(){registers++};override fun send(frame:ByteArray){frames++};override fun clear(){clears++};override fun close()=Unit}
 private class FakeWled:WledOutput{var frames=0;var blacks=0;var closes=0;override fun send(frame16:ByteArray){frames++};override fun blackout(){blacks++};override fun close(){closes++}}
}
