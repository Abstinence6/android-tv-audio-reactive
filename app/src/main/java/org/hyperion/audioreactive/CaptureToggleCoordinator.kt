package org.hyperion.audioreactive

import android.app.Activity
import android.content.Intent

/**
 * Shared Android adapter for the only permitted capture-start flow.
 * It never requests MediaProjection until the host has completed output preflight.
 */
internal class CaptureToggleCoordinator(private val host: Host) {
    interface Host {
        fun serviceExists(): Boolean
        fun hasRecordAudioPermission(): Boolean
        fun stopExistingService()
        fun requestRecordAudioPermission(generation: Long)
        fun requestMediaProjectionConsent(generation: Long)
        fun prepareOutputForCapture(generation: Long, onReady: () -> Unit, onDenied: () -> Unit)
        fun startCapture(generation: Long, resultCode: Int, data: Intent)
        fun onStoppedExistingService()
        fun onCaptureStartDenied()
        fun onCaptureStartApproved()
    }

    private var generation = 0L
    private var pendingGeneration: Long? = null

    /** A second press is ignored while the first admission owns preflight/consent. */
    fun toggle() {
        if (host.serviceExists()) dispatch(CaptureTogglePolicy.Action.STOP_EXISTING, null)
        else if (pendingGeneration == null) {
            val request = ++generation
            pendingGeneration = request
            host.prepareOutputForCapture(request,
                onReady = { if (current(request)) dispatch(CaptureTogglePolicy.actionFor(false, host.hasRecordAudioPermission()), request) },
                onDenied = { if (current(request)) deny() },
            )
        }
    }

    fun invalidatePending() { generation++; pendingGeneration = null }
    fun onRecordAudioPermissionResult(generation: Long, granted: Boolean) {
        if (current(generation)) dispatch(CaptureTogglePolicy.afterRecordAudioPermission(granted), generation)
    }
    fun onMediaProjectionConsentResult(generation: Long, resultCode: Int, data: Intent?) {
        if (!current(generation)) return
        when (CaptureTogglePolicy.afterMediaProjectionConsent(resultCode == Activity.RESULT_OK, data != null)) {
            CaptureTogglePolicy.Action.START_CAPTURE -> {
                host.startCapture(generation, resultCode, requireNotNull(data))
                host.onCaptureStartApproved()
            }
            CaptureTogglePolicy.Action.FINISH_WITHOUT_CAPTURE -> deny()
            else -> error("Unexpected MediaProjection policy action")
        }
    }

    /** The admission remains exclusive until the service has accepted its one-shot route handoff. */
    fun onCaptureServiceOwnershipConfirmed(generation: Long) {
        if (current(generation)) pendingGeneration = null
    }

    private fun current(request: Long) = pendingGeneration == request
    private fun deny() { pendingGeneration = null; host.onCaptureStartDenied() }
    private fun dispatch(action: CaptureTogglePolicy.Action, request: Long?) = when (action) {
        CaptureTogglePolicy.Action.STOP_EXISTING -> { host.stopExistingService(); host.onStoppedExistingService() }
        CaptureTogglePolicy.Action.REQUEST_RECORD_AUDIO -> host.requestRecordAudioPermission(requireNotNull(request))
        CaptureTogglePolicy.Action.REQUEST_MEDIA_PROJECTION -> host.requestMediaProjectionConsent(requireNotNull(request))
        CaptureTogglePolicy.Action.FINISH_WITHOUT_CAPTURE -> deny()
        CaptureTogglePolicy.Action.START_CAPTURE -> error("A MediaProjection result is required before capture can start")
    }
}
