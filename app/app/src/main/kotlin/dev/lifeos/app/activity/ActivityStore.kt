package dev.lifeos.app.activity

import dev.lifeos.app.data.Api
import dev.lifeos.app.data.ApiException
import dev.lifeos.app.data.BridgeJson
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDate

@Serializable
private data class Stored(val days: List<DayUsage> = emptyList(), val nights: List<Night> = emptyList(), val lastSyncMillis: Long = 0)

/**
 * Screen time and sleep per day on the phone, so the charts work offline. One small JSON file
 * in the app's private files (about a kilobyte per day; 400 days are kept), replaced
 * atomically. A plain file was chosen over a database: the data is one short list that is
 * always read as a whole, and the app has no other use for a database yet.
 */
class ActivityStore(private val file: File) {
    private var stored: Stored = try {
        BridgeJson.decodeFromString(Stored.serializer(), file.readText())
    } catch (_: Exception) {
        Stored()
    }

    @Synchronized
    fun days(): List<DayUsage> = stored.days

    @Synchronized
    fun nights(): List<Night> = stored.nights

    @Synchronized
    fun lastSyncMillis(): Long = stored.lastSyncMillis

    /**
     * Take new measurements. A day whose numbers did not change keeps its "uploaded" mark;
     * a completed day is never replaced by a later, emptier measurement (the system forgets
     * old events after some days).
     */
    @Synchronized
    fun putDays(measured: List<DayUsage>, today: LocalDate) {
        val byDate = stored.days.associateBy { it.date }.toMutableMap()
        for (day in measured) {
            val old = byDate[day.date]
            byDate[day.date] = when {
                old == null -> day
                old.complete && day.screenMinutes < old.screenMinutes -> old
                old.screenMinutes == day.screenMinutes && old.unlocks == day.unlocks && old.apps == day.apps && old.complete == day.complete -> old
                else -> day
            }
        }
        save(stored.copy(days = prune(byDate.values.sortedBy { it.date }, today) { it.date }))
    }

    /** An estimate never replaces what the owner entered, and an unchanged estimate keeps its mark. */
    @Synchronized
    fun putEstimate(night: Night, today: LocalDate) {
        val old = stored.nights.firstOrNull { it.date == night.date }
        if (old != null && (!old.estimated || (old.bed == night.bed && old.wake == night.wake))) return
        putNight(night.copy(estimated = true, uploaded = false), today)
    }

    /** The owner's own entry (or correction) for a night. It is uploaded again. */
    @Synchronized
    fun putManual(date: String, bed: String, wake: String, today: LocalDate) =
        putNight(Night(date, bed, wake, estimated = false, uploaded = false), today)

    private fun putNight(night: Night, today: LocalDate) {
        val nights = (stored.nights.filterNot { it.date == night.date } + night).sortedBy { it.date }
        save(stored.copy(nights = prune(nights, today) { it.date }))
    }

    @Synchronized
    fun markDayUploaded(date: String) = save(stored.copy(days = stored.days.map { if (it.date == date) it.copy(uploaded = true) else it }))

    @Synchronized
    fun markNightUploaded(night: Night) = save(
        // Only if it is still the same entry: it may have been corrected meanwhile.
        stored.copy(nights = stored.nights.map { if (it == night) it.copy(uploaded = true) else it }),
    )

    @Synchronized
    fun markSynced(nowMillis: Long) = save(stored.copy(lastSyncMillis = nowMillis))

    @Synchronized
    fun wipe() {
        stored = Stored()
        file.delete()
    }

    private fun <T> prune(items: List<T>, today: LocalDate, date: (T) -> String): List<T> {
        val oldest = today.minusDays(RETENTION_DAYS).toString()
        return items.filter { date(it) >= oldest }
    }

    private fun save(next: Stored) {
        stored = next
        try {
            file.parentFile?.mkdirs()
            val temporary = File(file.parentFile, file.name + ".new")
            FileOutputStream(temporary).use { it.write(BridgeJson.encodeToString(Stored.serializer(), next).toByteArray()) }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: IOException) {
        }
    }

    companion object {
        const val RETENTION_DAYS = 400L
    }
}

/** Sends what the computer does not have yet. Uploading a day twice is harmless: the same day replaces. */
object ActivitySync {
    const val MAX_APPS = 20

    /**
     * Upload every completed day and every night that is not confirmed yet, oldest first.
     * Returns how many were confirmed. Stops at the first sign that the computer cannot be
     * reached; a day the computer refuses is skipped and tried again next time.
     */
    suspend fun upload(store: ActivityStore, api: Api, nowMillis: Long): Int {
        var done = 0
        try {
            for (day in store.days().filter { it.complete && !it.uploaded }) {
                if (send { api.call("POST", "/activity/usage", body = usageBody(day)) }) {
                    store.markDayUploaded(day.date)
                    done++
                }
            }
            for (night in store.nights().filter { !it.uploaded }) {
                if (send { api.call("POST", "/activity/sleep", body = sleepBody(night)) }) {
                    store.markNightUploaded(night)
                    done++
                }
            }
            store.markSynced(nowMillis)
        } catch (_: ApiException) {
            // Not reachable now: everything unconfirmed stays for the next time.
        }
        return done
    }

    private suspend fun send(call: suspend () -> Unit): Boolean = try {
        call()
        true
    } catch (error: ApiException) {
        if (error.kind != ApiException.Kind.HTTP) throw error
        false
    }

    fun usageBody(day: DayUsage) = buildJsonObject {
        put("date", JsonPrimitive(day.date))
        put("screen_minutes", JsonPrimitive(day.screenMinutes))
        put("unlocks", JsonPrimitive(day.unlocks))
        put(
            "apps",
            buildJsonArray {
                day.apps.take(MAX_APPS).forEach { app ->
                    add(
                        buildJsonObject {
                            put("label", JsonPrimitive(app.label))
                            put("minutes", JsonPrimitive(app.minutes))
                        },
                    )
                }
            },
        )
    }

    fun sleepBody(night: Night) = buildJsonObject {
        put("date", JsonPrimitive(night.date))
        put("bed", JsonPrimitive(night.bed))
        put("wake", JsonPrimitive(night.wake))
        put("estimated", JsonPrimitive(night.estimated))
    }
}
