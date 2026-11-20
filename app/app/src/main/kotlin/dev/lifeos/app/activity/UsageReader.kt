package dev.lifeos.app.activity

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.lifeos.app.LifeOsApplication
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * Screen time and per-app use from the system's usage events (the "usage access" the owner
 * grants in the system settings). Nothing is read without that access, and what is read stays
 * in [ActivityStore] on the phone until it is sent to the owner's computer.
 */
class UsageReader(private val context: Context) {
    private val packages: PackageManager = context.packageManager
    private val labels = HashMap<String, String>()

    /** The last [days] local days up to today; days the system has no events for are left out. */
    fun measure(days: Int = UsageView.MEASURE_DAYS, nowMillis: Long = System.currentTimeMillis()): List<DayUsage> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val first = today.minusDays((days - 1).toLong())
        // Some hours before the first day, so an app already open at midnight is counted from midnight.
        val from = UsageMath.dayBounds(first, zone).first - LOOKBACK_MILLIS
        val events = events(from, nowMillis)
        val hidden = homeScreens() + context.packageName
        return (0 until days).mapNotNull { offset ->
            val day = first.plusDays(offset.toLong())
            val (start, end) = UsageMath.dayBounds(day, zone)
            val raw = UsageMath.measure(events, start, end, nowMillis)
            if (!UsageView.hasData(raw)) null else UsageMath.summarize(day, raw, hidden, ::label, complete = day < today)
        }
    }

    /**
     * Guesses of the last [days] nights from when the phone lay unused ([SleepEstimate]); a
     * morning without a plausible quiet stretch has no guess.
     */
    fun nights(days: Int = UsageView.MEASURE_DAYS, nowMillis: Long = System.currentTimeMillis()): List<Night> {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val first = today.minusDays((days - 1).toLong())
        // From noon before the first morning: a night starts the evening before.
        val from = UsageMath.dayBounds(first.minusDays(1), zone).first + 12 * 60 * 60_000L
        val events = events(from, nowMillis)
        return (0 until days).mapNotNull { offset -> SleepEstimate.night(events, first.plusDays(offset.toLong()), zone) }
    }

    private fun events(from: Long, to: Long): List<RawEvent> {
        val manager = context.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val found = ArrayList<RawEvent>()
        val result = manager.queryEvents(from, to) ?: return emptyList()
        val event = UsageEvents.Event()
        while (result.hasNextEvent()) {
            result.getNextEvent(event)
            if (event.eventType in KEPT) found += RawEvent(event.timeStamp, event.eventType, event.packageName)
        }
        return found
    }

    /** The home screen apps: time on them is screen time, but they are not "apps used". */
    private fun homeScreens(): Set<String> = try {
        packages.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .mapNotNull { it.activityInfo?.packageName }
            .toSet()
    } catch (_: Exception) {
        emptySet()
    }

    /** The name an app shows under its icon; apps without a launcher icon keep their package name. */
    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        try {
            packages.getApplicationLabel(packages.getApplicationInfo(pkg, 0)).toString().ifBlank { pkg }
        } catch (_: Exception) {
            pkg
        }
    }

    companion object {
        private const val LOOKBACK_MILLIS = 12 * 60 * 60_000L
        private val KEPT = setOf(RawEvent.RESUMED, RawEvent.PAUSED, RawEvent.SCREEN_OFF, RawEvent.UNLOCKED, RawEvent.SHUTDOWN)

        /** Whether the owner granted usage access to this app. */
        fun hasAccess(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java) ?: return false
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
            return if (mode == AppOpsManager.MODE_DEFAULT) {
                context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED
            } else {
                mode == AppOpsManager.MODE_ALLOWED
            }
        }

        /** The system screen where usage access is granted: this app's own page where the phone has one. */
        fun settingsIntents(context: Context): List<Intent> = listOf(
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
        )
    }
}

/**
 * Once a day, in the background: measure the last days into [ActivityStore], so nothing is
 * lost when the app is not opened for a while (the system forgets its events after about a
 * week). It does not send anything: sending needs the device secret, which is available only
 * while the app is unlocked, so the computer gets the days the next time the app connects.
 */
class UsageWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val app = applicationContext as LifeOsApplication
        if (UsageReader.hasAccess(app)) {
            val today = LocalDate.now(ZoneId.systemDefault())
            val reader = UsageReader(app)
            app.activityStore.putDays(reader.measure(), today)
            reader.nights().forEach { app.activityStore.putEstimate(it, today) }
        }
        return Result.success()
    }

    companion object {
        private const val NAME = "usage-measure"

        /** Make sure the daily measurement is planned (once; planning it again changes nothing). */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
