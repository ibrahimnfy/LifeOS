package dev.lifeos.app

import android.content.Context
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant

/**
 * DEBUG builds only, part of the `sample_data` test aid (see [TestAids]): Chat and Sessions
 * without a computer. Folders, sessions and a conversation come from
 * `src/debug/assets/sample-chat.json`; a sent message is answered piece by piece with the
 * invented answer there. Nothing is sent anywhere.
 *
 * A message that contains one of these words shows another state instead:
 * "in use" (the session is open on the computer), "fail" (the run breaks off after a few
 * words), "offline" (nothing leaves the phone), "silent" (the answer stops arriving).
 */
internal class SampleChat(private val context: Context) {
    private val data: JsonObject by lazy {
        Json.parseToJsonElement(context.assets.open("sample-chat.json").use { it.readBytes().decodeToString() }).jsonObject
    }

    /** The answer to a plain request, or null when it is not one of Chat's. */
    fun request(method: String, path: String): RelayResponse? {
        val key = "$method " + path.replace(Regex("^/sessions/[^/]+/"), "/sessions/ID/")
        return data[key]?.let { RelayResponse(200, relative(it)) }
    }

    fun events(path: String, body: JsonElement?): Flow<AnswerEvent> = flow {
        val prompt = ((body as? JsonObject)?.get("prompt") as? JsonPrimitive)?.content.orEmpty().lowercase()
        val session = Regex("^/sessions/([^/]+)/").find(path)?.groupValues?.get(1) ?: "sample-session-new"
        if ("offline" in prompt) throw ApiException(ApiException.Kind.NOT_SENT)
        if ("in use" in prompt) {
            emit(AnswerEvent.Done(RelayResponse(409, buildJsonObject { put("error", JsonPrimitive("session_in_use")) })))
            return@flow
        }
        emit(AnswerEvent.Stream("session", buildJsonObject { put("session_id", JsonPrimitive(session)) }))
        delay(1200)
        if ("silent" in prompt) throw ApiException(ApiException.Kind.UNCERTAIN)
        val answer = data.getValue("answer").jsonPrimitive.content
        for ((index, piece) in answer.chunked(18).withIndex()) {
            delay(90)
            emit(AnswerEvent.Stream("delta", buildJsonObject { put("text", JsonPrimitive(piece)) }))
            if (index == 2 && "fail" in prompt) {
                emit(AnswerEvent.Stream("error", buildJsonObject { put("error", JsonPrimitive("claude_failed")) }))
                emit(AnswerEvent.Done(RelayResponse(200, null)))
                return@flow
            }
        }
        emit(
            AnswerEvent.Stream(
                "done",
                buildJsonObject {
                    put("answer", JsonPrimitive(answer))
                    put("session_id", JsonPrimitive(session))
                },
            ),
        )
        emit(AnswerEvent.Done(RelayResponse(200, null)))
    }

    /** `ago_minutes` becomes `last_activity`, counted back from now. */
    private fun relative(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> buildJsonObject {
            for ((name, value) in element) {
                if (name == "ago_minutes") {
                    put("last_activity", JsonPrimitive(Instant.ofEpochMilli(System.currentTimeMillis() - value.jsonPrimitive.long * 60_000).toString()))
                } else {
                    put(name, relative(value))
                }
            }
        }
        is JsonArray -> buildJsonArray { element.forEach { add(relative(it)) } }
        else -> element
    }
}
