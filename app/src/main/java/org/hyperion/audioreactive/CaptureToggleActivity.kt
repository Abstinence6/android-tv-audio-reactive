package org.hyperion.audioreactive

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Leanback/IR shortcut only. It does not own capture, tokens, sockets, or permission UI.
 * Stopping is limited to an existing app-owned service; starting returns to the visible local UI.
 */
class CaptureToggleActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        if (AudioReactiveService.exists()) {
            AudioReactiveService.stopExisting(this)
        } else {
            startActivity(Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_IR_TOGGLE, true))
        }
        finish()
    }
}
