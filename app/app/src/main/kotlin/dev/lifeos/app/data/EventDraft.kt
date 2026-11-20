package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** What the owner typed for an event. Dates are `YYYY-MM-DD`, times `HH:MM`; no start means all day. */
@Serializable
data class EventDraft(
    val title: String,
    val date: String,
    @SerialName("end_date") val endDate: String? = null,
    val start: String? = null,
    val end: String? = null,
    val note: String? = null,
) {
    /** `POST /events`: optional fields are simply left out. */
    fun createBody(): JsonObject = buildJsonObject {
        put("title", JsonPrimitive(title))
        put("date", JsonPrimitive(date))
        endDate?.let { put("end_date", JsonPrimitive(it)) }
        start?.let { put("start", JsonPrimitive(it)) }
        if (start != null) end?.let { put("end", JsonPrimitive(it)) }
        note?.takeIf { it.isNotBlank() }?.let { put("note", JsonPrimitive(it)) }
    }

    /** `PATCH /events/{id}`: every field is sent; an explicit `null` clears an optional one. */
    fun updateBody(): JsonObject = buildJsonObject {
        put("title", JsonPrimitive(title))
        put("date", JsonPrimitive(date))
        put("end_date", endDate?.let(::JsonPrimitive) ?: JsonNull)
        put("start", start?.let(::JsonPrimitive) ?: JsonNull)
        put("end", (if (start != null) end else null)?.let(::JsonPrimitive) ?: JsonNull)
        put("note", note?.takeIf { it.isNotBlank() }?.let(::JsonPrimitive) ?: JsonNull)
    }

    companion object {
        fun of(event: Event) = EventDraft(event.title, event.date, event.endDate, event.start, event.end, event.note)
    }
}
