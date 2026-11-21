package dev.lifeos.app.signin

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.core.net.toUri
import dev.lifeos.app.LifeOsApplication
import dev.lifeos.app.MainActivity
import dev.lifeos.app.data.MoodleSignIn

/**
 * Receives the address the site's sign-in ends on (`moodlemobile://token=…`), hands it to the
 * core (which accepts it only for the attempt that is waiting) and brings the app back. It has
 * no screen, keeps nothing, and the address is removed from the intent at once.
 */
class MoodleRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val address = intent?.dataString
        intent?.data = null
        if (MoodleSignIn.isRedirect(address)) (application as LifeOsApplication).core.onMoodleRedirect(checkNotNull(address))
        // Back to the one app window (not a second one on top of the browser).
        startActivity(
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_SIGNED_IN)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }

    companion object {
        const val ACTION_SIGNED_IN = "dev.lifeos.app.action.MOODLE_SIGNED_IN"
    }
}

/** The site's sign-in page in a browser tab (never inside the app, never a WebView). */
object SignInBrowser {
    /**
     * Opens [link] as a Custom Tab where the browser offers one (the documented session extra,
     * without an extra library), otherwise as a normal browser page. False if no browser exists.
     */
    fun open(context: Context, link: String): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, link.toUri())
        intent.putExtras(Bundle().apply { putBinder("android.support.customtabs.extra.SESSION", null) })
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        }
    }
}
