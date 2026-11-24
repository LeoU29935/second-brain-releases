package com.leo.secondbrain

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.data.BrainDatabase
import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.EntryEntity
import com.leo.secondbrain.data.EntryKind
import com.leo.secondbrain.data.Event
import com.leo.secondbrain.data.Repeat
import com.leo.secondbrain.data.newId
import com.leo.secondbrain.reminders.ReminderTarget
import com.leo.secondbrain.reminders.Reminders
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class V0421ReminderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val notifications = context.getSystemService(NotificationManager::class.java)

    @Test
    fun revisesOrCancelsStaleAlarmsAndUsesTheCurrentTitle() = runBlocking {
        allowNotifications()
        val db = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        val store = isolatedCheckpoint()
        val repo = BrainRepository(db)
        val due = ZonedDateTime.now().plusMinutes(3).truncatedTo(ChronoUnit.MINUTES)
        var now = due.minusMinutes(1).toInstant()
        val reminders = Reminders(store, repo, { now }, { due.zone })
        val moved = task("Move this reminder", due)
        val completed = task("Complete this task", due)
        val deleted = task("Delete this task", due)
        val existing = task("Rename this task", due)
        val items = listOf(moved, completed, deleted, existing)
        try {
            items.forEach { repo.saveEntry(it, emptySet()) }
            reminders.refresh()
            repo.saveEntry(task(moved.title, due.plusMinutes(30)).copy(id = moved.id), emptySet())
            repo.completeWithUndo(completed.id, true, completed.deadline)
            repo.deleteWithUndo(deleted.id)
            repo.saveEntry(existing.copy(title = "Current task title"), emptySet())

            now = due.plusSeconds(1).toInstant()
            val restarted = Reminders(store, repo, { now }, { due.zone })
            restarted.refresh(forceRearm = true)
            awaitNotification("Task:${existing.id}")
            val posted = notifications.activeNotifications.filter { row -> items.any { "Task:${it.id}" == row.tag } }
            assertEquals("Task:${existing.id}", posted.single().tag)
            assertEquals("Current task title", posted.single().notification.extras.getString(Notification.EXTRA_TITLE))
            assertNotNull(posted.single().notification.contentIntent)
            assertEquals(listOf("task", existing.id), ReminderTarget(existing.id, event = false).uri.pathSegments)

            now = due.plusMinutes(31).toInstant()
            restarted.refresh(forceRearm = true)
            awaitNotification("Task:${moved.id}")
        } finally {
            items.forEach { repo.deleteWithUndo(it.id) }
            reminders.refresh(forceRearm = true)
            db.close()
        }
    }

    @Test
    fun timezoneShiftUsesTheLocalOccurrenceAndClockRollbackDoesNotReplayIt() = runBlocking {
        allowNotifications()
        val db = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        val repo = BrainRepository(db)
        val store = isolatedCheckpoint()
        val due = ZonedDateTime.now(ZoneId.of("UTC")).plusHours(6).truncatedTo(ChronoUnit.MINUTES)
        val task = task("Time zone test", due).copy(repeat = Repeat.DAILY)
        val tag = "Task:${task.id}"
        var zone = due.zone
        var now = due.minusMinutes(10).toInstant()
        val reminders = Reminders(store, repo, { now }, { zone })
        try {
            repo.saveEntry(task, emptySet())
            reminders.refresh()

            zone = ZoneId.of("America/New_York")
            now = due.plusSeconds(2).toInstant()
            reminders.refresh(forceRearm = true)
            assertTrue(notifications.activeNotifications.none { it.tag == tag })

            val localDue = due.toLocalDateTime().atZone(zone).toInstant()
            now = localDue.plusSeconds(2)
            reminders.refresh(forceRearm = true)
            awaitNotification(tag)
            notifications.activeNotifications.single { it.tag == tag }.also { notifications.cancel(it.tag, it.id) }
            withTimeout(5_000) {
                while (notifications.activeNotifications.any { it.tag == tag }) delay(30)
            }

            now = localDue.minusSeconds(30)
            reminders.refresh(forceRearm = true)
            now = localDue.plusSeconds(5)
            reminders.refresh(forceRearm = true)
            assertTrue(notifications.activeNotifications.none { it.tag == tag })
        } finally {
            repo.deleteWithUndo(task.id)
            reminders.refresh(forceRearm = true)
            db.close()
        }
    }

    // Run explicitly on a disposable Android guest to exercise Android's real system alarm across process death.
    @Test
    fun prepareSystemAlarmFixture(): Unit = runBlocking {
        val testCase = InstrumentationRegistry.getArguments().getString("reminder_fixture")
        assumeTrue("Requires a disposable test device", testCase != null)
        requireNotNull(testCase)
        allowNotifications()
        val delay = InstrumentationRegistry.getArguments().getString("delay_minutes")?.toLong() ?: 2
        val due = ZonedDateTime.now().plusMinutes(delay).truncatedTo(ChronoUnit.MINUTES)
        val app = context.applicationContext as BrainApplication
        val task = task("QA reminder $testCase", due)
        app.repository.saveEntry(task, emptySet())
        if (testCase == "foreground") {
            val end = due.plusMinutes(1)
            app.repository.saveEvent(Event(title = "QA calendar reminder", startDay = due.toLocalDate().toEpochDay(),
                startMinute = minute(due), endDay = end.toLocalDate().toEpochDay(), endMinute = minute(end),
                allDay = false, reminder = 0), emptySet())
        }
        app.reminders.refresh()
        android.util.Log.i("ReminderFixture", "case=$testCase id=${task.id} due=${due.toInstant()} exact=${app.reminders.exactAvailable}")
    }

    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private suspend fun awaitNotification(tag: String) = withTimeout(3_000) {
        while (notifications.activeNotifications.none { it.tag == tag }) delay(30)
    }

    private fun task(title: String, due: ZonedDateTime) = EntryEntity(kind = EntryKind.TASK, title = title,
        deadline = due.toLocalDate().toEpochDay(), dueMinute = minute(due), reminder = 0)

    private fun minute(date: ZonedDateTime) = date.hour * 60 + date.minute

    private fun isolatedCheckpoint(): Context {
        val checkpoint = context.getSharedPreferences("test-reminders-${newId()}", Context.MODE_PRIVATE)
        return object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) =
                if (name == "reminder-delivery") checkpoint else super.getSharedPreferences(name, mode)
        }
    }
}
