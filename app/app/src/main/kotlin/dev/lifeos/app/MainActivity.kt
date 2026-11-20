package dev.lifeos.app

import android.graphics.Color
import android.os.Bundle
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lifeos.app.settings.SettingsStore
import dev.lifeos.app.ui.nav.LifeOsApp
import dev.lifeos.app.ui.theme.LifeTheme
import kotlinx.coroutines.launch

/**
 * The single activity. It is an AppCompatActivity because the per-app language
 * (AppCompatDelegate.setApplicationLocales) needs AppCompat on Android 12 and older.
 */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val settings = SettingsStore(applicationContext)

        setContent {
            // Null until the stored setting is read, so the wrong theme is never drawn first.
            val storedTheme by settings.theme.collectAsStateWithLifecycle(initialValue = null)
            val theme = storedTheme ?: return@setContent
            val dark = theme.isDark(isSystemInDarkTheme())
            val scope = rememberCoroutineScope()

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
                LifeOsApp(
                    theme = theme,
                    onThemeChange = { scope.launch { settings.setTheme(it) } },
                )
            }
        }
    }
}
