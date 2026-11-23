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
 * As the bridge since #147: a new conversation runs under the id the phone chose (the same
 * first message again is answered as a repeat), a slow answer sends keep-alives, and a card
 * is closed by an event. One listed session ("Summarise the lecture notes of week 3") waits
 * for an approval from the start, to see what a notification about it opens. A first message
 * that contains "lost" arrives and is answered, but nothing of it reaches the phone.
 *
 * The coach conversation (#114) is one more session, empty at first. It counts as long after
 * five messages, or at once when a message contains "tidy": the next answer then starts only
 * after six seconds, in a new session, as when the computer tidies the conversation up.
 *
 * Folders and blank chats (#163, #168): the list says each session's folder, kind and colour,
 * and has one entry per folder for the tiles; two listed blank chats share the "Chats" tile,
 * and a conversation started here joins it (or the folder it was started in). Moving between
 * conversations by asking (#164): "jump" opens another conversation, "pick" offers three on a
 * card, "begin" starts a new one in the project folder; each streams its marker line first.
 * "move" proposes to move a blank chat's files into the project (a card that waits, and one
 * refused at once); with "taken" the first yes finds a name taken, with "broken" the move
 * breaks off after one file. "root off" as a new blank chat's first message: blank chats are
 * switched off on the computer.
 */
internal class SampleChat(private val context: Context) {
    private val data: JsonObject by lazy {
        Json.parseToJsonElement(context.assets.open("sample-chat.json").use { it.readBytes().decodeToString() }).jsonObject
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** One conversation as the bridge would hold it. */
    private inner class Session(seeded: Boolean, val parkedHere: Boolean = false) {
        val exchanges = mutableListOf<Pair<String, String>>()

        /** The MCP servers the answer to a message used, by that message's place in [exchanges] (#157). */
        val servers = HashMap<Int, List<String>>()
        val queue = mutableListOf<Pair<String, String>>()
        var running = false
        var skills = true
        var paused: String? = null
        var stop = CompletableDeferred<Unit>()

        /** What the run the computer makes by itself has said so far, for a follower (`/live`, #154). */
        @Volatile
        var live: LiveRun? = null

        /** The file offers of this session: offer id, name, when it runs out. */
        val offers = mutableListOf<Triple<String, String, Instant>>()

        /** A conversation started here: the folder it belongs to, as the bridge describes it (#163). */
        @Volatile
        var place: JsonObject? = null

        init {
            if (seeded) {
                for (item in data.getValue("history").jsonArray) {
                    exchanges += item.jsonObject.getValue("user").jsonPrimitive.content to item.jsonObject.getValue("assistant").jsonPrimitive.content
                }
            }
            if (parkedHere) exchanges += "Push the milestone summary to the remote." to ""
        }
    }

    /** A run that can be followed: its text grows, then [end] says how it ended ("done" or an error word). */
    private class LiveRun(val queueId: String?) {
        val text = StringBuilder()

        @Volatile
        var end: String? = null
    }

    private fun queueJson(session: Session) = buildJsonArray {
        session.queue.forEach { (id, prompt) -> add(buildJsonObject { put("id", JsonPrimitive(id)); put("prompt", JsonPrimitive(prompt)) }) }
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private fun session(id: String) = sessions.getOrPut(id) { Session(seeded = id.startsWith("sample-session-") && id != NEW, parkedHere = id == PARKED) }

    /** The session [PARKED] waits for an approval until it is answered (once per start of the app). */
    private var parked = true

    private fun parkedCard() = buildJsonObject {
        val now = Instant.now()
        put("id", JsonPrimitive(PARKED_CARD))
        put("session_id", JsonPrimitive(PARKED))
        put("kind", JsonPrimitive("approval"))
        put("tool", JsonPrimitive("Bash"))
        put("command", JsonPrimitive("git push origin main"))
        put("folder", JsonPrimitive("/home/owner/course-notes"))
        put("category", JsonPrimitive("push"))
        put("created_at", JsonPrimitive(now.minusSeconds(60).toString()))
        put("expires_at", JsonPrimitive(now.plusSeconds(540).toString()))
    }

    // ---- the coach conversation (#114)
    private var coachRound = 1

    /** The coach session that is being tidied up right now; a stop is refused for it. */
    @Volatile
    private var coachTidying: String? = null
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

    /**
     * A skill install that waits across runs (#97), as the bridge keeps it for a day: it is
     * "downloading" for [FETCH_MILLIS], then ready (or failed, for a proposal with "broken").
     */
    private class Proposal(val session: String, val source: String, val broken: Boolean, val taken: Boolean = false, val madeAt: Instant = Instant.now())

    private val proposals = ConcurrentHashMap<String, Proposal>()

    private fun proposalCard(id: String, proposal: Proposal) = buildJsonObject {
        val fetched = Instant.now().isAfter(proposal.madeAt.plusMillis(FETCH_MILLIS))
        put("id", JsonPrimitive(id))
        put("kind", JsonPrimitive("skill_install"))
        put("session_id", JsonPrimitive(proposal.session))
        put("folder", JsonPrimitive("/home/owner/project-alpha"))
        put("source", JsonPrimitive(proposal.source))
        put("created_at", JsonPrimitive(proposal.madeAt.toString()))
        put("expires_at", JsonPrimitive(proposal.madeAt.plusSeconds(24 * 3600).toString()))
        when {
            !fetched -> put("state", JsonPrimitive("downloading"))
            proposal.broken -> {
                put("state", JsonPrimitive("failed"))
                put("error", JsonPrimitive("there is no SKILL.md at the top"))
                put("error_code", JsonPrimitive("source_refused"))
            }
            else -> {
                put("state", JsonPrimitive("ready"))
                put("name", JsonPrimitive("tidy-notes"))
                put("target", JsonPrimitive("/home/owner/project-alpha/.claude/skills/tidy-notes"))
                put("description", JsonPrimitive("Tidy the notes of a project: merge duplicates, fix headings, list open questions."))
                put("warnings", Json.parseToJsonElement("""["shell_lines","has_scripts"]"""))
                put("files", Json.parseToJsonElement("""[{"path":"SKILL.md","size":1840},{"path":"reference/headings.md","size":612},{"path":"scripts/merge.py","size":2310}]"""))
                put("total_size", JsonPrimitive(4762))
            }
        }
    }

    private fun proposalCards(session: String) = proposals.filter { it.value.session == session }.toSortedMap().map { (id, proposal) -> proposalCard(id, proposal) }

    /**
     * A proposal to move a blank chat's files into a project (#163): it waits for a day, like a
     * skill install. [taken]: the first yes finds a name taken in the target (the card stays);
     * [broken]: the move breaks off after the first file.
     */
    private class MoveProposal(val session: String, val folder: String, var taken: Boolean, val broken: Boolean, val madeAt: Instant = Instant.now())

    private val moves = ConcurrentHashMap<String, MoveProposal>()

    private fun moveCard(id: String, move: MoveProposal) = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("kind", JsonPrimitive("move_files"))
        put("session_id", JsonPrimitive(move.session))
        put("folder", JsonPrimitive(move.folder))
        put("state", JsonPrimitive("ready"))
        put("target", JsonPrimitive("/home/owner/project-alpha/notes"))
        put("target_label", JsonPrimitive("project-alpha/notes"))
        put("files", Json.parseToJsonElement("""[{"path":"packing-list.md","size":2140},{"path":"data/budget.csv","size":380}]"""))
        put("total_size", JsonPrimitive(2520))
        put("created_at", JsonPrimitive(move.madeAt.toString()))
        put("expires_at", JsonPrimitive(move.madeAt.plusSeconds(24 * 3600).toString()))
    }

    private fun moveCards(session: String) = moves.filter { it.value.session == session }.toSortedMap().map { (id, move) -> moveCard(id, move) }

    /**
     * A chat in the program folder asks for its changes to be applied (#180). [changed]: the
     * first yes finds the folder at another commit (the computer answers "not done").
     */
    private class UpdateAsk(val session: String, val changed: Boolean, val madeAt: Instant = Instant.now())

    private val updateAsks = ConcurrentHashMap<String, UpdateAsk>()

    private fun updateCard(id: String, ask: UpdateAsk) = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("kind", JsonPrimitive("apply_update"))
        put("session_id", JsonPrimitive(ask.session))
        put("folder", JsonPrimitive("/home/owner/lifeos"))
        put("state", JsonPrimitive("ready"))
        put("commit", JsonPrimitive("4f2a9c1e7b3d5a6c8e0f1a2b3c4d5e6f7a8b9c0d"))
        put("running_commit", JsonPrimitive("9e8d7c6b5a4f3e2d1c0b9a8f7e6d5c4b3a2f1e0d"))
        put("created_at", JsonPrimitive(ask.madeAt.toString()))
        put("expires_at", JsonPrimitive(ask.madeAt.plusSeconds(24 * 3600).toString()))
    }

    private fun updateCards(session: String) = updateAsks.filter { it.value.session == session }.toSortedMap().map { (id, ask) -> updateCard(id, ask) }

    /** A conversation a switch names, as the bridge describes it (#164): never its messages. */
    private fun switchSession(id: String): JsonObject {
        val item = listedSessions().firstOrNull { it["id"]?.jsonPrimitive?.content == id } ?: JsonObject(emptyMap())
        return buildJsonObject {
            put("id", JsonPrimitive(id))
            listOf("title", "folder_label", "kind", "colour", "last_activity").forEach { name -> put(name, item[name] ?: JsonNull) }
        }
    }

    // ---- the MCP servers of a session (#153, #162): what the folder's .mcp.json and the owner's
    // personal settings declare, and the owner's approvals; keyed "scope/name"
    private val mcpState = ConcurrentHashMap(
        mapOf(
            "folder/notes-index" to "not_approved",
            "folder/calendar" to "approved",
            "folder/issue-tracker" to "changed",
            "personal/web-search" to "not_approved",
            "personal/team-wiki" to "unsupported",
        ),
    )

    private fun mcpServer(key: String) = buildJsonObject {
        val (scope, name) = key.split("/", limit = 2)
        put("name", JsonPrimitive(name))
        put("scope", JsonPrimitive(scope))
        when (name) {
            "calendar", "team-wiki" -> {
                put("type", JsonPrimitive("http"))
                put("command", JsonNull)
                put("args", JsonArray(emptyList()))
                put("url", JsonPrimitive(if (name == "team-wiki") "https://wiki.example.org/mcp" else "https://calendar.example.org/mcp"))
                put("env_names", JsonArray(emptyList()))
                put("header_names", Json.parseToJsonElement("""["Authorization"]"""))
            }
            else -> {
                put("type", JsonPrimitive("stdio"))
                put("command", JsonPrimitive(if (name == "issue-tracker") "python3" else "npx"))
                put("args", Json.parseToJsonElement(when (name) { "notes-index" -> """["-y","notes-index-server","--root","notes"]"""; "web-search" -> """["-y","web-search-server"]"""; else -> """["tools/issue_tracker.py","--read-only"]""" }))
                put("url", JsonNull)
                put("env_names", Json.parseToJsonElement(when (name) { "notes-index" -> """["NOTES_TOKEN"]"""; "web-search" -> """["SEARCH_API_KEY"]"""; else -> "[]" }))
                put("header_names", JsonArray(emptyList()))
            }
        }
        put("hash", JsonPrimitive("samplehash-$key"))
        put("outside_sandbox", JsonPrimitive(true))
        val state = mcpState[key] ?: "not_approved"
        put("state", JsonPrimitive(state))
        put("approved_at", if (state == "approved" || state == "changed") JsonPrimitive(Instant.now().minusSeconds(86_400).toString()) else JsonNull)
    }

    /** The key of the server a request names: its scope (`?scope=`, else the folder's if it has one) and name. */
    private fun mcpKey(name: String, query: Map<String, String>): String {
        val scope = query["scope"] ?: if (mcpState.containsKey("folder/$name")) "folder" else "personal"
        return "$scope/$name"
    }

    /** A server an answer used, as `done` and the history name it: its name and scope. */
    private fun usedServer(key: String) = buildJsonObject {
        put("name", JsonPrimitive(key.substringAfter("/")))
        put("scope", JsonPrimitive(key.substringBefore("/")))
        put("outside_sandbox", JsonPrimitive(true))
    }

    /** An approval card for one server ([name] is its "scope/name" key): it waits for a day, like a skill install. */
    private class McpCard(val session: String, val name: String, val madeAt: Instant = Instant.now())

    private val mcpCards = ConcurrentHashMap<String, McpCard>()

    private fun mcpCard(id: String, card: McpCard) = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("kind", JsonPrimitive("mcp_server"))
        put("session_id", JsonPrimitive(card.session))
        put("folder", JsonPrimitive("/home/owner/project-alpha"))
        put("scope", JsonPrimitive(card.name.substringBefore("/")))
        put("server", mcpServer(card.name))
        put("warning", JsonPrimitive("An approved MCP server runs outside the sandbox."))
        put("created_at", JsonPrimitive(card.madeAt.toString()))
        put("expires_at", JsonPrimitive(card.madeAt.plusSeconds(24 * 3600).toString()))
    }

    private fun mcpCards(session: String) = mcpCards.filter { it.value.session == session }.toSortedMap().map { (id, card) -> mcpCard(id, card) }

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
                    method == "POST" && rest == "interactions/$PARKED_CARD" && parts[1] == PARKED && parked -> {
                        parked = false
                        val allowed = ((body as? JsonObject)?.get("approve") as? JsonPrimitive)?.content == "true"
                        session.exchanges[session.exchanges.lastIndex] =
                            session.exchanges.last().first to if (allowed) "Pushed, as you allowed." else "I left it unpushed."
                        ok { put("answered", JsonPrimitive(true)) }
                    }
                    method == "GET" && rest == "mcp-servers" -> ok {
                        put("capable_only", JsonPrimitive(true))
                        put("note", JsonPrimitive("An approved MCP server runs outside the sandbox."))
                        put("servers", buildJsonArray { mcpState.keys.sorted().forEach { add(mcpServer(it)) } })
                    }
                    method == "POST" && rest.startsWith("mcp-servers/") && rest.endsWith("/approval") -> {
                        val name = mcpKey(rest.removePrefix("mcp-servers/").removeSuffix("/approval"), query)
                        if (!mcpState.containsKey(name)) return@synchronized error(404, "mcp_server_not_found")
                        if (mcpState[name] == "unsupported") return@synchronized error(409, "mcp_server_unsupported")
                        // One card per definition (#157): asked for again, the card that waits comes back.
                        val waitingId = mcpCards.entries.firstOrNull { it.value.session == parts[1] && it.value.name == name }?.key
                        val cardId = waitingId ?: "samplemcp%03d".format(++asked)
                        val card = mcpCards.getOrPut(cardId) { McpCard(parts[1], name) }
                        RelayResponse(200, JsonObject(mcpCard(cardId, card) + ("already_waiting" to JsonPrimitive(waitingId != null))))
                    }
                    method == "DELETE" && rest.startsWith("mcp-servers/") -> {
                        val name = mcpKey(rest.removePrefix("mcp-servers/"), query)
                        val had = mcpState[name].let { it == "approved" || it == "changed" }
                        if (had) mcpState[name] = "not_approved"
                        ok { put("revoked", JsonPrimitive(had)) }
                    }
                    method == "POST" && rest.startsWith("interactions/") && mcpCards[rest.removePrefix("interactions/")]?.session == parts[1] -> {
                        val id = rest.removePrefix("interactions/")
                        val card = mcpCards.remove(id) ?: return@synchronized error(404, "not_pending")
                        val approved = ((body as? JsonObject)?.get("approve") as? JsonPrimitive)?.content == "true"
                        if (approved) mcpState[card.name] = "approved"
                        ok {
                            put("answered", JsonPrimitive(true))
                            put("id", JsonPrimitive(id))
                            put("approved", JsonPrimitive(approved))
                            if (approved) put("name", JsonPrimitive(card.name.substringAfter("/")))
                        }
                    }
                    // A skill install: the computer takes it out of its list, then acts on a yes.
                    method == "POST" && rest.startsWith("interactions/") && proposals[rest.removePrefix("interactions/")]?.session == parts[1] -> {
                        val id = rest.removePrefix("interactions/")
                        val proposal = proposals[id] ?: return@synchronized error(404, "not_pending")
                        val yes = ((body as? JsonObject)?.get("approve") as? JsonPrimitive)?.content == "true"
                        val card = proposalCard(id, proposal)
                        // A yes the computer refuses leaves the card waiting (#157); a no is always taken.
                        if (yes && card["state"]?.jsonPrimitive?.content == "downloading") return@synchronized error(409, "still_downloading")
                        if (yes && proposal.taken) return@synchronized error(409, "skill_exists")
                        proposals.remove(id)
                        val installed = yes && card["state"]?.jsonPrimitive?.content == "ready"
                        ok {
                            put("answered", JsonPrimitive(true))
                            put("id", JsonPrimitive(id))
                            put("installed", JsonPrimitive(installed))
                            if (installed) {
                                put("name", card.getValue("name"))
                                put("target", card.getValue("target"))
                            }
                        }
                    }
                    // Moving a chat's files (#163): a refused yes leaves the card waiting; the bridge never overwrites.
                    method == "POST" && rest.startsWith("interactions/") && moves[rest.removePrefix("interactions/")]?.session == parts[1] -> {
                        val id = rest.removePrefix("interactions/")
                        val move = moves[id] ?: return@synchronized error(404, "not_pending")
                        val yes = ((body as? JsonObject)?.get("approve") as? JsonPrimitive)?.content == "true"
                        if (yes && move.taken) {
                            move.taken = false
                            return@synchronized error(409, "file_exists")
                        }
                        moves.remove(id)
                        ok {
                            put("answered", JsonPrimitive(true))
                            put("id", JsonPrimitive(id))
                            put("moved", JsonPrimitive(yes && !move.broken))
                            if (yes) {
                                put("files", Json.parseToJsonElement(if (move.broken) """["packing-list.md"]""" else """["packing-list.md","data/budget.csv"]"""))
                                put("failed", Json.parseToJsonElement(if (move.broken) """["data/budget.csv"]""" else "[]"))
                                put("target", JsonPrimitive("/home/owner/project-alpha/notes"))
                                put("target_label", JsonPrimitive("project-alpha/notes"))
                            }
                        }
                    }
                    // Applying an update (#180): a yes starts it, unless the commit changed since the card.
                    method == "POST" && rest.startsWith("interactions/") && updateAsks[rest.removePrefix("interactions/")]?.session == parts[1] -> {
                        val id = rest.removePrefix("interactions/")
                        val ask = updateAsks.remove(id) ?: return@synchronized error(404, "not_pending")
                        val yes = ((body as? JsonObject)?.get("approve") as? JsonPrimitive)?.content == "true"
                        ok {
                            put("answered", JsonPrimitive(true))
                            put("id", JsonPrimitive(id))
                            put("approved", JsonPrimitive(yes && !ask.changed))
                            if (yes && ask.changed) put("reason", JsonPrimitive("changed"))
                            if (yes && !ask.changed) put("state", JsonPrimitive("waiting"))
                        }
                    }
                    method == "POST" && rest.startsWith("interactions/") -> {
                        val run = waiting.remove(rest.removePrefix("interactions/")) ?: return@synchronized error(404, "not_pending")
                        run.complete(body as? JsonObject ?: JsonObject(emptyMap()))
                        ok { put("answered", JsonPrimitive(true)) }
                    }
                    method == "GET" && rest == "interactions" ->
                        ok { put("pending", JsonArray((if (parts[1] == PARKED && parked) listOf(parkedCard()) else emptyList()) + proposalCards(parts[1]) + mcpCards(parts[1]) + moveCards(parts[1]) + updateCards(parts[1]))) }
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
                        // The owner's personal skills (#162): offered without the session's switch.
                        put("personal", Json.parseToJsonElement("""{"enabled":true,"reason":null,"prefix":"personal:"}"""))
                        put("commands", data.getValue("commands"))
                    }
                    method == "GET" && rest == "queue" -> ok {
                        put("running", JsonPrimitive(session.running || (parts[1] == PARKED && parked)))
                        put("paused", session.paused?.let(::JsonPrimitive) ?: JsonNull)
                        put("queue", queueJson(session))
                    }
                    // The offers that have not run out: a card read again keeps its download (#154).
                    method == "GET" && rest == "files" -> ok {
                        put(
                            "files",
                            buildJsonArray {
                                session.offers.filter { it.third.isAfter(Instant.now()) }.forEach { (offer, name, expires) ->
                                    add(
                                        buildJsonObject {
                                            put("offer", JsonPrimitive(offer))
                                            put("name", JsonPrimitive(name))
                                            put("type", JsonPrimitive("text/markdown"))
                                            put("size", JsonPrimitive(20))
                                            put("expires_at", JsonPrimitive(expires.toString()))
                                        },
                                    )
                                }
                            },
                        )
                    }
                    // "Send now" for a waiting message, in one request (#154).
                    method == "POST" && rest.startsWith("queue/") && rest.endsWith("/send-now") -> {
                        val queueId = rest.removePrefix("queue/").removeSuffix("/send-now")
                        val index = session.queue.indexOfFirst { it.first == queueId }
                        if (index < 0) return@synchronized error(404, "not_queued")
                        session.queue.add(0, session.queue.removeAt(index))
                        session.paused = null
                        val interrupted = session.running
                        if (interrupted) session.stop.complete(Unit) else drain(session)
                        ok {
                            put("promoted", JsonPrimitive(true))
                            put("queue_id", JsonPrimitive(queueId))
                            put("interrupted_current_run", JsonPrimitive(interrupted))
                            put("queue", queueJson(session))
                        }
                    }
                    method == "POST" && rest == "cancel" && coachTidying == parts[1] -> error(409, "compaction_running")
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
                        ok {
                            put("resumed", JsonPrimitive(true))
                            put("queue", queueJson(session))
                        }
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
        // One session, read fresh (#154): as in the list, with its run and its waiting messages.
        if (method == "GET" && patched != null) {
            val listed = listedSessions().firstOrNull { it["id"]?.jsonPrimitive?.content == patched }
            val started = sessions[patched]
            if (listed == null && started == null) return error(404, "session_not_found")
            val session = session(patched)
            val item = listed ?: started(patched, session)
            return synchronized(session) {
                RelayResponse(
                    200,
                    JsonObject(
                        described(item) + mapOf(
                            "followable" to JsonPrimitive(session.live != null),
                            "queue" to queueJson(session),
                            "queue_paused" to (session.paused?.let(::JsonPrimitive) ?: JsonNull),
                        ),
                    ),
                )
            }
        }
        if (method == "PATCH" && patched != null) {
            val changes = body as? JsonObject ?: JsonObject(emptyMap())
            (changes["skills"] as? JsonPrimitive)?.let { session(patched).skills = it.content == "true" }
            // Custom groups are gone (#164): the bridge refuses the field.
            if (changes.containsKey("group")) return error(400, "bad_request")
            synchronized(meta) { meta[patched] = JsonObject(meta[patched].orEmpty() + changes) }
            val listed = listedSessions().firstOrNull { it["id"]?.jsonPrimitive?.content == patched }
            return RelayResponse(200, described(listed ?: buildJsonObject { put("id", JsonPrimitive(patched)) }))
        }
        // The session list with what the owner changed, and one entry per folder for the tiles (#163).
        if (method == "GET" && path == "/sessions") {
            val listed = listedSessions()
            return ok {
                put("sessions", buildJsonArray { listed.forEach { add(described(it)) } })
                put("total", JsonPrimitive(listed.size))
                put("folders", folderTiles(listed))
            }
        }
        reminderRequest(method, path, body)?.let { return it }
        return data["$method $path"]?.let { RelayResponse(200, relative(it)) }
    }

    /** What the owner changed about sessions (title, pin, switches), by session. */
    private val meta = HashMap<String, JsonObject>()

    /** A conversation started from the phone, as the list shows it: its first message is its title. */
    private fun started(id: String, session: Session) = buildJsonObject {
        put("id", JsonPrimitive(id))
        put("title", JsonPrimitive(session.exchanges.firstOrNull()?.first.orEmpty()))
        put("last_activity", JsonPrimitive(Instant.now().toString()))
        put("in_use", JsonPrimitive(session.running))
        (session.place ?: placeOf(null)).forEach { (name, value) -> put(name, value) }
    }

    /**
     * Where a new conversation belongs, as the bridge says it: no folder is a blank chat in a
     * folder of its own under the chat root; any other is one of the folders of `GET /folders`.
     */
    private fun placeOf(folder: String?): JsonObject {
        val known = data.getValue("GET /folders").jsonObject.getValue("folders").jsonArray.map { it.jsonObject }
        val entry = known.firstOrNull { folder != null && it["path"]?.jsonPrimitive?.content == folder } ?: known.first()
        val kind = entry.getValue("kind").jsonPrimitive.content
        val path = entry.getValue("path").jsonPrimitive.content
        return buildJsonObject {
            put("folder", JsonPrimitive(if (kind == "chat") "$path/${java.time.LocalDate.now()}-%06x".format(++asked) else path))
            put("folder_key", JsonPrimitive(if (kind == "chat") "chats" else path))
            put("folder_label", entry.getValue("label"))
            put("kind", JsonPrimitive(kind))
            put("colour", entry.getValue("colour"))
        }
    }

    private fun listedSessions(): List<JsonObject> {
        val listed = relative(data.getValue("GET /sessions")).jsonObject.getValue("sessions").jsonArray.map { it.jsonObject }
        // Conversations started from the phone, newest first.
        val mine = sessions.entries.filter { (id, session) -> session.place != null && session.exchanges.isNotEmpty() && listed.none { it["id"]?.jsonPrimitive?.content == id } }
            .map { (id, session) -> started(id, session) }
            .reversed()
        // The coach conversation is a session like the others once it has a message; the app gives it its own screen.
        if (session(coachId).exchanges.isEmpty()) return mine + listed
        val coach = buildJsonObject {
            put("id", JsonPrimitive(coachId))
            put("title", JsonPrimitive("Coach"))
            put("folder", JsonPrimitive("/home/owner/brain"))
            put("last_activity", JsonPrimitive(Instant.now().toString()))
            put("in_use", JsonPrimitive(session(coachId).running))
            put("folder_key", JsonPrimitive("coach"))
            put("folder_label", JsonPrimitive("Coach"))
            put("kind", JsonPrimitive("coach"))
            put("colour", JsonPrimitive(1))
            put("pinned", JsonPrimitive(true))
        }
        return listOf(coach) + mine + listed
    }

    /** The folders of the tiles, in the bridge's order (chats, coach, memory, the allowed folders), with their counts. */
    private fun folderTiles(listed: List<JsonObject>) = buildJsonArray {
        val known = data.getValue("GET /folders").jsonObject.getValue("folders").jsonArray.map { it.jsonObject }
        val entries = buildList {
            add(known.first() to "chats")
            if (listed.any { it["kind"]?.jsonPrimitive?.content == "coach" }) {
                add(buildJsonObject { put("path", JsonPrimitive("/home/owner/brain")); put("label", JsonPrimitive("Coach")); put("kind", JsonPrimitive("coach")); put("colour", JsonPrimitive(1)) } to "coach")
            }
            known.drop(1).forEach { add(it to it.getValue("path").jsonPrimitive.content) }
        }
        for ((entry, key) in entries) {
            val inside = listed.filter { it["folder_key"]?.jsonPrimitive?.content == key }
            add(
                buildJsonObject {
                    put("key", JsonPrimitive(key))
                    put("name", entry.getValue("label"))
                    put("kind", entry.getValue("kind"))
                    put("path", entry.getValue("path"))
                    put("colour", entry.getValue("colour"))
                    put("count", JsonPrimitive(inside.size))
                    put("last_activity", inside.mapNotNull { it["last_activity"]?.jsonPrimitive?.content }.maxOrNull()?.let(::JsonPrimitive) ?: JsonNull)
                },
            )
        }
    }

    /** A session as the bridge describes it: the listed facts with the owner's changes on top. */
    private fun described(item: JsonObject): JsonObject = synchronized(meta) {
        val changes = meta[item["id"]?.jsonPrimitive?.content].orEmpty()
        buildJsonObject {
            item.forEach { (name, value) -> put(name, value) }
            put("detected_title", item["title"] ?: JsonNull)
            // The sample sessions start with their folder's skills switched on.
            item["id"]?.jsonPrimitive?.content?.let { put("skills", JsonPrimitive(session(it).skills)) }
            changes.forEach { (name, value) ->
                // A cleared title is the detected one again.
                if (!(name == "title" && value is JsonNull)) put(name, value)
            }
        }
    }

    /**
     * Session reminders (#75), kept while the app runs. One has fired already: the push test
     * aid with `lifeos.test.push_ref sample-reminder-1` opens its session and shows its note.
     */
    private val reminders = mutableListOf(
        buildJsonObject {
            put("id", JsonPrimitive("sample-reminder-1"))
            put("session_id", JsonPrimitive("sample-session-2"))
            put("at", JsonPrimitive(Instant.now().minusSeconds(60).toString()))
            put("note", JsonPrimitive("Send the outline to the reviewer"))
            put("repeat", JsonPrimitive("none"))
            put("timezone", JsonNull)
            put("state", JsonPrimitive("fired"))
        },
    )

    private fun reminderRequest(method: String, path: String, body: JsonElement?): RelayResponse? {
        if (!path.startsWith("/reminders")) return null
        val id = path.removePrefix("/reminders").trim('/')
        val fields = body as? JsonObject ?: JsonObject(emptyMap())
        // The computer keeps the time in UTC.
        val at = (fields["at"] as? JsonPrimitive)?.content?.let { java.time.OffsetDateTime.parse(it).toInstant().toString() }
        return synchronized(reminders) {
            val index = reminders.indexOfFirst { it["id"]?.jsonPrimitive?.content == id }
            when {
                method == "GET" && id.isEmpty() -> ok { put("reminders", JsonArray(reminders.toList())) }
                method == "POST" && id.isEmpty() -> {
                    val made = JsonObject(
                        mapOf("id" to JsonPrimitive("sample-reminder-${++asked}"), "note" to JsonNull, "repeat" to JsonPrimitive("none"), "timezone" to JsonNull) +
                            fields + mapOf("at" to JsonPrimitive(at.orEmpty()), "state" to JsonPrimitive("scheduled")),
                    )
                    reminders += made
                    RelayResponse(201, made)
                }
                index < 0 -> error(404, "reminder_not_found")
                method == "DELETE" -> {
                    reminders.removeAt(index)
                    ok { put("deleted", JsonPrimitive(true)) }
                }
                method == "PATCH" -> {
                    val changed = JsonObject(reminders[index] + fields + (if (at != null) mapOf("at" to JsonPrimitive(at), "state" to JsonPrimitive("scheduled")) else emptyMap()))
                    reminders[index] = changed
                    RelayResponse(200, changed)
                }
                else -> null
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
                    for ((index, exchange) in session.exchanges.subList(start, end).withIndex()) {
                        val (user, assistant) = exchange
                        add(
                            buildJsonObject {
                                put("user", JsonPrimitive(user))
                                put("assistant", JsonPrimitive(assistant))
                                put(
                                    "mcp_servers",
                                    buildJsonArray {
                                        session.servers[start + index].orEmpty().forEach { add(usedServer(it)) }
                                    },
                                )
                                put("complete", JsonPrimitive(assistant.isNotEmpty() || !session.running))
                            },
                        )
                    }
                },
            )
            put("older", if (start > 0) JsonPrimitive("p$start") else JsonNull)
        }
    }

    fun events(method: String, path: String, body: JsonElement?): Flow<AnswerEvent> {
        if (method != "GET") return answer(path, body)
        val id = Regex("^/sessions/([^/]+)/live$").find(path)?.groupValues?.get(1)
        return if (id != null) follow(id) else flow { emit(AnswerEvent.Done(error(404, "not_found"))) }
    }

    /**
     * `GET /sessions/{id}/live` (#154): what the run the computer makes by itself has said so
     * far, then the rest as it is written, to its end. Only the answers to waiting messages
     * can be followed here; an answer the app itself receives ends with the app's listening.
     */
    private fun follow(id: String): Flow<AnswerEvent> = flow {
        val session = session(id)
        val run = session.live
        emit(
            AnswerEvent.Stream(
                "live",
                buildJsonObject {
                    put("session_id", JsonPrimitive(id))
                    put("running", JsonPrimitive(session.running))
                    put("followable", JsonPrimitive(run != null))
                    put("queue_id", run?.queueId?.let(::JsonPrimitive) ?: JsonNull)
                },
            ),
        )
        var sent = 0
        var first = true
        while (run != null) {
            val end = run.end
            val text = synchronized(run) { run.text.toString() }
            if (text.length > sent) {
                emit(AnswerEvent.Stream("delta", buildJsonObject { put("text", JsonPrimitive(text.substring(sent))) }))
                sent = text.length
            }
            // What the run had said before is over; from here on it is written as it comes (#157).
            if (first) emit(AnswerEvent.Stream("replay_end", buildJsonObject { put("events", JsonPrimitive(if (sent > 0) 1 else 0)) }))
            first = false
            if (end == "done") {
                emit(AnswerEvent.Stream("done", buildJsonObject { put("answer", JsonPrimitive(text)); put("session_id", JsonPrimitive(id)) }))
            } else if (end != null) {
                emit(AnswerEvent.Stream("error", buildJsonObject { put("error", JsonPrimitive(end)) }))
            }
            if (end != null) break
            delay(250)
        }
        if (run == null) emit(AnswerEvent.Stream("replay_end", buildJsonObject { put("events", JsonPrimitive(0)) }))
        emit(AnswerEvent.Done(RelayResponse(200, null)))
    }

    private fun answer(path: String, body: JsonElement?): Flow<AnswerEvent> = flow {
        val request = body as? JsonObject
        val text = (request?.get("prompt") as? JsonPrimitive)?.content.orEmpty()
        val prompt = text.lowercase()
        val toCoach = path == "/coach/chat/messages"
        // A new conversation runs under the id the phone chose (#148).
        val chosen = (request?.get("new_session_id") as? JsonPrimitive)?.content
        var id = Regex("^/sessions/([^/]+)/").find(path)?.groupValues?.get(1) ?: if (toCoach) coachId else chosen ?: NEW
        if ("offline" in prompt) throw ApiException(ApiException.Kind.NOT_SENT)
        if (chosen != null && sessions[chosen]?.exchanges?.firstOrNull()?.first == text) {
            // The same first message again: nothing is run a second time.
            emit(
                AnswerEvent.Done(
                    ok {
                        put("repeat", JsonPrimitive(true))
                        put("session_id", JsonPrimitive(chosen))
                        put("running", JsonPrimitive(session(chosen).running))
                    },
                ),
            )
            return@flow
        }
        if (toCoach) {
            if (!session(id).running && session(id).exchanges.isNotEmpty() && (coachDue() || "tidy" in prompt)) {
                // The conversation had grown long (#156): keep-alives while it is tidied up (that run
                // is never stopped), then what was done, then the message in a new session.
                coachTidying = id
                try {
                    repeat(3) {
                        delay(3000)
                        emit(AnswerEvent.Stream("keepalive", JsonObject(emptyMap())))
                    }
                } finally {
                    coachTidying = null
                }
                renewCoach()
                id = coachId
                emit(
                    AnswerEvent.Stream(
                        "compacted",
                        buildJsonObject {
                            put("facts", buildJsonArray { add(JsonPrimitive("profile/work.md")); add(JsonPrimitive("projects/project-alpha.md")) })
                            put("summary", JsonPrimitive(true))
                            put("session_id", JsonPrimitive(id))
                        },
                    ),
                )
            }
            coachRuns++
        }
        // "root off": blank chats are switched off on the computer (#163); only a new blank chat is refused.
        if ("root off" in prompt && chosen != null && request?.get("folder") == null) {
            emit(AnswerEvent.Done(RelayResponse(503, buildJsonObject { put("error", JsonPrimitive("chat_root_unusable")); put("message", JsonPrimitive("blank chats are switched off: the chat folder holds CLAUDE.md; it must hold no instruction or settings file")) })))
            return@flow
        }
        val session = session(id)
        // A new conversation belongs to a folder (#163): its own one under the chat root, unless one was named.
        if (chosen != null && session.place == null) session.place = placeOf((request?.get("folder") as? JsonPrimitive)?.content)
        if ("in use" in prompt) {
            emit(AnswerEvent.Done(error(409, "session_in_use")))
            return@flow
        }
        // "mcp unchecked": the folder has approved MCP servers the computer has not checked yet (#153).
        if ("mcp" in prompt && "unchecked" in prompt) {
            emit(AnswerEvent.Done(error(503, "mcp_unavailable")))
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
        if ("lost" in prompt && chosen != null) {
            // The first message arrives and is answered, but nothing of it reaches the phone.
            scope.launch {
                delay(8000)
                synchronized(session) {
                    session.exchanges[session.exchanges.lastIndex] = text to "This answer was written while the phone heard nothing."
                    session.running = false
                    drain(session)
                }
            }
            throw ApiException(ApiException.Kind.UNCERTAIN)
        }
        var written = ""
        // The line that offers a file stays in the conversation the computer keeps.
        var marker = ""
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
                "install" in prompt -> "A skill for tidying notes would help here. I have proposed it below; nothing is installed unless you say so."
                // Moving between conversations by asking (#164): "jump" opens one, "pick" offers
                // three, "begin" starts one in the project folder. "move": a blank chat's files (#163).
                "jump" in prompt -> "Opening the conversation about what is open this week."
                "pick" in prompt -> "Three conversations could be meant. Which one?"
                "begin" in prompt -> "Starting a new conversation in project-alpha."
                "move" in prompt -> "The packing list and the budget can go into the project's notes. I have proposed it below."
                else -> data.getValue("answer").jsonPrimitive.content
            }
            // The line the computer takes out of the answer at its end: while it is written it shows in the stream.
            val line = when {
                "jump" in prompt -> "[[switch: ${if (id == "sample-session-2") "sample-session-3" else "sample-session-2"}]]"
                "pick" in prompt -> "[[switch: sample-session-2, sample-session-7, sample-session-3]]"
                "begin" in prompt -> "[[switch-new: project-alpha]]"
                "move" in prompt -> "[[move-files: project-alpha/notes | packing-list.md | data/budget.csv]]"
                else -> null
            }
            val step = if ("slow" in prompt) 2000L else 90L
            for ((index, piece) in answer.chunked(18).withIndex()) {
                // A run that says nothing for a while sends a sign of life.
                if ("slow" in prompt) emit(AnswerEvent.Stream("keepalive", JsonObject(emptyMap())))
                if (pause(session, step)) return@flow stopped()
                written += piece
                emit(AnswerEvent.Stream("delta", buildJsonObject { put("text", JsonPrimitive(piece)) }))
                if (index == 2 && "fail" in prompt) {
                    emit(AnswerEvent.Stream("error", buildJsonObject { put("error", JsonPrimitive("claude_failed")) }))
                    emit(AnswerEvent.Done(RelayResponse(200, null)))
                    return@flow
                }
            }
            if (line != null) {
                // In two pieces, as a stream may cut it: the app shows neither.
                for (piece in listOf("\n" + line.take(6), line.drop(6))) {
                    if (pause(session, 600)) return@flow stopped()
                    emit(AnswerEvent.Stream("delta", buildJsonObject { put("text", JsonPrimitive(piece)) }))
                }
                // The history keeps a move's line (the app hides it there too), never a switch's.
                if ("move" in prompt) marker = "\n$line"
            }
            emit(
                AnswerEvent.Stream(
                    "done",
                    buildJsonObject {
                        put("answer", JsonPrimitive(answer))
                        put("session_id", JsonPrimitive(id))
                        // "file": one file is offered (the 20-byte sample file) and one is refused.
                        if ("file" in prompt) {
                            val offer = "sample-offer-${++asked}"
                            synchronized(session) { session.offers += Triple(offer, "outline.md", Instant.now().plusSeconds(15 * 60)) }
                            marker = "\n[[file: notes/alpha/outline.md]]"
                            put("files", Json.parseToJsonElement("""[{"offer":"$offer","name":"outline.md","type":"text/markdown","size":20}]"""))
                            put("files_refused", Json.parseToJsonElement("""[{"name":"recordings.zip","reason":"larger than the limit of 50 MB"}]"""))
                        }
                        // "mcp": the answer used the approved MCP servers (#153), each with its scope (#162).
                        val used = mcpState.filterValues { it == "approved" }.keys.sorted()
                        if ("mcp" in prompt && used.isNotEmpty()) {
                            synchronized(session) { session.servers[session.exchanges.lastIndex] = used }
                            put("mcp_servers", buildJsonArray { used.forEach { add(usedServer(it)) } })
                            put("mcp_note", JsonPrimitive("An approved MCP server runs outside the sandbox."))
                        }
                        // "install": the answer proposes a skill (#97). With "plain" its source is not
                        // HTTPS and is refused at once; with "broken" the download fails.
                        if ("install" in prompt) {
                            val card = if ("plain" in prompt) {
                                Json.parseToJsonElement("""{"kind":"skill_install","source":"http://skills.example.org/tidy-notes.zip","state":"refused","error":"only HTTPS sources can be installed","error_code":"source_not_https"}""")
                            } else {
                                val cardId = "samplecard%03d".format(++asked)
                                val proposal = Proposal(id, "https://skills.example.org/tidy-notes.zip", broken = "broken" in prompt, taken = "taken" in prompt)
                                proposals[cardId] = proposal
                                proposalCard(cardId, proposal)
                            }
                            put("skill_installs", JsonArray(listOf(card)))
                        }
                        // The switch of a fresh answer (#164): never part of the history.
                        when {
                            "jump" in prompt -> put("switch", buildJsonObject { put("action", JsonPrimitive("open")); put("sessions", buildJsonArray { add(switchSession(if (id == "sample-session-2") "sample-session-3" else "sample-session-2")) }); put("folder", JsonNull) })
                            "pick" in prompt -> put("switch", buildJsonObject { put("action", JsonPrimitive("choose")); put("sessions", buildJsonArray { listOf("sample-session-2", "sample-session-7", "sample-session-3").filter { it != id }.forEach { add(switchSession(it)) } }); put("folder", JsonNull) })
                            "begin" in prompt -> put(
                                "switch",
                                buildJsonObject {
                                    put("action", JsonPrimitive("new"))
                                    put("sessions", JsonArray(emptyList()))
                                    put("folder", Json.parseToJsonElement("""{"path":"/home/owner/project-alpha","label":"project-alpha","kind":"project","colour":3}"""))
                                },
                            )
                        }
                        // "move": a card that waits for the owner, and one the computer refused at once.
                        // With "taken" the first yes finds a name taken; with "broken" the move breaks off.
                        if ("move" in prompt) {
                            val cardId = "samplemove%03d".format(++asked)
                            val folder = session.place?.get("folder")?.jsonPrimitive?.content ?: "/home/owner/LifeOS-Chats/2027-01-15-a1b2c3"
                            val move = MoveProposal(id, folder, taken = "taken" in prompt, broken = "broken" in prompt)
                            moves[cardId] = move
                            val refused = Json.parseToJsonElement("""{"kind":"move_files","state":"refused","target":"brain","error":"the memory folder is not a target","error_code":"target_not_allowed"}""")
                            put("file_moves", JsonArray(listOf(moveCard(cardId, move), refused)))
                        }
                        // "apply": a request to apply the chat's changes (#180), and one refused at once;
                        // with "changed" the first yes finds the folder at another commit.
                        if ("apply" in prompt) {
                            val cardId = "sampleupd%03d".format(++asked)
                            val ask = UpdateAsk(id, changed = "changed" in prompt)
                            updateAsks[cardId] = ask
                            val refused = Json.parseToJsonElement("""{"kind":"apply_update","state":"refused","error":"an update is being applied already","error_code":"update_in_progress"}""")
                            put("update_requests", JsonArray(listOf(updateCard(cardId, ask), refused)))
                        }
                    },
                ),
            )
            emit(AnswerEvent.Done(RelayResponse(200, null)))
        } finally {
            synchronized(session) {
                session.exchanges[session.exchanges.lastIndex] = text to written + marker
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

    /**
     * The next waiting message, sent by the computer itself as the bridge does: its answer is
     * written in the background and can be followed (`/live`).
     */
    private fun drain(session: Session) {
        if (session.running || session.paused != null || session.queue.isEmpty()) return
        val (queueId, text) = session.queue.removeAt(0)
        session.running = true
        session.stop = CompletableDeferred()
        session.exchanges += text to ""
        val run = LiveRun(queueId)
        session.live = run
        scope.launch {
            val answer = "Answer to the waiting message “$text”. The computer sent it by itself when the run before it had ended, and the phone followed it as it was written."
            var stopped = pause(session, 1500)
            for (piece in answer.chunked(14)) {
                if (stopped) break
                synchronized(run) { run.text.append(piece) }
                stopped = pause(session, 450)
            }
            synchronized(session) {
                session.exchanges[session.exchanges.lastIndex] = text to synchronized(run) { run.text.toString() }
                session.running = false
                run.end = if (stopped) "cancelled" else "done"
                session.live = null
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
        val reply = try {
            withTimeoutOrNull(waitSeconds * 1000) { answer.await() }
        } finally {
            waiting.remove(id)
        }
        // The computer no longer waits for the card.
        emit(
            AnswerEvent.Stream(
                "interaction_closed",
                buildJsonObject {
                    put("id", JsonPrimitive(id))
                    put("reason", JsonPrimitive(if (reply == null) "timed_out" else "answered"))
                },
            ),
        )
        return reply
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
        const val PARKED = "sample-session-4"
        const val PARKED_CARD = "sample-parked-1"
        const val COACH_FULL = 5
        const val PAGE = 4

        /** How long a proposed skill counts as being downloaded. */
        const val FETCH_MILLIS = 6_000L
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
