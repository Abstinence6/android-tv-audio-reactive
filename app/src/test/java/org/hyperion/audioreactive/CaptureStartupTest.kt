package org.hyperion.audioreactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureStartupTest {
    @Test fun onlyFirstServiceStartIdIsAdmittedForCurrentToken() {
        val state = CaptureStartupState()
        val token = state.begin()

        assertEquals(CaptureStartAdmission.ADMITTED, state.admitStart(token, startId = 41))
        assertEquals(CaptureStartAdmission.REJECTED, state.admitStart(token, startId = 42))
        assertEquals(CaptureStatus.PREPARING_AUDIO_RECORD, state.preparingAudioRecord(token))
        assertEquals(CaptureStartAdmission.REJECTED, state.admitStart(token, startId = 43))
        assertTrue(state.isCurrent(token))
        assertEquals(CaptureStatus.PREPARING_AUDIO_RECORD, state.status)
    }

    @Test fun timeoutAfterAudioStartCannotOverwriteActiveOrOnState() {
        val state = CaptureStartupState()
        val token = state.begin()
        assertEquals(CaptureStartAdmission.ADMITTED, state.admitStart(token, 1))
        assertEquals(CaptureStatus.PREPARING_AUDIO_RECORD, state.preparingAudioRecord(token))
        assertTrue(state.audioRecordInitialized(token))
        assertTrue(state.audioRecordStarted(token))
        assertEquals(CaptureStatus.CAPTURE_ACTIVE, state.activate(token))
        assertNull(state.timedOut(token))
        assertEquals(CaptureStatus.CAPTURE_ACTIVE, state.status)
        assertTrue(state.isActive(token))
    }

    @Test fun timeoutTerminallyBlocksDelayedCaptureCallbacksAndRouterCreation() {
        val state = CaptureStartupState()
        val token = state.begin()
        assertEquals(CaptureStartAdmission.ADMITTED, state.admitStart(token, 1))
        state.preparingAudioRecord(token)
        assertEquals(CaptureStatus.AUDIO_RECORD_INIT_TIMEOUT, state.timedOut(token))
        assertFalse(state.audioRecordInitialized(token))
        assertFalse(state.audioRecordStarted(token))
        assertFalse(state.canCreateRouter(token))
        assertNull(state.activate(token))
        assertEquals(CaptureStatus.AUDIO_RECORD_INIT_TIMEOUT, state.status)
    }

    @Test fun oldRunTokenCannotChangeLaterRun() {
        val state = CaptureStartupState()
        val old = state.begin(10)
        assertEquals(CaptureStartAdmission.ADMITTED, state.admitStart(old, 1))
        state.preparingAudioRecord(old)
        val current = state.begin(11)
        assertNull(state.timedOut(old))
        assertNull(state.stop(old, CaptureStatus.AUDIO_RECORD_INIT_FAILED))
        assertEquals(CaptureStartAdmission.ADMITTED, state.admitStart(current, 2))
        assertEquals(CaptureStatus.PREPARING_AUDIO_RECORD, state.preparingAudioRecord(current))
        assertTrue(state.audioRecordInitialized(current))
        assertTrue(state.audioRecordStarted(current))
        assertEquals(CaptureStatus.CAPTURE_ACTIVE, state.activate(current))
        assertEquals(CaptureStatus.CAPTURE_ACTIVE, state.status)
    }

    @Test fun firstTerminalResultWinsDeterministically() {
        val state = CaptureStartupState()
        val token = state.begin()
        assertEquals(CaptureStatus.AUDIO_RECORD_INIT_FAILED, state.stop(token, CaptureStatus.AUDIO_RECORD_INIT_FAILED))
        assertNull(state.stop(token, CaptureStatus.ROUTER_INIT_FAILED))
        assertNull(state.timedOut(token))
        assertEquals(CaptureStatus.AUDIO_RECORD_INIT_FAILED, state.status)
    }

    @Test fun timeoutPolicyIsBounded() {
        assertFalse(CaptureStartupTimeoutPolicy.expired(CaptureStartupTimeoutPolicy.TIMEOUT_MILLIS - 1))
        assertTrue(CaptureStartupTimeoutPolicy.expired(CaptureStartupTimeoutPolicy.TIMEOUT_MILLIS))
    }
}
