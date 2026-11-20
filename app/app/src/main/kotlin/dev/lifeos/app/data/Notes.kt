package dev.lifeos.app.data

import java.util.Locale

/** A note of the list as the screen shows it: with the text of an edit that is still on its way. */
data class NoteRow(val note: NoteSummary, val text: String, val pending: OutboxItem?)

/** What the Notes screen shows while writes are still on their way. */
object NoteLists {
    /** Notes captured on this phone that the computer does not have yet, newest first. */
    fun waiting(outbox: List<OutboxItem>): List<OutboxItem> =
        outbox.filter { it.kind == OutboxItem.Kind.NOTE && it.state == OutboxItem.State.WAITING }.reversed()

    /** The latest edit of a note that has not been confirmed yet (a refused one does not count). */
    fun pendingEdit(noteId: String, outbox: List<OutboxItem>): OutboxItem? =
        outbox.lastOrNull { it.kind == OutboxItem.Kind.NOTE_EDIT && it.noteId == noteId && it.state != OutboxItem.State.FAILED }

    fun rows(notes: List<NoteSummary>, outbox: List<OutboxItem>): List<NoteRow> = notes.map { note ->
        val pending = pendingEdit(note.id, outbox)
        NoteRow(note, pending?.text ?: note.preview, pending)
    }

    /**
     * Whether a capture can be edited now: a topic note never (it is edited on the computer),
     * a capture unless an earlier edit already left the phone and is not confirmed (the
     * computer gives the note a new id with every edit, so a second one would miss it).
     */
    fun canEdit(note: NoteSummary, outbox: List<OutboxItem>): Boolean {
        if (!note.editable) return false
        val pending = pendingEdit(note.id, outbox) ?: return true
        return pending.state == OutboxItem.State.WAITING && !pending.sending
    }

    /** The computer's search rule, for the saved copy while the computer cannot be asked. */
    fun matches(note: NoteSummary, query: String): Boolean {
        val needle = query.trim().lowercase(Locale.ROOT)
        if (needle.isEmpty()) return true
        return "${note.title.orEmpty()}\n${note.preview}".lowercase(Locale.ROOT).contains(needle)
    }

    /** A row's two lines: the title (a topic note's own, or a capture's first line) and the rest. */
    fun headline(title: String?, text: String): Pair<String, String?> {
        val lines = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (!title.isNullOrBlank()) return title to lines.joinToString(" ").ifEmpty { null }
        return (lines.firstOrNull() ?: "") to lines.drop(1).joinToString(" ").ifEmpty { null }
    }
}
