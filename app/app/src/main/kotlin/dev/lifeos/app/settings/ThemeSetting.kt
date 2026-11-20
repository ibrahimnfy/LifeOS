package dev.lifeos.app.settings

/** The appearance chosen in settings. Light is the default (app-design.md, section 5). */
enum class ThemeSetting(val storedValue: String) {
    LIGHT("light"),
    DARK("dark"),
    SYSTEM("system");

    /** Whether the dark colours are used, given the phone's own dark-mode state. */
    fun isDark(systemIsDark: Boolean): Boolean = when (this) {
        LIGHT -> false
        DARK -> true
        SYSTEM -> systemIsDark
    }

    companion object {
        val Default = LIGHT

        /** Reads a stored value; anything missing or unknown falls back to the default. */
        fun fromStored(value: String?): ThemeSetting =
            entries.firstOrNull { it.storedValue == value } ?: Default
    }
}
