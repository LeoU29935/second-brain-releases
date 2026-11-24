package com.leo.secondbrain.reminders

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.util.Log
import com.leo.secondbrain.BrainApplication
import com.leo.secondbrain.MainActivity
import com.leo.secondbrain.R
import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.ReminderOccurrence
import com.leo.secondbrain.data.Repeat
import com.leo.secondbrain.data.RecurrenceRule
import com.leo.secondbrain.data.ScheduleMove
import com.leo.secondbrain.data.nextReminderOccurrence
import com.leo.secondbrain.data.occurrenceInstant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

internal const val OPEN_TODAY = "com.leo.secondbrain.OPEN_TODAY"
internal const val OPEN_REMINDER = "com.leo.secondbrain.OPEN_REMINDER"
private const val LEGACY_ALARM = "com.leo.secondbrain.REMINDER"
private const val EXACT_ALARM = "com.leo.secondbrain.REMINDER_EXACT"
private const val FALLBACK_ALARM = "com.leo.secondbrain.REMINDER_FALLBACK"
private const val CHANNEL = "schedule"

internal data class ReminderTarget(val id: String, val event: Boolean) {
    val category get() = if (event) "Event" else "Task"
    val key get() = "$category:$id"
    val uri: Uri get() = Uri.Builder().scheme("secondbrain").authority("reminder")
        .appendPath(if (event) "event" else "task").appendPath(id).build()

    companion object {
        fun from(intent: Intent): ReminderTarget? {
            if (intent.action != OPEN_REMINDER) return null
            val uri = intent.data?.takeIf { it.scheme == "secondbrain" && it.authority == "reminder" } ?: return null
            val parts = uri.pathSegments
            if (parts.size != 2 || parts[1].isBlank()) return null
            return when (parts[0]) {
                "event" -> ReminderTarget(parts[1], event = true)
                "task" -> ReminderTarget(parts[1], event = false)
                else -> null
            }
        }
    }
}

private data class ReminderSchedule(
    val target: ReminderTarget, val title: String, val firstDay: Long, val anchor: Long,
    val minute: Int, val advance: Int, val repeat: Repeat, val excluded: Set<Long> = emptySet(),
    val rule: RecurrenceRule? = null
) {
    val signature get() = "$firstDay/$anchor/$minute/$advance/${repeat.name}" + (rule?.let { "/${it.encode()}" } ?: "")
    fun triggerOn(day: Long, zone: ZoneId) =
        occurrenceInstant(LocalDate.ofEpochDay(day), minute, zone).minusSeconds(advance * 60L)

    fun next(after: Instant, zone: ZoneId): ReminderOccurrence? =
        nextReminderOccurrence(anchor, minute, advance, repeat,
            maxOf(after, triggerOn(firstDay, zone).minusMillis(1)), zone, excluded, rule)
}

private data class ReminderState(val signature: String, val planned: Long?, val handled: Long?) {
    fun json() = JSONObject().put("schedule", signature).put("planned", planned ?: JSONObject.NULL)
        .put("handled", handled ?: JSONObject.NULL)
}

internal class Reminders(
    private val context: Context, private val repository: BrainRepository,
    private val clock: () -> Instant = Instant::now, private val zone: () -> ZoneId = ZoneId::systemDefault
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private val checkpoint = context.getSharedPreferences("reminder-delivery", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private data class AlarmPlan(val exactAt: Instant?, val fallbackAt: Instant?)
    private var armed: AlarmPlan? = null

    val exactAvailable: Boolean
        get() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()

    val notificationsEnabled: Boolean
        get() = notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE &&
            (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    fun prepareChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Task and event reminders", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    fun requestRefresh() {
        scope.launch { refreshOrRetry() }
    }

    suspend fun refreshOrRetry(forceRearm: Boolean = false) {
        try {
            refresh(forceRearm)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e("SecondBrain", "Could not reconcile reminders", error)
            val queued = ReminderReconciliationJob.enqueue(context)
            try { mutex.withLock {
                armed = null
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, clock().plusSeconds(60).toEpochMilli(),
                    pending(FALLBACK_ALARM))
            } } catch (fallback: Exception) {
                if (fallback is CancellationException || !queued) throw fallback
                Log.w("SecondBrain", "Reminder reconciliation queued", fallback)
            }
        }
    }

    suspend fun refresh(forceRearm: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            prepareChannel()
            val now = clock()
            val zone = zone()
            val savedJson = JSONObject(checkpoint.getString("occurrences", "{}") ?: "{}")
            val legacy = checkpoint.getLong("next", 0).takeIf {
                it in (now.toEpochMilli() - 6 * 3_600_000L)..now.toEpochMilli()
            }?.let(Instant::ofEpochMilli)
            val flexibleCheck = repository.reconsiderFlexibleTasks(now.atZone(zone))
            val (tasks, events, skips) = repository.reminderSnapshot()
            val excluded = skips.groupBy({ it.seriesId }, { it.day }).mapValues { it.value.toSet() }
            val schedules = buildList {
                for (task in tasks) {
                    if (task.timeOfDay != null && task.dueMinute == null ||
                        ScheduleMove.read(task.scheduleChange)?.waiting == true) continue
                    val deadline = requireNotNull(task.deadline)
                    add(ReminderSchedule(ReminderTarget(task.id, event = false), task.title, deadline,
                        task.recurrenceAnchor ?: deadline, task.dueMinute ?: 9 * 60, requireNotNull(task.reminder),
                        task.repeat, excluded[task.id].orEmpty(), RecurrenceRule.read(task.recurrenceRule)))
                }
                for (event in events) {
                    if (!event.allDay && event.startMinute == null) continue
                    add(ReminderSchedule(ReminderTarget(event.id, event = true), event.title, event.startDay,
                        event.startDay, event.startMinute ?: 0, requireNotNull(event.reminder), event.repeat))
                }
            }
            val state = JSONObject()
            val allowed = notificationsEnabled
            var earliest: Instant? = null
            for (schedule in schedules) {
                val key = schedule.target.key
                val saved = savedJson.optJSONObject(key)
                val previous = saved?.takeIf { it.optString("schedule") == schedule.signature }
                if (saved != null && previous == null) cancelNotification(key, saved)
                var handled = previous?.day("handled")
                val due = previous?.day("planned")?.takeUnless { it in schedule.excluded }
                    ?: legacy?.let { schedule.next(it.minusMillis(1), zone)?.day }
                if (due != null && (handled == null || due > handled) && schedule.triggerOn(due, zone) <= now) {
                    if (allowed) {
                        handled?.let { notifications.cancel(key, it.toInt()) }
                        notify(schedule, due)
                    }
                    handled = due
                }
                val after = handled?.let { maxOf(now, schedule.triggerOn(it, zone)) } ?: now
                val next = schedule.next(after, zone)
                if (next != null) earliest = earliest?.let { minOf(it, next.trigger) } ?: next.trigger
                state.put(key, ReminderState(schedule.signature, next?.day, handled).json())
            }
            for (key in savedJson.keys()) {
                if (!state.has(key)) cancelNotification(key, savedJson.getJSONObject(key))
            }
            // Save occurrence identities before handing off the next wakeup to Android.
            if (legacy != null || checkpoint.contains("next") || savedJson.toString() != state.toString()) {
                check(checkpoint.edit().putString("occurrences", state.toString()).remove("next").commit())
            }
            arm(earliest, flexibleCheck, forceRearm)
        }
    }

    private fun JSONObject.day(name: String): Long? =
        optLong(name, Long.MIN_VALUE).takeUnless { it == Long.MIN_VALUE }

    private fun cancelNotification(key: String, saved: JSONObject) {
        notifications.cancel(key, saved.day("handled")?.toInt() ?: 0)
    }

    private fun pending(action: String, flags: Int = 0): PendingIntent =
        requireNotNull(PendingIntent.getBroadcast(context, 0,
            Intent(context, ReminderReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or flags))

    private fun arm(reminder: Instant?, flexibleCheck: Instant?, force: Boolean) {
        val exactAt = reminder?.takeIf { exactAvailable }
        val fallbackAt = listOfNotNull(flexibleCheck, reminder?.let { if (exactAt == null) it else it.plusSeconds(120) })
            .minOrNull()
        val plan = AlarmPlan(exactAt, fallbackAt)
        if (armed == plan && !force) return
        if (armed == null) {
            PendingIntent.getBroadcast(context, 0, Intent(context, ReminderReceiver::class.java).setAction(LEGACY_ALARM),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE)?.let(alarms::cancel)
        }
        val exact = pending(EXACT_ALARM)
        val fallback = pending(FALLBACK_ALARM)
        // Android can kill the process and cancel every exact alarm when the permission is revoked.
        // A separate inexact alarm remains eligible for delivery in that case.
        val precise = exactAt != null && try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, exactAt.toEpochMilli(), exact)
            true
        } catch (_: SecurityException) {
            false
        }
        if (!precise) alarms.cancel(exact)
        val backupAt = if (precise) fallbackAt else listOfNotNull(reminder, flexibleCheck).minOrNull()
        if (backupAt == null) alarms.cancel(fallback)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, backupAt.toEpochMilli(), fallback)
        armed = AlarmPlan(exactAt.takeIf { precise }, backupAt)
    }

    private fun notify(reminder: ReminderSchedule, occurrenceDay: Long) {
        val target = reminder.target
        val open = PendingIntent.getActivity(context, 0,
            Intent(context, MainActivity::class.java).setAction(OPEN_REMINDER).setData(target.uri)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(reminder.title)
            .setContentText("${target.category} reminder")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setCategory(Notification.CATEGORY_REMINDER)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        try {
            notifications.notify(target.key, occurrenceDay.toInt(), notification)
        } catch (error: SecurityException) {
            Log.w("SecondBrain", "Notification access changed during delivery", error)
        }
    }
}

class ReminderReconciliationJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as BrainApplication
        work = CoroutineScope(Dispatchers.Main).launch {
            val retry = try {
                app.reminders.refresh(forceRearm = true)
                false
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("SecondBrain", "Reminder reconciliation will retry", error)
                true
            }
            work = null
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        work?.cancel()
        work = null
        return true
    }

    internal companion object {
        private const val JOB_ID = 31403

        fun enqueue(context: Context): Boolean = runCatching {
            val scheduler = context.getSystemService(JobScheduler::class.java)
            scheduler.getPendingJob(JOB_ID) != null || scheduler.schedule(
                JobInfo.Builder(JOB_ID, ComponentName(context, ReminderReconciliationJob::class.java))
                    .setMinimumLatency(30_000)
                    .setBackoffCriteria(30_000, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                    .setPersisted(true)
                    .build()) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val work = goAsync()
        val app = context.applicationContext as BrainApplication
        scope.launch {
            try {
                app.reminders.refreshOrRetry(forceRearm = true)
            } finally {
                work.finish()
            }
        }
    }

    private companion object {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
