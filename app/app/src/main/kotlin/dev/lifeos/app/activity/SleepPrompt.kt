package dev.lifeos.app.activity

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.lifeos.app.LifeOsApplication
import dev.lifeos.app.MainActivity
import dev.lifeos.app.R
import dev.lifeos.app.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The silent morning question ("How did you sleep?"). One inexact alarm on the phone at the
 * chosen time (default 05:00): no polling, no service that keeps running, nothing from the
 * computer. When it rings and last night has not been entered, one silent notification is
 * posted; then the next morning is planned. It is planned again after a restart of the phone
 * and after a change of the clock or the time zone.
 */
object SleepPrompt {
    const val ACTION_ASK = "dev.lifeos.app.action.SLEEP_PROMPT"

    /** Opens the Sleep tab (see MainActivity). */
    const val ACTION_OPEN_SLEEP = "dev.lifeos.app.action.OPEN_SLEEP"

    private const val CHANNEL = "morning"
    private const val NOTIFICATION_ID = 5

    /** Plan the next question, or remove the plan when the owner switched it off. */
    suspend fun schedule(context: Context) {
        val settings = SettingsStore(context)
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = alarmIntent(context)
        alarms.cancel(pending)
        if (!settings.sleepPrompt.first()) return
        val time = SleepPromptTime.parse(settings.sleepPromptTime.first())
        val next = SleepPromptTime.next(ZonedDateTime.now(ZoneId.systemDefault()), time)
        // Inexact on purpose: the phone may bundle it with other work, which saves battery.
        alarms.set(AlarmManager.RTC_WAKEUP, next.toInstant().toEpochMilli(), pending)
    }

    /** Whether the phone lets the app post notifications (Android 13 asks the owner). */
    fun mayNotify(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** The question itself, if last night is still missing. Generic text only: no data in it. */
    fun askIfMissing(context: Context) {
        val app = context.applicationContext as LifeOsApplication
        if (!SleepView.needsEntry(app.activityStore.nights(), LocalDate.now(ZoneId.systemDefault()))) return
        if (!mayNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.sleep_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.sleep_channel_text)
                setSound(null, null)
                enableVibration(false)
            },
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_SLEEP).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sleep)
            .setContentTitle(context.getString(R.string.sleep_prompt_title))
            .setContentText(context.getString(R.string.sleep_prompt_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // The permission was taken back in the meantime: nothing to do.
        }
    }

    /** The question is answered: take it away. */
    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, SleepPromptReceiver::class.java).setAction(ACTION_ASK),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** The alarm rang, the phone restarted, or the clock changed. Not exported: only the system and the app reach it. */
class SleepPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val done = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                if (intent.action == SleepPrompt.ACTION_ASK) SleepPrompt.askIfMissing(context)
                SleepPrompt.schedule(context)
            } finally {
                done.finish()
            }
        }
    }
}
