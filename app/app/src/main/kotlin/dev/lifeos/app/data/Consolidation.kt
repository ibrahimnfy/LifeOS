package dev.lifeos.app.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One change the nightly consolidation proposes to the memory (#150, #151). Nothing is written
 * until the owner approves it.
 */
@Serializable
data class MemoryOperation(
    val id: String,
    /** `file_capture`, `merge_facts` or `add_fact`. */
    val op: String = "",
    /** The topic file it changes, relative to the memory folder. */
    val file: String = "",
    val why: String? = null,
    /** `pending`, `applied`, `rejected` or `failed`. */
    val status: String = MemoryOperations.PENDING,
    val reason: String? = null,
    // file_capture: the inbox capture that is filed
    val capture: String? = null,
    @SerialName("capture_text") val captureText: String? = null,
    @SerialName("capture_at") val captureAt: String? = null,
    // merge_facts: the fact that stays and the one it replaces
    val keep: String? = null,
    val drop: String? = null,
    // add_fact
    val text: String? = null,
) {
    val pending: Boolean get() = status == MemoryOperations.PENDING
}

@Serializable
data class MemoryProposal(
    val id: String,
    @SerialName("created_at") val createdAt: String? = null,
    val operations: List<MemoryOperation> = emptyList(),
)

/** `GET /memory/consolidation` (and the answers of approve and reject, which add their lists). */
@Serializable
data class ConsolidationView(
    val proposal: MemoryProposal? = null,
    val waiting: Int = 0,
    val running: Boolean = false,
    @SerialName("last_run") val lastRun: String? = null,
    @SerialName("last_result") val lastResult: String? = null,
    val applied: List<String> = emptyList(),
    val failed: List<String> = emptyList(),
    val rejected: List<String> = emptyList(),
    val committed: Boolean? = null,
)

object MemoryOperations {
    const val PENDING = "pending"
    const val APPLIED = "applied"
    const val REJECTED = "rejected"
    const val FAILED = "failed"

    /** The operations still waiting for the owner. */
    fun pending(view: ConsolidationView?): List<MemoryOperation> = view?.proposal?.operations.orEmpty().filter { it.pending }

    /** Whether Today shows the card: a proposal with something still to decide. */
    fun waits(view: ConsolidationView?): Boolean = pending(view).isNotEmpty()

    /** The body of approve or reject: the named operations, or all that wait. */
    fun body(ids: List<String>?): JsonObject = buildJsonObject {
        if (ids == null) put("all", true) else put("operations", JsonArray(ids.map { JsonPrimitive(it) }))
    }
}

object ConsolidationEndpoints {
    val view = Endpoint("memory-consolidation", "/memory/consolidation", serializer = ConsolidationView.serializer())
}

/** Approve [ids] (null: all that wait); the changes are written in one commit on the computer. */
suspend fun Api.approveMemory(ids: List<String>?): ConsolidationView =
    BridgeJson.decodeFromJsonElement(
        ConsolidationView.serializer(),
        call("POST", "/memory/consolidation/approve", body = MemoryOperations.body(ids), timeoutMillis = Api.WRITE_TIMEOUT_MILLIS),
    )

/** Reject [ids] (null: all that wait); rejected changes are not proposed again unchanged. */
suspend fun Api.rejectMemory(ids: List<String>?): ConsolidationView =
    BridgeJson.decodeFromJsonElement(
        ConsolidationView.serializer(),
        call("POST", "/memory/consolidation/reject", body = MemoryOperations.body(ids), timeoutMillis = Api.WRITE_TIMEOUT_MILLIS),
    )

/** Ask for a proposal now (one run in the background on the computer). */
suspend fun Api.runConsolidation() {
    call("POST", "/memory/consolidation/run", timeoutMillis = Api.WRITE_TIMEOUT_MILLIS)
}
