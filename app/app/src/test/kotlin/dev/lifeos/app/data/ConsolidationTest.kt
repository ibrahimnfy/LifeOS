package dev.lifeos.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsolidationTest {
    private val answer = """
        {"proposal": {"id": "c-1", "created_at": "2026-01-01T03:00:00+00:00", "operations": [
           {"id": "op1", "op": "file_capture", "file": "topics/a.md", "why": "about a", "status": "pending",
            "capture": "i-1", "capture_text": "a note", "capture_at": "2026-01-01 08:15"},
           {"id": "op2", "op": "merge_facts", "file": "topics/b.md", "why": null, "status": "applied", "keep": "k", "drop": "d"},
           {"id": "op3", "op": "add_fact", "file": "topics/c.md", "why": "new", "status": "failed", "reason": "file changed", "text": "t"}]},
         "waiting": 1, "running": false, "last_run": "2026-01-01T03:00:00+00:00", "last_result": "ok",
         "applied": ["op2"], "failed": ["op3"], "committed": true}
    """

    @Test
    fun `the bridge's view and its operations are read`() {
        val view = BridgeJson.decodeFromString(ConsolidationView.serializer(), answer)
        val ops = view.proposal!!.operations
        assertEquals(listOf("op1", "op2", "op3"), ops.map { it.id })
        assertEquals("a note", ops[0].captureText)
        assertEquals("2026-01-01 08:15", ops[0].captureAt)
        assertEquals("k", ops[1].keep)
        assertEquals("d", ops[1].drop)
        assertNull(ops[1].why)
        assertEquals("file changed", ops[2].reason)
        assertEquals(listOf("op2"), view.applied)
        assertEquals(listOf("op3"), view.failed)
        assertEquals(true, view.committed)
    }

    @Test
    fun `only pending operations wait, and Today's card shows only while some do`() {
        val view = BridgeJson.decodeFromString(ConsolidationView.serializer(), answer)
        assertEquals(listOf("op1"), MemoryOperations.pending(view).map { it.id })
        assertTrue(MemoryOperations.waits(view))
        assertFalse(MemoryOperations.waits(BridgeJson.decodeFromString(ConsolidationView.serializer(), """{"proposal": null, "waiting": 0, "running": false}""")))
        assertFalse(MemoryOperations.waits(null))
    }

    @Test
    fun `approve and reject name the operations or all of them`() {
        assertEquals(JsonObject(mapOf("operations" to JsonArray(listOf(JsonPrimitive("op1"), JsonPrimitive("op3"))))), MemoryOperations.body(listOf("op1", "op3")))
        assertEquals(JsonObject(mapOf("all" to JsonPrimitive(true))), MemoryOperations.body(null))
    }
}
