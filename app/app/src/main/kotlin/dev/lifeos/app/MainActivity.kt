package dev.lifeos.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dev.lifeos.app.lock.Authenticator
import dev.lifeos.app.pairing.PairingText
import dev.lifeos.app.ui.components.LocalConnection
import dev.lifeos.app.ui.nav.LifeOsApp
import dev.lifeos.app.ui.screens.LockScreen
import dev.lifeos.app.ui.screens.WelcomeScreen
import dev.lifeos.app.ui.theme.LifeTheme
import kotlinx.coroutines.launch

/**
 * The single activity. It is an AppCompatActivity because the per-app language
 * (AppCompatDelegate.setApplicationLocales) needs AppCompat on Android 12 and older.
 */
class MainActivity : AppCompatActivity() {
    private val core: LifeOsCore get() = (application as LifeOsApplication).core
    private var openPairing by mutableStateOf(false)
    private var lockMessage by mutableStateOf<String?>(null)

    /** Once the welcome screen was answered it does not come back in this run. */
    private var welcomeDone by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val core = core
        val settings = core.settings
        if (savedInstanceState == null) useTestAids(intent)

        setContent {
            // Null until the stored setting is read, so the wrong theme is never drawn first.
            val storedTheme by settings.theme.collectAsStateWithLifecycle(initialValue = null)
            val theme = storedTheme ?: return@setContent
            val welcomed by settings.welcomed.collectAsStateWithLifecycle(initialValue = null)
            val dark = theme.isDark(isSystemInDarkTheme())
            val scope = rememberCoroutineScope()
            val locked by core.locked.collectAsStateWithLifecycle()
            val connection by core.connection.collectAsStateWithLifecycle()
            val pairing by core.pairing.collectAsStateWithLifecycle()

            // System-bar icons follow the app's theme, not the phone's.
            LaunchedEffect(dark) {
                val style = if (dark) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }

            LifeTheme(dark = dark) {
                CompositionLocalProvider(LocalConnection provides connection) {
                    val showWelcome = welcomed == false && pairing == null && !welcomeDone
                    when {
                        welcomed == null -> Unit
                        showWelcome -> WelcomeScreen(
                            onPair = {
                                welcomeDone = true
                                openPairing = true
                                scope.launch { settings.setWelcomed() }
                            },
                            onLater = {
                                welcomeDone = true
                                scope.launch { settings.setWelcomed() }
                            },
                        )
                        else -> Box {
                            // While locked the app keeps its place (open screen, scroll position)
                            // but is invisible, silent for screen readers and out of reach:
                            // the lock screen lies on top and swallows every touch.
                            Box(if (locked) Modifier.alpha(0f).clearAndSetSemantics { } else Modifier) {
                                LifeOsApp(
                                    core = core,
                                    theme = theme,
                                    onThemeChange = { scope.launch { settings.setTheme(it) } },
                                    authenticate = ::authenticate,
                                    openPairing = openPairing,
                                    onPairingOpened = { openPairing = false },
                                )
                            }
                            if (locked) {
                                LockScreen(
                                    message = lockMessage,
                                    onUnlock = ::promptUnlock,
                                    onBack = { moveTaskToBack(true) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        useTestAids(intent)
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch { core.onShown() }
    }

    override fun onStop() {
        super.onStop()
        // A rotation or language switch is not "out of sight".
        if (!isChangingConfigurations) core.onHidden()
    }

    private fun authenticate(title: String, subtitle: String, onSuccess: () -> Unit, onFailure: () -> Unit) {
        Authenticator.prompt(this, title, subtitle, onSuccess, onFailure)
    }

    private fun promptUnlock() {
        authenticate(
            getString(R.string.lock_prompt_title),
            getString(R.string.lock_prompt_subtitle),
            {
                lockMessage = when (core.unlock()) {
                    UnlockResult.UNLOCKED -> null
                    UnlockResult.NEEDS_CONFIRMATION -> getString(R.string.lock_needs_credential)
                    UnlockResult.PAIRING_LOST -> {
                        Toast.makeText(this, R.string.lock_pairing_lost, Toast.LENGTH_LONG).show()
                        null
                    }
                }
            },
            {},
        )
    }

    /** Debug builds only (see `src/debug/.../TestAids.kt`); in release builds this does nothing. */
    private fun useTestAids(intent: Intent?) {
        val realLock = TestAids.realLock(intent)
        val offer = TestAids.pairingText(intent)?.let(PairingText::parse) ?: return
        val label = getString(R.string.pair_label_default)
        welcomeDone = true
        openPairing = true
        if (!realLock) {
            core.startPairing(offer, label, TestAids.VAULT)
            return
        }
        // Only the camera is replaced: confirmation and the protected key are the real ones.
        window.decorView.post {
            authenticate(
                getString(R.string.pair_confirm_title),
                getString(R.string.pair_confirm_subtitle),
                { core.startPairing(offer, label) },
                { offer.secret.fill(0) },
            )
        }
    }
}
