package org.hyperion.audioreactive

/**
 * Process-wide mutual exclusion between one-shot diagnostic output and capture-route admission.
 * A diagnostic holds the lease through preflight, frames, and cleanup; capture holds its lease
 * from service admission until router teardown. This prevents two owners from using a route.
 */
internal object OutputDiagnosticAdmission {
    private var capture = false
    private var diagnostic = false

    @Synchronized fun reserveCapture(): Boolean {
        if (capture || diagnostic) return false
        capture = true
        return true
    }

    @Synchronized fun releaseCapture() { capture = false }

    @Synchronized fun reserveDiagnostic(): Boolean {
        if (capture || diagnostic) return false
        diagnostic = true
        return true
    }

    @Synchronized fun releaseDiagnostic() { diagnostic = false }

    @Synchronized fun diagnosticAllowed() = !capture && !diagnostic
}
