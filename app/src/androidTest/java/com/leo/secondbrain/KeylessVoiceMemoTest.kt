package com.leo.secondbrain

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.leo.secondbrain.ai.AiUnavailable
import com.leo.secondbrain.ai.CaptureProcessor
import com.leo.secondbrain.ai.LocalTranscription
import com.leo.secondbrain.ai.readChatGptStream
import com.leo.secondbrain.data.BrainDatabase
import com.leo.secondbrain.data.BrainDump
import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.CaptureStatus
import com.leo.secondbrain.data.DraftKind
import com.leo.secondbrain.data.Proposal
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KeylessVoiceMemoTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test
    fun shortAndLongSavedAacMemosTranscribeOnDevice() = runBlocking {
        val speech = LocalTranscription(context.assets)
        withRecording("short") { recording ->
            val text = speech.transcribe(recording)
            assertTrue(text, text.contains("blue guitar", ignoreCase = true))
            assertTrue(text, text.contains("rehearsal", ignoreCase = true))
            assertTrue(text, text.contains("microphone", ignoreCase = true))
        }
        withRecording("long") { recording ->
            val text = speech.transcribe(recording)
            assertTrue(text, text.contains("blue guitar", ignoreCase = true))
            assertTrue(text, text.contains("return the microphone", ignoreCase = true))
        }
    }

    @Test
    fun planLimitKeepsAudioAndTranscriptAndRetryCreatesExactlyOneOfEachItem() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, BrainDatabase::class.java).build()
        try {
            withRecording("short") { recording ->
                val original = recording.readBytes()
                val repository = BrainRepository(db)
                val source = BrainDump(recording = recording.name)
                repository.saveCapture(source)
                val speech = LocalTranscription(context.assets)
                var transcriptions = 0
                var inferences = 0
                var reminders = 0
                val tomorrow = LocalDate.now().plusDays(1).toEpochDay()
                val worker = CaptureProcessor(repository, { processed ->
                    val onDisk = repository.release.dump(source.id)
                    assertEquals(processed.transcript, onDisk?.transcript)
                    assertFalse(onDisk?.transcript.isNullOrBlank())
                    if (++inferences == 1) {
                        readChatGptStream(Buffer().writeUtf8(event("response.failed",
                            JSONObject().put("response", JSONObject().put("error",
                                JSONObject().put("code", "subscription_sharing_usage_limit_exceeded"))))))
                    }
                    listOf(
                        Proposal(DraftKind.NOTE, "Blue guitar at the studio"),
                        Proposal(DraftKind.EVENT, "Rehearsal", startDay = tomorrow, endDay = tomorrow, startMinute = 14 * 60),
                        Proposal(DraftKind.TASK, "Return the microphone", startDay = tomorrow,
                            startMinute = 19 * 60, reminder = 30, inferredSchedule = "fixture")
                    )
                }, transcribe = {
                    transcriptions++
                    speech.transcribe(recording)
                }) {
                    assertEquals(CaptureStatus.VERIFYING, repository.release.captureRun(source.id)?.status)
                    assertEquals(30, repository.reminderSnapshot().first.single().reminder)
                    reminders++
                }
                assertFalse(worker.drain())
                assertEquals(CaptureStatus.FAILED, repository.release.captureRun(source.id)?.status)
                assertTrue(repository.release.captureRun(source.id)?.message.orEmpty().contains("usage limit"))
                assertTrue(repository.snapshot().proposals().isEmpty())
                assertArrayEquals(original, recording.readBytes())
                repository.retryCapture(source.id)
                assertFalse(worker.drain())
                assertFalse(worker.drain())
                assertEquals(CaptureStatus.ORGANIZED, repository.release.captureRun(source.id)?.status)
                assertEquals(DraftKind.entries.toSet(), repository.snapshot().proposals().map { it.kind }.toSet())
                assertEquals(3, repository.snapshot().proposals().size)
                assertEquals(1, transcriptions)
                assertEquals(2, inferences)
                assertEquals(1, reminders)
                assertArrayEquals(original, recording.readBytes())
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun partialStreamCannotProduceSuccessfulOrganization() {
        val content = JSONObject().put("delta", """{"suggestions":[]}""")
        val partial = event("response.output_text.delta", content)
        val truncated = runCatching { readChatGptStream(Buffer().writeUtf8(partial)) }.exceptionOrNull()
        assertTrue(truncated is AiUnavailable && truncated.retryLater)
        val completed = event("response.completed", JSONObject().put("response", JSONObject().put("status", "completed")))
        assertEquals(0, readChatGptStream(Buffer().writeUtf8(partial + completed)).getJSONArray("suggestions").length())
    }

    private suspend fun withRecording(name: String, action: suspend (File) -> Unit) {
        val file = File.createTempFile("synthetic-", ".m4a", context.cacheDir)
        try {
            instrumentation.context.assets.open("voice/$name.m4a").use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            action(file)
        } finally {
            file.delete()
        }
    }

    private fun event(type: String, value: JSONObject) = "data: ${value.put("type", type)}\n\n"
}
