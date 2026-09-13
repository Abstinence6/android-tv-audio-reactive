package org.hyperion.audioreactive

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Finds an available voice-input microphone, including the TV's built-in microphone. */
object VoiceInputDevices {
    internal fun supportedType(type: Int): Boolean = type in setOf(
        AudioDeviceInfo.TYPE_BUILTIN_MIC,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    )

    fun connected(context: Context): AudioDeviceInfo? =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { device ->
                device.isSource && supportedType(device.type)
            }
}
