package org.hyperion.audioreactive

/** Pure policy for the user-initiated audio-effects toggle. */
internal object CaptureTogglePolicy {
    enum class Action { STOP_EXISTING, REQUEST_RECORD_AUDIO, REQUEST_MEDIA_PROJECTION, START_CAPTURE, FINISH_WITHOUT_CAPTURE }

    fun actionFor(serviceExists: Boolean, recordAudioGranted: Boolean): Action = when {
        serviceExists -> Action.STOP_EXISTING
        recordAudioGranted -> Action.REQUEST_MEDIA_PROJECTION
        else -> Action.REQUEST_RECORD_AUDIO
    }

    fun afterRecordAudioPermission(granted: Boolean): Action =
        if (granted) Action.REQUEST_MEDIA_PROJECTION else Action.FINISH_WITHOUT_CAPTURE

    fun afterMediaProjectionConsent(resultOk: Boolean, hasData: Boolean): Action =
        if (resultOk && hasData) Action.START_CAPTURE else Action.FINISH_WITHOUT_CAPTURE
}
