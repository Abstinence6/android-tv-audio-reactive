package org.hyperion.audioreactive

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager

/** Finds a user-connected microphone; built-in TV microphones are intentionally not offered. */
object VoiceInputDevices {
    fun connected(context: Context): AudioDeviceInfo? =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { device ->
                device.isSource && device.type in setOf(
                    AudioDeviceInfo.TYPE_USB_DEVICE,
                    AudioDeviceInfo.TYPE_USB_HEADSET,
                    AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                )
            }
}
