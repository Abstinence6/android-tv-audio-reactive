package org.hyperion.audioreactive

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * External remote-binding adapters. They select a fixed command and return control to MainActivity,
 * where MainActivityActionPolicy remains the only command policy and consent-flow owner.
 */
abstract class RemoteCommandActivity : Activity() {
    protected abstract val commandAction: String

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(commandAction)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}

/** Separately bindable external ON surface; caller input cannot alter the forwarded action. */
class RemoteOnActivity : RemoteCommandActivity() {
    override val commandAction = MainActivity.ACTION_ON
}

/** Separately bindable external OFF surface; caller input cannot alter the forwarded action. */
class RemoteOffActivity : RemoteCommandActivity() {
    override val commandAction = MainActivity.ACTION_OFF
}

/** Separately bindable external TOGGLE surface; caller input cannot alter the forwarded action. */
class RemoteToggleActivity : RemoteCommandActivity() {
    override val commandAction = MainActivity.ACTION_TOGGLE
}
