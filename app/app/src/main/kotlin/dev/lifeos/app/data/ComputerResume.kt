package dev.lifeos.app.data

/**
 * "Continue on the computer" (#201). A conversation started from the phone is a session of
 * its folder on the computer, but Claude Code's own list (`claude --resume` without an id)
 * leaves out sessions that were started through the Agent SDK. By id it resumes like any
 * other, so the app shows the exact line to run in a terminal.
 */
object ComputerResume {
    /**
     * `cd <folder> && claude --resume <id>` for a POSIX shell, or null when the session has
     * no usable id or folder. The folder is quoted whenever it holds anything a shell reads.
     */
    fun command(folder: String, sessionId: String): String? {
        if (!SESSION_ID.matches(sessionId) || !folder.startsWith("/") || folder.any { it == '\n' || it == '\r' || it == '\u0000' }) return null
        return "cd ${quoted(folder)} && claude --resume $sessionId"
    }

    /** [text] as one shell word: as it is when it is plain, else in single quotes. */
    internal fun quoted(text: String): String =
        if (PLAIN.matches(text)) text else "'" + text.replace("'", "'\\''") + "'"

    private val SESSION_ID = Regex("^[0-9a-fA-F]{8}(-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}$")
    private val PLAIN = Regex("^[A-Za-z0-9_@%+=:,./-]+$")
}
