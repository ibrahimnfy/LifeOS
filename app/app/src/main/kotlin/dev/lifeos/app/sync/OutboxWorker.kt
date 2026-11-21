package dev.lifeos.app.sync

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.lifeos.app.LifeOsApplication
import dev.lifeos.app.data.OutboxItem
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The outbox when the app is not in front (#165): planned whenever something waits while the
 * computer cannot be reached, with "connected network" as its condition, so Android starts it
 * (and the app's process, if it was stopped) as soon as the phone is online again.
 *
 * It sends through the app's one core, the same way as the network callback does, so nothing is
 * sent twice. It never starts the core and never unlocks: a real pairing's secret needs the
 * owner's fingerprint or screen lock, so when the core is not running (the process was
 * restarted) or the app is locked, it ends at once and what waits is sent right after the next
 * unlock.
 */
class OutboxWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val core = (applicationContext as LifeOsApplication).runningCore() ?: return Result.success()
        if (core.pairing.value == null || core.locked.value) return Result.success()
        if (core.outbox.items.value.none(::waits)) return Result.success()
        core.syncNow()
        withTimeoutOrNull(WAIT_MILLIS) { core.outbox.items.first { items -> items.none(::waits) } }
        // No retry with back-off: the next network change, the next unlock or the app's own
        // check every minute sends what is still waiting.
        return Result.success()
    }

    companion object {
        private const val NAME = "outbox-send"

        /** Long enough for a few checks of the computer and the sending itself. */
        private const val WAIT_MILLIS = 90_000L

        private fun waits(item: OutboxItem): Boolean = item.state == OutboxItem.State.WAITING

        /** Plan one run for when the network is there (a run already planned is kept). */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .apply {
                    // Before Android 12, expedited work would need a notification of its own.
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    }
                }
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
