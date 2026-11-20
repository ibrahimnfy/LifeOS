package dev.lifeos.app.data

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UiStateTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val empty: (List<Int>) -> Boolean = { it.isEmpty() }
    private fun failure(kind: ApiException.Kind, status: Int = 0) = Outcome.Failure(ApiException(kind, status))

    @Test
    fun `not paired wins over everything`() {
        assertEquals(UiState.NotPaired, UiStates.of(false, listOf(1) to 5L, Outcome.Success(listOf(2), 9), empty))
    }

    @Test
    fun `while the first request runs the screen loads, or shows its saved copy`() {
        assertEquals(UiState.Loading, UiStates.of(true, null, null, empty))
        assertEquals(UiState.Content(listOf(1), fromCache = true, savedAtMillis = 5, refreshing = true), UiStates.of(true, listOf(1) to 5L, null, empty))
        assertEquals(UiState.Empty(fromCache = true, savedAtMillis = 5, refreshing = true), UiStates.of(true, emptyList<Int>() to 5L, null, empty))
    }

    @Test
    fun `a fresh answer replaces the saved copy`() {
        assertEquals(UiState.Content(listOf(2), fromCache = false, savedAtMillis = 9), UiStates.of(true, listOf(1) to 5L, Outcome.Success(listOf(2), 9), empty))
        assertEquals(UiState.Empty(fromCache = false, savedAtMillis = 9), UiStates.of(true, listOf(1) to 5L, Outcome.Success(emptyList(), 9), empty))
    }

    @Test
    fun `when the computer cannot be reached the saved copy stays, marked with its age`() {
        for (kind in listOf(ApiException.Kind.NOT_SENT, ApiException.Kind.UNCERTAIN, ApiException.Kind.HTTP)) {
            assertEquals(UiState.Content(listOf(1), fromCache = true, savedAtMillis = 5), UiStates.of(true, listOf(1) to 5L, failure(kind, 500), empty))
        }
    }

    @Test
    fun `without a saved copy a failure is an error with its kind`() {
        assertEquals(UiState.Error(Problem.UNREACHABLE), UiStates.of(true, null, failure(ApiException.Kind.NOT_SENT), empty))
        assertEquals(UiState.Error(Problem.NO_ANSWER), UiStates.of(true, null, failure(ApiException.Kind.UNCERTAIN), empty))
        assertEquals(UiState.Error(Problem.SERVER, 500), UiStates.of(true, null, failure(ApiException.Kind.HTTP, 500), empty))
        assertEquals(UiState.Error(Problem.NOT_SET_UP, 503), UiStates.of(true, null, failure(ApiException.Kind.HTTP, 503), empty))
    }

    @Test
    fun `a folder inside another repository has its own message`() {
        val refused = Outcome.Failure(ApiException(ApiException.Kind.HTTP, 403, UiStates.FOLDER_IN_REPOSITORY))
        assertEquals(UiState.Error(Problem.FOLDER_IN_REPOSITORY, 403), UiStates.of(true, null, refused, empty))
        val other = Outcome.Failure(ApiException(ApiException.Kind.HTTP, 403, "folder_not_allowed"))
        assertEquals(UiState.Error(Problem.SERVER, 403), UiStates.of(true, null, other, empty))
    }

    @Test
    fun `a refusal by the relay means not paired, even with a saved copy`() {
        assertEquals(UiState.NotPaired, UiStates.of(true, listOf(1) to 5L, failure(ApiException.Kind.REFUSED), empty))
        assertEquals(UiState.NotPaired, UiStates.of(true, null, failure(ApiException.Kind.NOT_PAIRED), empty))
    }

    @Test
    fun `the saved copies keep the last answer per key with its time and are wiped together`() {
        val cache = ScreenCache(File(folder.root, "cache"))
        assertNull(cache.get("tasks"))
        cache.put("tasks", JsonPrimitive("first"), 100)
        cache.put("tasks", JsonPrimitive("second"), 200)
        cache.put("course-5/../x", JsonPrimitive("other"), 300)
        assertEquals(JsonPrimitive("second"), cache.get("tasks")!!.raw)
        assertEquals(200L, cache.get("tasks")!!.savedAtMillis)
        assertEquals(JsonPrimitive("other"), cache.get("course-5/../x")!!.raw)
        assertEquals(2, File(folder.root, "cache").list()!!.size) // nothing escapes the folder
        File(folder.root, "cache/tasks.json").writeText("{broken")
        assertNull(cache.get("tasks"))
        cache.wipe()
        assertNull(cache.get("course-5/../x"))
        assertEquals(false, File(folder.root, "cache").exists())
    }
}
