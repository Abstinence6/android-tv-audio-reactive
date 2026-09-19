package org.hyperion.audioreactive

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioReactiveServiceAudioDrainSourceTest {
    private val source by lazy {
        sequenceOf(
            File("src/main/java/org/hyperion/audioreactive/AudioReactiveService.kt"),
            File("app/src/main/java/org/hyperion/audioreactive/AudioReactiveService.kt"),
        ).first(File::isFile).readText()
    }

    @Test fun videoLoopDrainsToTheLatestCompletePcmBlockBeforeAnalysis() {
        assertTrue(source.contains("AudioRecordDrainPolicy.drainLatestFullBlock(samples,latestSamples)"))
        assertTrue(source.contains("analyzer.analyzeStereo(latestSamples,latestSamples.size,s.sensitivity,s.effectParameters.beatThreshold,started)"))
        assertTrue(source.contains("AudioRecord.READ_NON_BLOCKING"))
        assertFalse(source.contains("analyzer.analyze(samples,n,s.sensitivity)"))
    }

    @Test fun connectedMicrophoneUsesVoiceRecognitionAndAnExplicitPreferredDevice() {
        assertTrue(source.contains("s.audioInput==AudioInput.MICROPHONE"))
        assertTrue(source.contains("VoiceInputDevices.connected(this)?:error(\"microphone disconnected\")"))
        assertTrue(source.contains("setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)"))
        assertTrue(source.contains("record.setPreferredDevice(microphone)"))
        assertTrue(source.contains("record.addOnRoutingChangedListener(voiceInputRouteListener,null)"))
        assertTrue(source.contains("registerAudioDeviceCallback(voiceInputDeviceCallback,null)"))
        assertTrue(source.contains("unregisterAudioDeviceCallback(voiceInputDeviceCallback)"))
        assertTrue(source.contains("removeOnRoutingChangedListener(voiceInputRouteListener)"))
        assertTrue(source.contains("private fun terminateVoiceInputLost(){ terminalStop(TerminalCause.MICROPHONE_LOSS) { status=CaptureStatus.MICROPHONE_ROUTE_LOST"))
    }
}
