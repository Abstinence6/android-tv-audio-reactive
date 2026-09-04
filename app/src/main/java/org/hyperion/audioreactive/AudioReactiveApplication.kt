package org.hyperion.audioreactive

import android.app.Application

class AudioReactiveApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        RuntimeSettings.initialize(SharedPreferencesAudioSettingsStore(this))
        // Application-process launch only: the service is non-sticky and no boot receiver exists.
        MqttControlService.start(this)
    }
}
