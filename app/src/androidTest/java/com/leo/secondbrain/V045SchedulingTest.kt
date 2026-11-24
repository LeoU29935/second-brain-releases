package com.leo.secondbrain

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.RelevantContext
import com.leo.secondbrain.ai.objects
import com.leo.secondbrain.ai.WorkspaceToolSession
import com.leo.secondbrain.ai.readSuggestion
import com.leo.secondbrain.backup.BackupCodec
import com.leo.secondbrain.data.*
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class V045SchedulingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val empty = RelevantContext(JSONObject(), emptyMap(), emptySet(), emptySet())

    @Test
    fun captureAndLiveToolsPersistSeparatePeriodsAndExplicitTimes() = withDatabase { repository ->
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val source = "Tomorrow morning I need to apply for two internships, and in the afternoon I need to call Dad."
        val capture = BrainDump(original = source)
        repository.saveCapture(capture)
        val phrases = listOf("Tomorrow morning", "in the afternoon")
        val titles = listOf("Apply for two internships", "Call Dad")
        val proposals = phrases.zip(titles).map { (quote, title) ->
            readSuggestion(suggestion(title, quote).put("startDate", tomorrow.toString()), empty, source, today,
                suggestReminder = true)
        }
        repository.organizeCapture(capture.id, proposals, draftOnly = true)
        val drafts = repository.release.draftsFor(capture.id)
        repository.approveDrafts(drafts.map { it.id }.toSet())
        val expected = listOf(TimeOfDay.MORNING, TimeOfDay.AFTERNOON)
        val session = WorkspaceToolSession(repository, true)
        for ((index, title) in titles.withIndex()) {
            val saved = repository.snapshot().entries.single { it.entry.title == title }.entry
            assertEquals(tomorrow.toEpochDay(), saved.deadline)
            assertEquals(expected[index], saved.timeOfDay)
            assertTrue(requireNotNull(saved.dueMinute) in expected[index].fromMinute until expected[index].untilMinute)
            assertNotNull(saved.reminder)
            val live = suggestion("Live $title", phrases[index]).put("startDate", today.minusDays(1).toString())
            val id = create(session, source, live)
            val matching = repository.snapshot().entries.single { it.entry.id == id }.entry
            assertEquals(saved.deadline, matching.deadline)
            assertEquals(saved.timeOfDay, matching.timeOfDay)
            assertTrue(requireNotNull(matching.dueMinute) in expected[index].fromMinute until expected[index].untilMinute)
            assertNotNull(matching.reminder)
        }

        val evening = create(session, "Create a task for tomorrow evening",
            suggestion("Read", "tomorrow evening").put("startTime", "18:00"))
        val imperative = create(session, "Call Dad tomorrow evening",
            suggestion("Call Dad again", "tomorrow evening").apply { remove("scheduleEvidence") })
        val exact = create(session, "Create a task for tomorrow at 10 AM", suggestion("Run", "tomorrow at 10 AM"))
        val dateOnly = create(session, "Create a task for Friday", suggestion("Plan", "Friday"))
        val event = create(session, "Schedule dinner this evening", suggestion("Dinner", "this evening", DraftKind.EVENT))
        val dateEvent = create(session, "Schedule the fair tomorrow", suggestion("Fair", "tomorrow", DraftKind.EVENT))
        val workspace = repository.snapshot()
        val entries = workspace.entries.associateBy { it.entry.id }.mapValues { it.value.entry }
        assertEquals(TimeOfDay.EVENING, entries.getValue(evening).timeOfDay)
        assertNotNull(entries.getValue(evening).dueMinute)
        assertNotNull(entries.getValue(evening).reminder)
        assertEquals(tomorrow.toEpochDay(), entries.getValue(imperative).deadline)
        assertEquals(TimeOfDay.EVENING, entries.getValue(imperative).timeOfDay)
        assertEquals(600, entries.getValue(exact).dueMinute)
        assertNull(entries.getValue(exact).timeOfDay)
        assertEquals(today.with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.FRIDAY)).toEpochDay(),
            entries.getValue(dateOnly).deadline)
        assertNotNull(entries.getValue(dateOnly).dueMinute)
        assertNotNull(entries.getValue(dateOnly).reminder)
        assertNull(entries.getValue(dateOnly).timeOfDay)
        assertEquals(today.toEpochDay(), workspace.events.single { it.id == event }.startDay)
        assertEquals(TimeOfDay.EVENING, workspace.events.single { it.id == event }.timeOfDay)
        assertNull(workspace.events.single { it.id == event }.startMinute)
        assertFalse(workspace.events.single { it.id == dateEvent }.allDay)
        assertNull(workspace.events.single { it.id == dateEvent }.startMinute)
    }

    @Test
    fun migrationReopenEditorBackupAndReschedulePreserveTheSchedule() = runBlocking {
        val name = "v045-migration-${newId()}.db"
        val file = context.getDatabasePath(name)
        val today = LocalDate.now()
        val day = today.plusDays(1).toEpochDay()
        file.parentFile?.mkdirs()
        try {
            val schema = instrumentation.context.assets.open("com.leo.secondbrain.data.BrainDatabase/6.json")
                .bufferedReader().use { JSONObject(it.readText()).getJSONObject("database") }
            SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
                for (entity in schema.getJSONArray("entities").objects()) {
                    val table = entity.getString("tableName")
                    fun sql(query: String) = query.replace("${'$'}{TABLE_NAME}", table)
                    old.execSQL(sql(entity.getString("createSql")))
                    for (index in entity.optJSONArray("indices").objects()) old.execSQL(sql(index.getString("createSql")))
                }
                val setup = schema.getJSONArray("setupQueries")
                repeat(setup.length()) { old.execSQL(setup.getString(it)) }
                old.execSQL("""
                    INSERT INTO entries (id,kind,title,body,deadline,priority,completed,createdAt,updatedAt,dueMinute,reminder,repeat)
                    VALUES ('original','TASK','Existing task','',$day,'NONE',0,1,1,600,15,'NONE')
                """.trimIndent())
                old.version = 6
            }

            fun open() = Room.databaseBuilder(context, BrainDatabase::class.java, name).addMigrations(migration6To7, migration7To8, migration8To9).build()
            withRepository(open()) { repo ->
                val original = repo.snapshot().entries.single().entry
                assertEquals(600, original.dueMinute)
                assertEquals(15, original.reminder)
                assertNull(original.timeOfDay)
                repo.saveEditorItem("preferred", Proposal(DraftKind.TASK, "Preferred", startDay = day,
                    timeOfDay = TimeOfDay.MORNING, reminder = 15))
                repo.saveEditorItem("meeting", Proposal(DraftKind.EVENT, "Dinner", startDay = day, endDay = day,
                    timeOfDay = TimeOfDay.EVENING))
            }
            withRepository(open()) { repo ->
                val restored = BackupCodec.decode(BackupCodec.encode(repo.snapshot(), emptyList())).first
                assertEquals(TimeOfDay.EVENING, restored.events.single().timeOfDay)
                val tagged = restored.entries.single { it.entry.id == "preferred" }
                assertEquals(TimeOfDay.MORNING, tagged.entry.timeOfDay)
                assertNull(tagged.entry.reminder)
                repo.moveUnfinishedTasks(day + 1)
                val moved = repo.snapshot().entries.single { it.entry.id == "preferred" }
                assertEquals(day + 1, moved.entry.deadline)
                assertNull(moved.entry.dueMinute)
                val editor = moved.asProposal()
                assertEquals(TimeOfDay.MORNING, editor.timeOfDay)
                repo.saveEditorItem(moved.entry.id, editor.copy(timeOfDay = TimeOfDay.AFTERNOON))
                val edited = repo.snapshot().entries.single { it.entry.id == "preferred" }.asProposal()
                assertEquals(TimeOfDay.AFTERNOON, edited.timeOfDay)
                repo.saveEditorItem(moved.entry.id, edited.copy(timeOfDay = null))
                val checked = repo.snapshot().entries.associate { it.entry.id to it.entry }
                assertNull(checked.getValue("preferred").timeOfDay)
                assertEquals(day, checked.getValue("original").deadline)
            }
        } finally {
            context.deleteDatabase(name)
        }
        Unit
    }

    @Test
    fun automaticPlacementStaysInThePreferredPeriodWhenItMovesDays() = withDatabase { repository ->
        val day = LocalDate.now().plusDays(1)
        val first = day.toEpochDay()
        val window = FlexibleSchedule(first, first + 1, 8 * 60, 21 * 60, 60)
        val id = repository.applyAssistantProposal(Proposal(DraftKind.TASK, "Place the report",
            startDay = first, timeOfDay = TimeOfDay.MORNING, flexibleSchedule = window))
        repository.saveEvent(Event(title = "Busy morning", startDay = first, endDay = first,
            startMinute = 8 * 60, endMinute = 12 * 60, allDay = false), emptySet())
        repository.reconsiderFlexibleTasks(day.atTime(7, 0).atZone(ZoneId.systemDefault()))
        val moved = repository.snapshot().entries.single { it.entry.id == id }.entry
        assertEquals(first + 1, moved.deadline)
        assertEquals(8 * 60, moved.dueMinute)
        assertEquals(TimeOfDay.MORNING, moved.timeOfDay)
        assertNotNull(moved.flexibleSchedule)
    }

    @Test
    fun midnightAndTimezoneChangesUseTheReferenceForEachUtterance() = withDatabase { repository ->
        val instant = Instant.parse("2026-11-22T00:15:00Z")
        val london = instant.atZone(ZoneId.of("Europe/London"))
        val newYork = instant.atZone(ZoneId.of("America/New_York"))
        val phrase = "tomorrow morning"
        val raw = suggestion("Apply", phrase).put("startDate", newYork.toLocalDate().plusDays(1).toString())
        for (reference in listOf(london.toLocalDate(), newYork.toLocalDate(), london.minusMinutes(30).toLocalDate())) {
            val proposed = readSuggestion(raw, empty, phrase, reference)
            val id = repository.applyAssistantProposal(proposed)
            val saved = repository.snapshot().entries.single { it.entry.id == id }.entry
            assertEquals(reference.plusDays(1).toEpochDay(), saved.deadline)
            assertEquals(TimeOfDay.MORNING, saved.timeOfDay)
        }
        val monday = "next Monday morning"
        val proposed = readSuggestion(suggestion("Apply", monday), empty, monday, london.toLocalDate())
        assertEquals(LocalDate.of(2026, 11, 23).toEpochDay(), proposed.startDay)
    }

    private suspend fun create(session: WorkspaceToolSession, source: String, change: JSONObject): String {
        val args = JSONObject().put("operation", "CREATE").put("instructionEvidence", source).put("change", change)
        val result = session.invoke("apply_workspace_change", args, source).json
        assertTrue(result.toString(), result.getBoolean("applied"))
        return result.getString("targetId")
    }

    private fun suggestion(title: String, quote: String, kind: DraftKind = DraftKind.TASK) =
        JSONObject().put("kind", kind.name).put("title", title).put("body", "").put("priority", "NONE")
            .put("scheduleEvidence", quote)

    private fun withDatabase(block: suspend (BrainRepository) -> Unit) {
        runBlocking {
            withRepository(Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build(), block)
        }
    }

    private suspend fun withRepository(database: BrainDatabase, block: suspend (BrainRepository) -> Unit) {
        try {
            block(BrainRepository(database))
        } finally {
            database.close()
        }
    }
}
