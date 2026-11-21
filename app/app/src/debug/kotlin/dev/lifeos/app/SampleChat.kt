package dev.lifeos.app

import android.content.Context
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.relay.AnswerEvent
import dev.lifeos.app.relay.RelayResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * DEBUG builds only, part of the `sample_data` test aid (see [TestAids]): Chat and Sessions
 * without a computer. Folders and sessions come from `src/debug/assets/sample-chat.json`;
 * conversations are kept in memory while the app runs and behave like the bridge's: a sent
 * message is answered piece by piece with the invented answer, a message sent during a run
 * waits in line, a run can be stopped. Nothing is sent anywhere.
 *
 * A message that contains one of these words shows another state instead:
 * "in use" (the session is open on the computer), "fail" (the run breaks off after a few
 * words), "offline" (nothing leaves the phone), "silent" (the answer stops arriving),
 * "slow" (the answer takes half a minute, time enough to queue and stop),
 * "file" (the answer offers a small file for download and names one that is too large),
 * "delete" and "push" (the run waits for an approval; the first is a deletion), "choose"
 * (the run waits for an answer to two questions). With "hurry" the wait lasts 20 seconds
 * instead of ten minutes.
 *
 * The coach conversation (#114) is one more session, empty at first. It counts as long after
 * five messages, or at once when a message contains "tidy": the next answer then starts only
 * after six seconds, in a new session, as when the computer tidies the conversation up.
 */
internal class SampleChat(private val context: Context) {
    private val data: JsonObject by lazy {
        Json.parseToJsonElement(context.assets.open("sample-chat.json").use { it.readBytes().decodeToString() }).jsonObject
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One conversation as the bridge would hold it. */
    private inner class Session(seeded: Boolean) {
        val exchanges = mutableListOf<Pair<String, String>>()
        val queue = mutableListOf<Pair<String, String>>()
        var running = false
        var skills = true
        var paused: String? = null
        var stop = CompletableDeferred<Unit>()

        init {
            if (seeded) {
                for (item in data.getValue("history").jsonArray) {
                    exchanges += item.jsonObject.getValue("user").jsonPrimitive.content to item.jsonObject.getValue("assistant").jsonPrimitive.content
                }
            }
        }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private fun session(id: String) = sessions.getOrPut(id) { Session(seeded = id != NEW && !id.startsWith(COACH)) }

    // ---- the coach conversation (#114)
    private var coachRound = 1
    private var coachRuns = 0
    private val coachId get() = "$COACH-$coachRound"
    private fun coachDue() = session(coachId).exchanges.size >= COACH_FULL

    private fun renewCoach() {
        coachRound++
        coachRuns++
    }

    private fun usage(runs: Int) = buildJsonObject {
        put("runs", JsonPrimitive(runs))
        put("input_tokens", JsonPrimitive(runs * 2_400))
        put("output_tokens", JsonPrimitive(runs * 610))
        put("cache_creation_input_tokens", JsonPrimitive(runs * 9_000))
        put("cache_read_input_tokens", JsonPrimitive(runs * 41_000))
        put("cost_usd", JsonPrimitive(runs * 0.11))
        put("models", buildJsonArray { if (runs > 0) add(JsonPrimitive("opus")) })
    }

    private fun periods(today: Int, week: Int) = buildJsonObject {
        put("today", usage(today))
        put("week", usage(week))
    }

    private fun coachView() = ok {
        val exchanges = session(coachId).exchanges.size
        put("session_id", JsonPrimitive(coachId))
        put("started", JsonPrimitive(exchanges > 0))
        put("transcript_bytes", JsonPrimitive(exchanges * 80_000))
        put("compact_at_bytes", JsonPrimitive(COACH_FULL * 80_000))
        put("compactions", JsonPrimitive(coachRound - 1))
        put("summary", if (coachRound > 1) JsonPrimitive("The owner works on **Project Alpha** first and keeps mornings for the outline.\n\n- Exercise sheets are done on the day they arrive.\n- The essay topic waits for a reply.") else JsonNull)
        put(
            "last_compaction",
            if (coachRound > 1) buildJsonObject { put("at", JsonPrimitive(java.time.OffsetDateTime.now().withNano(0).toString())); put("result", JsonPrimitive("ok")); put("facts", JsonPrimitive(3)) } else JsonNull,
        )
        put("earlier_sessions", buildJsonArray { for (round in 1 until coachRound) add(JsonPrimitive("$COACH-$round")) })
        put("model", JsonPrimitive("opus"))
        put("effort", JsonPrimitive("high"))
        put("usage", periods(coachRuns, coachRuns + 9))
        put("morning_usage", periods(1, 6))
    }

    /** Questions and approvals a sample run waits for, by id. */
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private var asked = 0
    private var queued = 0

    /** The answer to a plain request, or null when it is not one of Chat's. */
    fun request(method: String, path: String, query: Map<String, String>, body: JsonElement?): RelayResponse? {
        if (method == "GET" && path == "/coach/chat") return coachView()
        if (method == "POST" && path == "/coach/chat/compact") {
            if (session(coachId).exchanges.isEmpty()) return error(409, "nothing_to_compact")
            Thread.sleep(4000) // one run on the computer
            renewCoach()
            return ok {
                put("facts", buildJsonArray { add(JsonPrimitive("profile/work.md")) })
                put("summary", JsonPrimitive(true))
                put("session_id", JsonPrimitive(coachId))
            }
        }
        val parts = Regex("^/sessions/([^/]+)/(.+)$").find(path)?.groupValues
        if (parts != null) {
            val session = session(parts[1])
            val rest = parts[2]
            return synchronized(session) {
                when {
                    method == "POST" && rest.startsWith("interactions/") -> {
                        val run = waiting.remove(rest.removePrefix("interactions/")) ?: return@synchronized error(404, "not_pending")
                        run.complete(body as? JsonObject ?: JsonObject(emptyMap()))
                        ok { put("answered", JsonPrimitive(true)) }
                    }
                    method == "GET" && rest == "interactions" -> ok { put("pending", JsonArray(emptyList())) }
                    // The coach conversation has no thread to read before its first message.
                    method == "GET" && rest == "history" && parts[1].startsWith(COACH) && session.exchanges.isEmpty() -> error(404, "session_not_found")
                    method == "GET" && rest == "history" -> history(session, query["before"])
                    // The folder's skills and commands; switched on per session (PATCH below).
                    method == "GET" && rest == "commands" -> ok {
                        put("enabled", JsonPrimitive(session.skills))
                        put("switched_on", JsonPrimitive(session.skills))
                        put("reason", if (session.skills) JsonNull else JsonPrimitive("skills are switched off for this session"))
                        put("capable_only", JsonPrimitive(true))
                        put("prefix", JsonPrimitive("folder:"))
                        put("commands", data.getValue("commands"))
                    }
                    method == "GET" && rest == "queue" -> ok {
                        put("running", JsonPrimitive(session.running))
                        put("paused", session.paused?.let(::JsonPrimitive) ?: JsonNull)
                        put("queue", buildJsonArray { session.queue.forEach { (id, prompt) -> add(buildJsonObject { put("id", JsonPrimitive(id)); put("prompt", JsonPrimitive(prompt)) }) } })
                    }
                    method == "POST" && rest == "cancel" -> {
                        val stopped = session.running
                        if (stopped) {
                            session.stop.complete(Unit)
                            session.paused = "cancel"
                        }
                        ok {
                            put("cancelled", JsonPrimitive(stopped))
                            put("queue_paused", JsonPrimitive(session.paused != null))
                        }
                    }
                    method == "POST" && rest == "queue/resume" -> {
                        session.paused = null
                        drain(session)
                        ok { put("resumed", JsonPrimitive(true)) }
                    }
                    rest.startsWith("queue/") -> {
                        val index = session.queue.indexOfFirst { it.first == rest.removePrefix("queue/") }
                        if (index < 0) return@synchronized error(404, "not_queued")
                        if (method == "DELETE") {
                            session.queue.removeAt(index)
                        } else {
                            session.queue[index] = session.queue[index].first to ((body as? JsonObject)?.get("prompt") as? JsonPrimitive)?.content.orEmpty()
                        }
                        ok { put("removed", JsonPrimitive(method == "DELETE")) }
                    }
                    else -> null
                }
            }
        }
        // What the computer keeps about a session (model, effort, title, pin, switches): taken as sent.
        val patched = Regex("^/sessions/([^/]+)$").find(path)?.groupValues?.get(1)
        if (method == "PATCH" && patched != null) {
            val changes = body as? JsonObject ?: JsonObject(emptyMap())
            (changes["skills"] as? JsonPrimitive)?.let { session(patched).skills = it.content == "true" }
            synchronized(meta) {
                val group = (changes["group"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (group != null && groups.none { it.first == group }) return error(404, "group_not_found")
                meta[patched] = JsonObject(meta[patched].orEmpty() + changes)
            }
            val listed = listedSessions().firstOrNull { it["id"]?.jsonPrimitive?.content == patched }
            return RelayResponse(200, described(listed ?: buildJsonObject { put("id", JsonPrimitive(patched)) }))
        }
        // The session list with what the owner changed, and the owner's groups (#74).
        if (method == "GET" && path == "/sessions") {
            return ok {
                put("sessions", buildJsonArray { listedSessions().forEach { add(described(it)) } })
                put("total", JsonPrimitive(listedSessions().size))
                put("groups", groupList())
            }
        }
        groupRequest(method, path, body)?.let { return it }
        return data["$method $path"]?.let { RelayResponse(200, relative(it)) }
    }

    /** What the owner changed about sessions (title, pin, group, switches), by session, and the groups. */
    private val meta = HashMap<String, JsonObject>()
    private val groups = mutableListOf<Pair<String, String>>()

    private fun listedSessions(): List<JsonObject> {
        val listed = relative(data.getValue("GET /sessions")).jsonObject.getValue("sessions").jsonArray.map { it.jsonObject }
        // The coach conversation is a session like the others once it has a message; the app gives it its own screen.
        if (session(coachId).exchanges.isEmpty()) return listed
        val coach = buildJsonObject {
            put("id", JsonPrimitive(coachId))
            put("title", JsonPrimitive("Coach"))
            put("folder", JsonPrimitive("/home/owner/brain"))
            put("last_activity", JsonPrimitive(Instant.now().toString()))
            put("in_use", JsonPrimitive(session(coachId).running))
            put("auto_group", JsonPrimitive("brain"))
            put("pinned", JsonPrimitive(true))
        }
        return listOf(coach) + listed
    }

    private fun groupList() = synchronized(meta) {
        buildJsonArray { groups.forEach { (id, name) -> add(buildJsonObject { put("id", JsonPrimitive(id)); put("name", JsonPrimitive(name)) }) } }
    }

    /** A session as the bridge describes it: the listed facts with the owner's changes on top. */
    private fun described(item: JsonObject): JsonObject = synchronized(meta) {
        val changes = meta[item["id"]?.jsonPrimitive?.content].orEmpty()
        buildJsonObject {
            item.forEach { (name, value) -> put(name, value) }
            put("detected_title", item["title"] ?: JsonNull)
            changes.forEach { (name, value) ->
                when {
                    // A cleared title is the detected one again; a group that is gone is no group.
                    name == "title" && value is JsonNull -> Unit
                    name == "group" && groups.none { it.first == (value as? JsonPrimitive)?.content } -> put(name, JsonNull)
                    else -> put(name, value)
                }
            }
        }
    }

    /** `/session-groups`: create, rename, delete; a name can be used once. */
    private fun groupRequest(method: String, path: String, body: JsonElement?): RelayResponse? {
        if (!path.startsWith("/session-groups")) return null
        val name = ((body as? JsonObject)?.get("name") as? JsonPrimitive)?.content?.trim().orEmpty()
        val id = path.removePrefix("/session-groups").trim('/')
        return synchronized(meta) {
            val index = groups.indexOfFirst { it.first == id }
            when {
                method == "GET" && id.isEmpty() -> ok { put("groups", groupList()) }
                method == "DELETE" && index >= 0 -> {
                    groups.removeAt(index)
                    ok { put("deleted", JsonPrimitive(true)) }
                }
                method != "POST" && method != "PATCH" -> null
                method == "PATCH" && index < 0 -> error(404, "group_not_found")
                name.isEmpty() -> error(400, "bad_request")
                groups.any { it.second.equals(name, ignoreCase = true) && it.first != id } -> error(409, "group_exists")
                else -> {
                    val group = (if (method == "POST") "samplegroup%02d".format(++asked) else id) to name
                    if (method == "POST") groups += group else groups[index] = group
                    RelayResponse(if (method == "POST") 201 else 200, buildJsonObject { put("id", JsonPrimitive(group.first)); put("name", JsonPrimitive(name)) })
                }
            }
        }
    }

    private fun ok(fields: JsonObjectBuilder.() -> Unit) = RelayResponse(200, buildJsonObject(fields))
    private fun error(status: Int, code: String) = RelayResponse(status, buildJsonObject { put("error", JsonPrimitive(code)) })

    /** Four exchanges a page, as the bridge; the cursor is the place of the page's first one. */
    private fun history(session: Session, before: String?): RelayResponse {
        val end = before?.removePrefix("p")?.toIntOrNull()?.coerceIn(0, session.exchanges.size) ?: session.exchanges.size
        val start = (end - PAGE).coerceAtLeast(0)
        return ok {
            put(
                "exchanges",
                buildJsonArray {
                    for ((user, assistant) in session.exchanges.subList(start, end)) {
                        add(
                            buildJsonObject {
                                put("user", JsonPrimitive(user))
                                put("assistant", JsonPrimitive(assistant))
                                put("complete", JsonPrimitive(assistant.isNotEmpty() || !session.running))
                            },
                        )
                    }
                },
            )
            put("older", if (start > 0) JsonPrimitive("p$start") else JsonNull)
        }
    }

    fun events(path: String, body: JsonElement?): Flow<AnswerEvent> = flow {
        val request = body as? JsonObject
        val text = (request?.get("prompt") as? JsonPrimitive)?.content.orEmpty()
        val prompt = text.lowercase()
        val toCoach = path == "/coach/chat/messages"
        var id = Regex("^/sessions/([^/]+)/").find(path)?.groupValues?.get(1) ?: if (toCoach) coachId else NEW
        if ("offline" in prompt) throw ApiException(ApiException.Kind.NOT_SENT)
        if (toCoach) {
            if (!session(id).running && session(id).exchanges.isNotEmpty() && (coachDue() || "tidy" in prompt)) {
                // The conversation had grown long: one run without any sign, then a new session.
                delay(6000)
                renewCoach()
                id = coachId
            }
            coachRuns++
        }
        val session = session(id)
        if ("in use" in prompt) {
            emit(AnswerEvent.Done(error(409, "session_in_use")))
            return@flow
        }
        // A run is going on: the message waits in line, or goes first and interrupts ("send now").
        val place = synchronized(session) {
            if (!session.running) {
                session.running = true
                session.stop = CompletableDeferred()
                if (session.paused == "cancel") session.paused = null
                session.exchanges += text to ""
                return@synchronized null
            }
            val now = (request?.get("when") as? JsonPrimitive)?.content == "now"
            val entry = "sampleq%03d".format(++queued) to text
            if (now) {
                session.queue.add(0, entry)
                session.paused = null
                session.stop.complete(Unit)
            } else {
                session.queue += entry
            }
            entry.first to if (now) 1 else session.queue.size
        }
        if (place != null) {
            emit(
                AnswerEvent.Done(
                    RelayResponse(
                        202,
                        buildJsonObject {
                            put("queued", JsonPrimitive(true))
                            put("queue_id", JsonPrimitive(place.first))
                            put("position", JsonPrimitive(place.second))
                        },
                    ),
                ),
            )
            return@flow
        }
        var written = ""
        try {
            emit(AnswerEvent.Stream("session", buildJsonObject { put("session_id", JsonPrimitive(id)) }))
            if (pause(session, 1200)) return@flow stopped()
            if ("silent" in prompt) throw ApiException(ApiException.Kind.UNCERTAIN)
            val wait = if ("hurry" in prompt) 20L else 600L
            val answer = when {
                "delete" in prompt || "push" in prompt -> {
                    val deletion = "delete" in prompt
                    val reply = ask(id, wait) {
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
                    val reply = ask(id, wait) {
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
            val step = if ("slow" in prompt) 2000L else 90L
            for ((index, piece) in answer.chunked(18).withIndex()) {
                if (pause(session, step)) return@flow stopped()
                written += piece
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
                        put("session_id", JsonPrimitive(id))
                        // "file": one file is offered (the 20-byte sample file) and one is refused.
                        if ("file" in prompt) {
                            put("files", Json.parseToJsonElement("""[{"offer":"sample-offer-${++asked}","name":"outline.md","type":"text/markdown","size":20}]"""))
                            put("files_refused", Json.parseToJsonElement("""[{"name":"recordings.zip","reason":"larger than the limit of 50 MB"}]"""))
                        }
                    },
                ),
            )
            emit(AnswerEvent.Done(RelayResponse(200, null)))
        } finally {
            synchronized(session) {
                session.exchanges[session.exchanges.lastIndex] = text to written
                session.running = false
                drain(session)
            }
        }
    }

    private suspend fun FlowCollector<AnswerEvent>.stopped() {
        emit(AnswerEvent.Stream("error", buildJsonObject { put("error", JsonPrimitive("cancelled")) }))
        emit(AnswerEvent.Done(RelayResponse(200, null)))
    }

    /** Wait [millis]; true when the run was stopped meanwhile. */
    private suspend fun pause(session: Session, millis: Long): Boolean = withTimeoutOrNull(millis) { session.stop.await() } != null

    /** The next waiting message, answered in the background as the bridge does (not streamed). */
    private fun drain(session: Session) {
        if (session.running || session.paused != null || session.queue.isEmpty()) return
        val (_, text) = session.queue.removeAt(0)
        session.running = true
        session.stop = CompletableDeferred()
        session.exchanges += text to ""
        scope.launch {
            val stopped = pause(session, 6000)
            synchronized(session) {
                session.exchanges[session.exchanges.lastIndex] = text to if (stopped) "" else "Answer to the waiting message “$text”."
                session.running = false
                drain(session)
            }
        }
    }

    /** Show the owner a question or an approval and wait as the computer would; null when no answer came. */
    private suspend fun FlowCollector<AnswerEvent>.ask(session: String, waitSeconds: Long, fields: JsonObjectBuilder.() -> Unit): JsonObject? {
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
        const val NEW = "sample-session-new"
        const val COACH = "sample-coach"
        const val COACH_FULL = 5
        const val PAGE = 4
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
