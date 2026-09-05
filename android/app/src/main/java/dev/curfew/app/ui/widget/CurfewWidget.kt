package dev.curfew.app.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import dev.curfew.app.R
import dev.curfew.app.curfew
import kotlinx.coroutines.launch

/**
 * A home screen face for the state of enforcement.
 *
 * There is no button on it. Everything a lock can be ended by — a screen lock, a typed passage, a
 * peer — needs a screen, and a widget that could end a session without one would be the shortest
 * way past every lock in the app.
 */
class CurfewWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        // The state lives behind an encrypted database and a suspending lock, so the update is
        // asynchronous; goAsync keeps the process alive for the moment that takes.
        val pending = goAsync()
        val runtime = context.curfew
        runtime.scope.launch {
            val face = runCatching {
                val now = runtime.clock.now()
                widgetFace(
                    profiles = runtime.activeProfiles.value,
                    nextChange = runtime.nextChange(now, runtime.calendarEvents(now)),
                    streak = runtime.stats(now = now).currentStreak,
                    now = now,
                )
            }.getOrElse {
                // A widget that shows a stack trace is worse than one that admits it does not know.
                WidgetFace("Curfew", "Open the app to see the current state", "")
            }
            val views = views(context, face)
            for (id in ids) manager.updateAppWidget(id, views)
            pending.finish()
        }
    }

    private fun views(context: Context, face: WidgetFace): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_curfew).apply {
            setTextViewText(R.id.widget_headline, face.headline)
            setTextViewText(R.id.widget_detail, face.detail)
            setTextViewText(R.id.widget_footer, face.footer)
            setContentDescription(
                R.id.widget_root,
                listOf(face.headline, face.detail, face.footer).filter { it.isNotEmpty() }
                    .joinToString(". "),
            )
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
            setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 0, open, PendingIntent.FLAG_IMMUTABLE),
            )
        }

    companion object {
        /**
         * Redraw every placed widget.
         *
         * Called from the enforcement loop rather than driven by `updatePeriodMillis`, which the
         * system will not honour more often than half an hour — long enough for the widget to say
         * a session is running for twenty minutes after it ended.
         */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, CurfewWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                android.content.Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .setComponent(ComponentName(context, CurfewWidget::class.java))
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }
    }
}
