package org.hyperion.audioreactive

import android.media.AudioDeviceInfo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceInputPolicyTest {
    @Test fun microphoneChoiceIsVisibleOnlyForAudioReactiveModesAndAConnectedDevice() {
        assertFalse(VoiceInputPolicy.shown(RenderMode.AUDIO, false))
        assertFalse(VoiceInputPolicy.shown(RenderMode.VIDEO, true))
        assertFalse(VoiceInputPolicy.shown(RenderMode.ANIMATION, true))
        assertTrue(VoiceInputPolicy.shown(RenderMode.AUDIO, true))
        assertTrue(VoiceInputPolicy.shown(RenderMode.VIDEO_AUDIO, true))
    }

    @Test fun disconnectedMicrophoneCannotBeUsedButPlaybackAlwaysRemainsValid() {
        assertFalse(VoiceInputPolicy.usable(AudioInput.MICROPHONE, false))
        assertTrue(VoiceInputPolicy.usable(AudioInput.MICROPHONE, true))
        assertTrue(VoiceInputPolicy.usable(AudioInput.PLAYBACK, false))
    }

    @Test fun builtInAndExternalVoiceInputMicrophonesAreSupported() {
        assertTrue(VoiceInputDevices.supportedType(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertTrue(VoiceInputDevices.supportedType(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertFalse(VoiceInputDevices.supportedType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
    }
}
