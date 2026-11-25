package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.core.PreferencesManager

class AlfredGoalsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        const val EXTRA_OPEN_TAB = "extra_open_tab"
        const val EXTRA_TRIGGER_VOICE = "extra_trigger_voice"

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, AlfredGoalsWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isNotEmpty()) {
                for (id in ids) {
                    updateAppWidget(context, manager, id)
                }
            }
        }

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val prefs = PreferencesManager(context)
            val title = prefs.readWidgetTitle()
            val theme = prefs.readWidgetTheme()
            val showCompleted = prefs.readWidgetShowCompleted()
            val syncLabel = prefs.readWidgetSyncLabel()
            val rawGoals = prefs.readWidgetGoals()
            val visibleGoals = if (showCompleted) {
                rawGoals
            } else {
                rawGoals.filterNot { it.startsWith("✓") }
            }.take(4)

            val views = RemoteViews(context.packageName, R.layout.widget_alfred_goals)

            val (bgRes, titleColor, subColor, itemColor) = when (theme) {
                "HACKER" -> Quad(
                    R.drawable.widget_bg_hacker,
                    Color.parseColor("#00FF41"),
                    Color.parseColor("#00E5FF"),
                    Color.parseColor("#C8FFD4")
                )
                "LIGHT" -> Quad(
                    R.drawable.widget_bg_light,
                    Color.parseColor("#0F172A"),
                    Color.parseColor("#1D4ED8"),
                    Color.parseColor("#334155")
                )
                else -> Quad(
                    R.drawable.widget_bg_dark,
                    Color.parseColor("#F8FAFC"),
                    Color.parseColor("#00F5D4"),
                    Color.parseColor("#CBD5E1")
                )
            }

            views.setInt(R.id.widget_root, "setBackgroundResource", bgRes)
            views.setTextViewText(R.id.widget_title, title)
            views.setTextColor(R.id.widget_title, titleColor)
            views.setTextViewText(R.id.widget_subtitle, syncLabel)
            views.setTextColor(R.id.widget_subtitle, subColor)

            val goalViews = listOf(
                R.id.widget_goal_1,
                R.id.widget_goal_2,
                R.id.widget_goal_3,
                R.id.widget_goal_4
            )

            if (visibleGoals.isEmpty()) {
                views.setViewVisibility(R.id.widget_goal_1, View.VISIBLE)
                views.setTextViewText(R.id.widget_goal_1, "• Tap to add daily encrypted goals")
                views.setTextColor(R.id.widget_goal_1, itemColor)
                goalViews.drop(1).forEach { views.setViewVisibility(it, View.GONE) }
            } else {
                goalViews.forEachIndexed { index, viewId ->
                    val text = visibleGoals.getOrNull(index)
                    if (text != null) {
                        views.setViewVisibility(viewId, View.VISIBLE)
                        views.setTextViewText(viewId, text)
                        views.setTextColor(viewId, itemColor)
                    } else {
                        views.setViewVisibility(viewId, View.GONE)
                    }
                }
            }

            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_TAB, 0)
            }
            val openPending = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 1,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, openPending)

            val voiceIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(EXTRA_OPEN_TAB, 1)
                putExtra(EXTRA_TRIGGER_VOICE, true)
            }
            val voicePending = PendingIntent.getActivity(
                context,
                appWidgetId * 10 + 2,
                voiceIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_voice_btn, voicePending)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    private data class Quad(
        val bgRes: Int,
        val titleColor: Int,
        val subColor: Int,
        val itemColor: Int
    )
}
