package dev.lifeos.app.settings

/**
 * What the Today tab shows (#192): the field of physics tiles (the owner's choice, the default)
 * or the classic list of cards.
 */
enum class HomeStyle(val storedValue: String) {
    TILES("tiles"),
    CLASSIC("classic");

    companion object {
        val Default = TILES

        /** Reads a stored value (also the command bar's choice); anything missing or unknown is the default. */
        fun fromStored(value: String?): HomeStyle =
            entries.firstOrNull { it.storedValue == value } ?: Default
    }
}
