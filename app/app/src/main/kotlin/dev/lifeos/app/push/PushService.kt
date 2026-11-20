package dev.lifeos.app.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.goterl.lazysodium.LazySodiumAndroid
import com.goterl.lazysodium.SodiumAndroid
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import dev.lifeos.app.BuildConfig
import dev.lifeos.app.LifeOsApplication
import dev.lifeos.app.MainActivity
import dev.lifeos.app.R
import dev.lifeos.app.crypto.E2e
import dev.lifeos.app.crypto.LazySodiumBackend

/**
 * Push through Firebase Cloud Messaging (docs/security.md 5.1): data messages only, also while
 * the app is closed. A push carries one sealed hint (`h`); it is opened here with the push key
 * alone, and the notification shows a fixed text for its kind of event, never content. Counts
 * and references stay inside the app (they decide what a tap opens).
 */
class PushService : FirebaseMessagingService() {
    private val e2e: E2e by lazy { E2e(LazySodiumBackend(LazySodiumAndroid(SodiumAndroid()))) }

    override fun onMessageReceived(message: RemoteMessage) {
        val app = application as LifeOsApplication
        // A bare wake-up or a hint: a running app asks the computer again either way.
        app.runningCore()?.onPush()
        val field = message.data["h"] ?: return
        val (key, device) = app.pushKeys.load() ?: return
        val hint = try {
            PushHints.open(e2e, key, device, field, app.pushKeys::acceptCounter)
        } finally {
            key.fill(0)
        }
        if (hint != null) PushNotifier.show(this, hint)
    }

    /** The push address changed: hand the new one to the computer (inside the sealed channel). */
    override fun onNewToken(token: String) {
        (application as LifeOsApplication).core.onPushToken(token)
    }
}

/** Firebase without its Gradle plugin: the four values are compiled in from the owner's private settings file. */
object PushSetup {
    /** Whether this build can receive push at all. */
    val available: Boolean
        get() = BuildConfig.FIREBASE_APP_ID.isNotEmpty() && BuildConfig.FIREBASE_API_KEY.isNotEmpty() &&
            BuildConfig.FIREBASE_PROJECT_ID.isNotEmpty() && BuildConfig.FIREBASE_SENDER_ID.isNotEmpty()

    fun init(context: Context) {
        if (!available || FirebaseApp.getApps(context).isNotEmpty()) return
        try {
            FirebaseApp.initializeApp(
                context,
                FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FIREBASE_APP_ID)
                    .setApiKey(BuildConfig.FIREBASE_API_KEY)
                    .setProjectId(BuildConfig.FIREBASE_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FIREBASE_SENDER_ID)
                    .build(),
            )
        } catch (_: Exception) {
            // Without Google Play services (or with unusable values) the app simply has no push.
        }
    }

    fun ready(context: Context): Boolean = available && FirebaseApp.getApps(context).isNotEmpty()
}

/** The four notification channels and the notifications themselves. */
object PushNotifier {
    const val ACTION_OPEN = "dev.lifeos.app.action.OPEN_PUSH"
    const val EXTRA_DESTINATION = "lifeos.push.destination"
    const val EXTRA_NOTICE_TITLE = "lifeos.push.notice_title"
    const val EXTRA_NOTICE_LINK = "lifeos.push.notice_link"

    /** Create the channels (repeating this changes nothing the owner set in the system settings). */
    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        for (channel in PushChannel.entries) {
            val (name, description, importance) = when (channel) {
                PushChannel.CHAT -> Triple(R.string.push_channel_chat, R.string.push_channel_chat_text, NotificationManager.IMPORTANCE_HIGH)
                PushChannel.COURSES -> Triple(R.string.push_channel_courses, R.string.push_channel_courses_text, NotificationManager.IMPORTANCE_DEFAULT)
                PushChannel.COACH -> Triple(R.string.push_channel_coach, R.string.push_channel_coach_text, NotificationManager.IMPORTANCE_DEFAULT)
                PushChannel.REMINDERS -> Triple(R.string.push_channel_reminders, R.string.push_channel_reminders_text, NotificationManager.IMPORTANCE_HIGH)
            }
            manager.createNotificationChannel(
                NotificationChannel(channel.id, context.getString(name), importance).apply { this.description = context.getString(description) },
            )
        }
    }

    /** The fixed text of an event kind: the only words a push puts on the screen, lock screen included. */
    fun textOf(event: String): Int = when (event) {
        "answer" -> R.string.push_answer
        "question" -> R.string.push_question
        "approval" -> R.string.push_approval
        "courses" -> R.string.push_courses
        "signin" -> R.string.push_signin
        "coach" -> R.string.push_coach
        "reminder" -> R.string.push_reminder
        else -> R.string.push_notice
    }

    fun show(context: Context, hint: Hint) {
        createChannels(context)
        val key = PushHints.notificationKey(hint)
        val open = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_DESTINATION, PushHints.destinationOf(hint).encode())
        // A notice's own title travels only inside the app (shown after unlocking), never in the notification.
        PushHints.noticeTitle(hint)?.let { open.putExtra(EXTRA_NOTICE_TITLE, it) }
        PushHints.noticeLink(hint)?.let { open.putExtra(EXTRA_NOTICE_LINK, it) }
        val tap = PendingIntent.getActivity(context, key.hashCode(), open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = context.getString(textOf(hint.event))
        val public = NotificationCompat.Builder(context, PushHints.channelOf(hint).id)
            .setSmallIcon(R.drawable.ic_stat_lifeos)
            .setContentTitle(text)
            .build()
        val notification = NotificationCompat.Builder(context, PushHints.channelOf(hint).id)
            .setSmallIcon(R.drawable.ic_stat_lifeos)
            .setContentTitle(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setCategory(if (PushHints.channelOf(hint) == PushChannel.CHAT) NotificationCompat.CATEGORY_MESSAGE else NotificationCompat.CATEGORY_REMINDER)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(key.hashCode(), notification)
        } catch (_: SecurityException) {
            // Notifications are not allowed on this phone: nothing to show.
        }
    }
}
