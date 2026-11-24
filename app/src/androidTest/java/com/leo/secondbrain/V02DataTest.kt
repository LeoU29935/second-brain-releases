package com.leo.secondbrain

import android.net.Uri
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.RelevantContext
import com.leo.secondbrain.ai.objects
import com.leo.secondbrain.ai.readSuggestion
import com.leo.secondbrain.backup.Backups
import com.leo.secondbrain.capture.RecordingStore
import com.leo.secondbrain.data.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import com.leo.secondbrain.reminders.Reminders
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

@RunWith(AndroidJUnit4::class)
class V02DataTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun migrationFromExportedV01PreservesEntriesCollectionsAndTags() = runBlocking {
        val filename = "v01-fixture-${newId()}.db"
        val schema = instrumentation.context.assets
            .open("com.leo.secondbrain.data.BrainDatabase/1.json").bufferedReader().use { JSONObject(it.readText()) }
            .getJSONObject("database")
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name(filename)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        for (entity in schema.getJSONArray("entities").objects()) {
                            fun sql(query: String) = query.replace("\${TABLE_NAME}", entity.getString("tableName"))
                            db.execSQL(sql(entity.getString("createSql")))
                            for (index in entity.optJSONArray("indices").objects()) {
                                db.execSQL(sql(index.getString("createSql")))
                            }
                        }
                        val setup = schema.getJSONArray("setupQueries")
                        repeat(setup.length()) { db.execSQL(setup.getString(it)) }
                        db.execSQL("INSERT INTO collections VALUES ('parent','Original','','2',NULL)")
                        db.execSQL("INSERT INTO collections VALUES ('child','Child','','3','parent')")
                        db.execSQL("INSERT INTO tags VALUES ('tag','Blue',4)")
                        db.execSQL("INSERT INTO entries VALUES ('note','NOTE','Existing note','Body','child',NULL,'NONE',0,11,12)")
                        db.execSQL("INSERT INTO entries VALUES ('task','TASK','Existing task','','parent',20777,'HIGH',1,11,13)")
                        db.execSQL("INSERT INTO entry_tags VALUES ('note','tag')")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = error("Unexpected upgrade")
                }).build()
        )
        helper.writableDatabase
        helper.close()
        val db = Room.databaseBuilder(context, BrainDatabase::class.java, filename)
            .addMigrations(migration1To2, migration2To3, migration3To4, migration4To5, migration5To6, migration6To7, migration7To8, migration8To9).build()
        try {
            val repo = BrainRepository(db)
            val snapshot = repo.snapshot(true)
            assertEquals("parent", snapshot.collections.single { it.id == "child" }.parentId)
            val note = snapshot.entries.single { it.entry.id == "note" }
            assertEquals("Body", note.entry.body)
            assertEquals("Blue", note.tags.single().name)
            val task = snapshot.entries.single { it.entry.id == "task" }.entry
            assertEquals(20777L, task.deadline)
            assertEquals(Priority.HIGH, task.priority)
            assertTrue(task.completed)
            assertNull(task.dueMinute)
            assertNull(task.reminder)
            assertEquals(Repeat.NONE, task.repeat)
            assertTrue(snapshot.events.isEmpty())
            assertTrue(snapshot.dumps.isEmpty())
        } finally {
            db.close()
            context.deleteDatabase(filename)
        }
        Unit
    }

    @Test
    fun draftApprovalIsExplicitAtomicAndRejectsStaleTargets() = runBlocking {
        withRepository { repository ->
            val existing = EntryEntity(kind = EntryKind.NOTE, title = "Existing")
            repository.saveEntry(existing, emptySet())
            val snapshot = repository.snapshot().entries.single().asProposal()
            val dump = BrainDump(original = "New thought")
            repository.release.insertDump(dump)
            val suggested = listOf(Proposal(DraftKind.NOTE, "New note"), snapshot.copy(title = "Renamed"))
            assertTrue(repository.saveSuggestions(dump.id, suggested))
            assertFalse(repository.saveSuggestions(dump.id, suggested))
            assertEquals("Existing", repository.entries.first().single().entry.title)

            repository.saveEntry(existing.copy(title = "Locally edited"), emptySet())
            val drafts = repository.drafts.first()
            assertTrue(runCatching { repository.approveDrafts(drafts.map { it.id }.toSet()) }.isFailure)
            assertEquals("Locally edited", repository.entries.first().single().entry.title)
            assertTrue(repository.drafts.first().all { it.status == DraftStatus.PENDING })

            val created = drafts.single { Proposal.read(JSONObject(it.proposal)).targetId == null }
            repository.approveEditedDraft(created.id, Proposal(DraftKind.NOTE, "Explicitly approved"))
            assertTrue(repository.entries.first().any { it.entry.title == "Explicitly approved" })
            assertEquals(DumpStatus.UNREVIEWED, repository.dumps.first().single().status)
            val rejected = drafts.single { it.id != created.id }
            repository.editDraft(rejected.id, Proposal.read(JSONObject(rejected.proposal)), DraftStatus.REJECTED)
            assertEquals(DumpStatus.REVIEWED, repository.dumps.first().single().status)
        }
    }

    @Test
    fun backupRestoresAudioAndRepeatedImportsKeepLocalEdits() = runBlocking {
        val store = RecordingStore(context)
        val recording = store.file(newId() + ".m4a")
        val archive = File.createTempFile("second-brain-test-", ".zip", context.cacheDir)
        val uri = Uri.fromFile(archive)
        val audio = ByteArray(1_000) { (it % 251).toByte() }
        try {
            val note = EntryEntity(kind = EntryKind.NOTE, title = "Backed up")
            withRepository { source ->
                source.saveEntry(note, emptySet())
                recording.writeBytes(audio)
                source.release.insertDump(BrainDump(recording = recording.name, transcript = "Speech"))
                val day = LocalDate.of(2027, 2, 1).toEpochDay()
                source.saveEvent(Event(title = "Calendar", startDay = day, endDay = day, repeat = Repeat.MONTHLY), emptySet())
                val export = Backups(context, source, store)
                export.export(uri, includeAudio = true)
                assertEquals(1, export.preview(uri).recordings)
            }
            recording.delete()
            withRepository { target ->
                val restore = Backups(context, target, store)
                assertEquals(3, restore.restore(uri))
                assertArrayEquals(audio, recording.readBytes())
                assertEquals("Speech", target.dumps.first().single().transcript)
                assertEquals(Repeat.MONTHLY, target.events.first().single().repeat)
                target.saveEntry(note.copy(title = "Edited locally"), emptySet())
                assertEquals(0, restore.restore(uri))
                assertEquals("Edited locally", target.entries.first().single().entry.title)
            }
        } finally {
            archive.delete()
            recording.delete()
        }
    }

    @Test
    fun organizerDropsUnsupportedScheduleAndInventedWorkspaceIdentifiers() {
        val context = RelevantContext(JSONObject(), emptyMap(), setOf("known"), setOf("real"))
        val value = JSONObject("""{
            "kind":"EVENT","title":"Lunch","body":"","collectionId":"invented","tags":["real","fake"],
            "priority":"NONE","startDate":"2026-11-20","endDate":"2026-11-20","startTime":"12:00",
            "endTime":"13:00","allDay":false,"location":"","repeat":"NONE","reminder":30,
            "targetId":"bogus","related":["unknown"],"uncertainty":"","scheduleEvidence":"at noon"
        }""")
        val proposal = readSuggestion(value, context, "Lunch someday")
        assertNull(proposal.startDay)
        assertNull(proposal.reminder)
        assertNull(proposal.targetId)
        assertNull(proposal.collectionId)
        assertEquals(setOf("real"), proposal.tags)
        assertTrue(proposal.uncertainty.isNotBlank())
        assertTrue(runCatching { proposal.validated(true) }.isFailure)
    }

    @Test
    fun recurringReminderPostsNotificationAndPlansNextDelivery() = runBlocking {
        if (Build.VERSION.SDK_INT >= 33) {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
        }
        withRepository { repository ->
            val now = ZonedDateTime.now().plusMinutes(2).truncatedTo(ChronoUnit.MINUTES)
            val day = now.toLocalDate().toEpochDay()
            val minute = now.hour * 60 + now.minute
            val end = now.plusMinutes(1)
            val title = "Notification test " + newId()
            val event = Event(title = title, startDay = day, startMinute = minute,
                endDay = end.toLocalDate().toEpochDay(), endMinute = end.hour * 60 + end.minute,
                allDay = false, reminder = 0, repeat = Repeat.DAILY)
            repository.saveEvent(event, emptySet())
            val prefs = context.getSharedPreferences("reminder-delivery", Context.MODE_PRIVATE)
            val notification = context.getSystemService(NotificationManager::class.java)
            var clock = now.minusMinutes(1).toInstant()
            val reminders = Reminders(context, repository, { clock }, { now.zone })
            try {
                reminders.refresh()
                // Recreate the scheduler after a long delay, as at boot or after a late idle alarm.
                clock = now.plusHours(8).toInstant()
                val restarted = Reminders(context, repository, { clock }, { now.zone })
                restarted.refresh(forceRearm = true)
                withTimeout(3_000) {
                    while (notification.activeNotifications.none { it.tag == "Event:${event.id}" }) delay(50)
                }
                val state = org.json.JSONObject(prefs.getString("occurrences", "{}")!!)
                    .getJSONObject("Event:${event.id}")
                assertEquals(day + 1, state.getLong("planned"))
                assertEquals(day, state.getLong("handled"))
                val posted = notification.activeNotifications.single { it.tag == "Event:${event.id}" }
                assertEquals(title, posted.notification.extras.getString(android.app.Notification.EXTRA_TITLE))
                notification.cancel(posted.tag, posted.id)
                // NotificationManager applies cancel asynchronously.
                withTimeout(5_000) {
                    while (notification.activeNotifications.any { it.tag == posted.tag }) delay(50)
                }
                restarted.refresh(forceRearm = true)
                assertTrue(notification.activeNotifications.none { it.tag == posted.tag })
            } finally {
                notification.cancel("Event:${event.id}", day.toInt())
                repository.deleteEvent(event.id)
                reminders.refresh()
            }
        }
    }

    @Test
    fun deletingRecordingsCascadesDraftsAndKeepsSharedAudioAndWorkspace() = runBlocking {
        withRepository { repository ->
            val shared = newId() + ".m4a"
            val unique = newId() + ".m4a"
            val first = BrainDump(recording = shared)
            val second = BrainDump(recording = shared)
            val third = BrainDump(recording = unique)
            val text = BrainDump(original = "Keep this text")
            listOf(first, second, third, text).forEach { repository.release.insertDump(it) }
            repository.addDraft(first.id, Proposal(DraftKind.NOTE, "Keep approved note"))
            repository.approveDrafts(setOf(repository.drafts.first().single().id))
            assertEquals(setOf(unique), repository.deleteVoiceCaptures(setOf(first.id, third.id, text.id)))
            assertEquals(setOf(second.id, text.id), repository.dumps.first().map { it.id }.toSet())
            assertTrue(repository.drafts.first().isEmpty())
            assertEquals("Keep approved note", repository.entries.first().single().entry.title)
            assertEquals(setOf(shared), repository.deleteVoiceCaptures(setOf(second.id)))
        }
    }

    private suspend fun withRepository(work: suspend (BrainRepository) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        try {
            work(BrainRepository(db))
        } finally {
            db.close()
        }
    }
}
