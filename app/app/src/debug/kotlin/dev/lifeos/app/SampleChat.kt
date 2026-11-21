package dev.lifeos.app

import android.content.Context
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
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
import java.util.concurrent.ConcurrentHashMap

/**
 * DEBUG builds only, part of the `sample_data` test aid (see [TestAids]): Chat and Sessions
 * without a computer. Folders, sessions and a conversation come from
 * `src/debug/assets/sample-chat.json`; a sent message is answered piece by piece with the
 * invented answer there. Nothing is sent anywhere.
 *
 * A message that contains one of these words shows another state instead:
 * "in use" (the session is open on the computer), "fail" (the run breaks off after a few
 * words), "offline" (nothing leaves the phone), "silent" (the answer stops arriving),
 * "delete" and "push" (the run waits for an approval; the first is a deletion), "choose"
 * (the run waits for an answer to two questions). With "hurry" the wait lasts 20 seconds
 * instead of ten minutes.
 */
internal class SampleChat(private val context: Context) {
    private val data: JsonObject by lazy {
        Json.parseToJsonElement(context.assets.open("sample-chat.json").use { it.readBytes().decodeToString() }).jsonObject
    }

    /** Questions and approvals a sample run waits for, by id. */
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var asked = 0

    /** The answer to a plain request, or null when it is not one of Chat's. */
    fun request(method: String, path: String, body: JsonElement?): RelayResponse? {
        val interaction = Regex("^/sessions/[^/]+/interactions/([^/]+)$").find(path)?.groupValues?.get(1)
        if (method == "POST" && interaction != null) {
            val run = waiting.remove(interaction)
                ?: return RelayResponse(404, buildJsonObject { put("error", JsonPrimitive("not_pending")) })
            run.complete(body as? JsonObject ?: JsonObject(emptyMap()))
            return RelayResponse(200, buildJsonObject { put("answered", JsonPrimitive(true)) })
        }
        if (method == "GET" && path.endsWith("/interactions")) return RelayResponse(200, buildJsonObject { put("pending", JsonArray(emptyList())) })
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
        val wait = if ("hurry" in prompt) 20L else 600L
        val answer = when {
            "delete" in prompt || "push" in prompt -> {
                val deletion = "delete" in prompt
                val reply = ask(session, wait) {
                    put("kind", JsonPrimitive("approval"))
                    put("tool", JsonPrimitive("Bash"))
                    put("command", JsonPrimitive(if (deletion) "rm -r notes/drafts/plan-old.md notes/drafts/outline-v1.md notes/drafts/scratch.md" else "git push origin main"))
                    put("folder", JsonPrimitive("/home/owner/project-alpha"))
                    put("category", JsonPrimitive(if (deletion) "deletion" else "push"))
                    put("reason", JsonPrimitive(if (deletion) "permanently deleting files" else "pushing to a remote repository"))
                }
                if ((reply?.get("approve") as? JsonPrimitive)?.content == "true") "Done, as you allowed." else "I left everything as it is."
            }
            "choose" in prompt -> {
                val reply = ask(session, wait) {
                    put("kind", JsonPrimitive("question"))
                    put("questions", Json.parseToJsonElement(QUESTIONS))
                }
                when {
                    reply == null -> "No answer came in time, so I went on without it."
                    "decline" in reply -> "Fine, I will go on without that."
                    else -> "Thanks. You answered: `" + reply["answers"].toString() + "`"
                }
            }
            else -> data.getValue("answer").jsonPrimitive.content
        }
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

    /** Show the owner a question or an approval and wait as the computer would; null when no answer came. */
    private suspend fun FlowCollector<AnswerEvent>.ask(
        session: String,
        waitSeconds: Long,
        fields: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ): JsonObject? {
        val id = "sample%04d".format(++asked)
        val answer = CompletableDeferred<JsonObject>()
        waiting[id] = answer
        val now = Instant.now()
        emit(
            AnswerEvent.Stream(
                "interaction",
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("session_id", JsonPrimitive(session))
                    fields()
                    put("created_at", JsonPrimitive(now.toString()))
                    put("expires_at", JsonPrimitive(now.plusSeconds(waitSeconds).toString()))
                },
            ),
        )
        return try {
            withTimeoutOrNull(waitSeconds * 1000) { answer.await() }
        } finally {
            waiting.remove(id)
        }
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

    private companion object {
        const val QUESTIONS = """[
            {"question": "Which draft should the summary be based on?", "header": "Draft", "multi_select": false, "options": [
                {"label": "The outline", "description": "notes/alpha/outline.md"},
                {"label": "The check-in notes", "description": "notes/alpha/check-in.md"},
                {"label": "Both", "description": ""}]},
            {"question": "What should it contain?", "header": "Contents", "multi_select": true, "options": [
                {"label": "Open tasks", "description": ""},
                {"label": "Decisions", "description": ""},
                {"label": "Next steps", "description": "For the coming week"}]}
        ]"""
    }
}
