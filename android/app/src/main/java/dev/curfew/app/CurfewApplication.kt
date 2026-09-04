package dev.curfew.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import dev.curfew.app.data.CurfewRuntime

/**
 * The process entry point, and the only place the runtime is constructed.
 *
 * Curfew has one long-lived object graph — the policy core, the stores that back it, and the
 * enforcer that acts on it — and every component here reaches it through [runtime]. The
 * accessibility service, the foreground service, the alarm receiver and the UI all run in this same
 * process, and they must be looking at one set of sessions, not four.
 */
class CurfewApplication : Application() {

    lateinit var runtime: CurfewRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        runtime = CurfewRuntime.create(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ENFORCEMENT,
            getString(R.string.notification_channel_enforcement),
            // Low: the notification exists so the user always knows enforcement is on. It should be
            // legible, not loud, and it must not be dismissible while a session runs.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_enforcement_description)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ENFORCEMENT = "enforcement"
    }
}

/** The runtime, from anywhere that has a context. */
val android.content.Context.curfew: CurfewRuntime
    get() = (applicationContext as CurfewApplication).runtime
