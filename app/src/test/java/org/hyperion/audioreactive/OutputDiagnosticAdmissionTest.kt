package org.hyperion.audioreactive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputDiagnosticAdmissionTest {
    @Test fun diagnosticAndCaptureReservationsAreMutuallyExclusive() {
        OutputDiagnosticAdmission.releaseCapture(); OutputDiagnosticAdmission.releaseDiagnostic()
        assertTrue(OutputDiagnosticAdmission.reserveDiagnostic())
        assertFalse(OutputDiagnosticAdmission.reserveCapture())
        OutputDiagnosticAdmission.releaseDiagnostic()
        assertTrue(OutputDiagnosticAdmission.reserveCapture())
        assertFalse(OutputDiagnosticAdmission.reserveDiagnostic())
        OutputDiagnosticAdmission.releaseCapture()
    }
}