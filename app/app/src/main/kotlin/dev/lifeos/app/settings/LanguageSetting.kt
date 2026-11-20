package dev.lifeos.app.settings

/**
 * The app language chosen in settings. [languageTag] is null for "follow phone".
 * The platform's per-app locale is the single source of truth; this type only
 * maps between it and the three choices on the settings screen.
 */
enum class LanguageSetting(val languageTag: String?) {
    SYSTEM(null),
    ENGLISH("en"),
    TURKISH("tr");

    /** The value handed to the per-app locale API: empty means "follow phone". */
    fun toLanguageTags(): String = languageTag.orEmpty()

    companion object {
        /**
         * Maps the per-app locale list (comma-separated BCP 47 tags, e.g. "tr-TR")
         * to a setting. An empty list or an unsupported language is "follow phone".
         */
        fun fromLanguageTags(tags: String?): LanguageSetting {
            val language = tags.orEmpty()
                .substringBefore(',')
                .trim()
                .substringBefore('-')
                .substringBefore('_')
                .lowercase()
            return entries.firstOrNull { it.languageTag == language } ?: SYSTEM
        }
    }
}
