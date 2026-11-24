package com.leo.secondbrain.capture

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.widget.RemoteViews
import android.view.View
import androidx.compose.ui.graphics.toArgb
import com.leo.secondbrain.BrainApplication
import com.leo.secondbrain.R
import com.leo.secondbrain.data.Appearance
import com.leo.secondbrain.data.PreferenceStore
import com.leo.secondbrain.data.Preferences
import com.leo.secondbrain.data.WidgetAlignment
import com.leo.secondbrain.ui.theme.resolveColors

internal const val OPEN_CAPTURE = "com.leo.secondbrain.OPEN_CAPTURE"

class MicWidget : AppWidgetProvider() {
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val app = context.applicationContext as? BrainApplication
        val prefs = app?.preferences?.preferences?.value ?: PreferenceStore(context.applicationContext).preferences.value
        val widget = widgetPreferences(prefs)
        val night = when (widget.appearance) {
            Appearance.DARK -> true
            Appearance.LIGHT -> false
            Appearance.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
        }
        val colors = resolveColors(context, night, widget)
        val capture = PendingIntent.getActivity(context, 17,
            Intent(context, WidgetCaptureActivity::class.java).setAction(OPEN_CAPTURE)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val calendar = PendingIntent.getActivity(context, 18,
            Intent(context, QuickCalendarActivity::class.java).setAction(OPEN_QUICK_CALENDAR)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val micImage = icon(colors.primaryContainer.toArgb(), colors.onPrimaryContainer.toArgb())
        val calendarImage = icon(colors.secondaryContainer.toArgb(), colors.onSecondaryContainer.toArgb(), calendar = true)
        val buttons = mapOf(
            WidgetAlignment.LEFT to Triple(R.id.widget_left, R.id.mic_left, R.id.calendar_left),
            WidgetAlignment.CENTER to Triple(R.id.widget_center, R.id.mic_center, R.id.calendar_center),
            WidgetAlignment.RIGHT to Triple(R.id.widget_right, R.id.mic_right, R.id.calendar_right)
        )
        for (id in ids) {
            val view = RemoteViews(context.packageName, R.layout.microphone_widget)
            val (group, mic, date) = buttons.getValue(widget.widgetAlignment)
            buttons.values.forEach { view.setViewVisibility(it.first, if (it.first == group) View.VISIBLE else View.GONE) }
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val width = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 112)
                view.setViewLayoutWidth(group, width.coerceIn(48, 112).toFloat(), android.util.TypedValue.COMPLEX_UNIT_DIP)
            }
            view.setImageViewBitmap(mic, micImage)
            view.setImageViewBitmap(date, calendarImage)
            view.setOnClickPendingIntent(mic, capture)
            view.setOnClickPendingIntent(date, calendar)
            manager.updateAppWidget(id, view)
        }
    }

    private fun icon(background: Int, ink: Int, calendar: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(144, 144, Bitmap.Config.ARGB_8888)
        bitmap.density = 320
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = background
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(RectF(1f, 6f, 143f, 138f), 48f, 48f, paint)
        paint.color = ink
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 6f
        paint.strokeCap = Paint.Cap.ROUND
        if (calendar) {
            canvas.drawRoundRect(RectF(48f, 47f, 96f, 96f), 7f, 7f, paint)
            canvas.drawLine(48f, 63f, 96f, 63f, paint)
            canvas.drawLine(60f, 41f, 60f, 52f, paint)
            canvas.drawLine(84f, 41f, 84f, 52f, paint)
            canvas.drawLine(62f, 78f, 70f, 78f, paint)
            canvas.drawLine(81f, 78f, 83f, 78f, paint)
            return bitmap
        }
        canvas.drawRoundRect(RectF(63f, 42f, 81f, 82f), 9f, 9f, paint)
        canvas.drawArc(RectF(53f, 59f, 91f, 92f), 0f, 180f, false, paint)
        canvas.drawLine(72f, 92f, 72f, 104f, paint)
        canvas.drawLine(63f, 104f, 81f, 104f, paint)
        return bitmap
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, MicWidget::class.java))
            if (ids.isNotEmpty()) MicWidget().onUpdate(context, manager, ids)
        }
    }
}

internal fun widgetPreferences(app: Preferences): Preferences = app.copy(
    colorTheme = app.widgetTheme ?: app.colorTheme,
    appearance = app.widgetAppearance ?: app.appearance,
    dynamicColor = app.dynamicColor && app.widgetTheme == null
)
