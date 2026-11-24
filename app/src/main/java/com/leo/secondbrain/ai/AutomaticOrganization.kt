package com.leo.secondbrain.ai

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.util.Log
import com.leo.secondbrain.BrainApplication
import com.leo.secondbrain.data.BrainDump
import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.Proposal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex

internal class AutomaticOrganization(private val app: BrainApplication) {
    private val foreground = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val worker = CaptureProcessor(app.repository, app.organizer::suggest, transcribe = { name ->
        app.localTranscription.transcribe(app.recordings.file(name))
    }) {
        app.reminders.refresh()
    }
    val processing = worker.processing

    fun kick() {
        runCatching { schedule(app) }.onFailure { Log.w("SecondBrain", "Could not schedule organization", it) }
        foreground.launch {
            try {
                drain()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("SecondBrain", "Foreground organization will retry", error)
            }
        }
    }

    suspend fun drain(): Boolean = worker.drain()

    companion object {
        private const val JOB_ID = 31402

        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, OrganizationJob::class.java))
                .setBackoffCriteria(30_000L, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
                .setPersisted(true)
                .build()
            scheduler.schedule(job)
        }
    }
}

internal class CaptureProcessor(
    private val repository: BrainRepository,
    private val suggest: suspend (BrainDump) -> List<Proposal>,
    private val draftOnly: Boolean = false,
    private val transcribe: (suspend (String) -> String)? = null,
    private val onCommitted: (suspend () -> Unit)? = null
) {
    private val mutex = Mutex()
    private val active = MutableStateFlow<String?>(null)
    val processing = active.asStateFlow()

    suspend fun drain(): Boolean {
        if (!mutex.tryLock()) return true
        try {
            var retry = false
            val queued = repository.release.queuedCaptures()
            val prepared = queued.mapNotNull { capture ->
                if (transcribe == null || capture.recording == null || !capture.transcript.isNullOrBlank()) {
                    return@mapNotNull capture
                }
                active.value = capture.id
                try {
                    val transcript = transcribe.invoke(capture.recording)
                    repository.release.transcript(capture.id, transcript)
                    capture.copy(transcript = transcript)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: AiUnavailable) {
                    repository.captureFailure(capture.id, error.message.orEmpty(), false)
                    null
                }
            }
            for (capture in prepared) {
                active.value = capture.id
                try {
                    repository.organizeCapture(capture.id, suggest(capture), draftOnly, verifyReminders = onCommitted != null)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: AiUnavailable) {
                    retry = repository.captureFailure(capture.id, error.message.orEmpty(), error.retryLater)
                    if (retry) break
                } catch (error: Exception) {
                    Log.w("SecondBrain", "Could not organize a capture", error)
                    repository.captureFailure(capture.id, "Couldn’t organize this capture. Your original is saved.", false)
                }
            }
            val awaitingReminders = repository.release.capturesAwaitingReminders().mapTo(mutableSetOf()) { it.dumpId }
            if (awaitingReminders.isNotEmpty() && onCommitted != null) {
                try {
                    onCommitted()
                    repository.finishCaptureVerification(awaitingReminders)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    Log.w("SecondBrain", "Capture reminders will be reconciled again", error)
                    repository.finishCaptureVerification(awaitingReminders, "Items saved. Reminder setup will retry.")
                    return true
                }
            }
            return retry
        } finally {
            active.value = null
            mutex.unlock()
        }
    }
}

internal class OrganizationJob : JobService() {
    private var work: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        work = CoroutineScope(Dispatchers.Main).launch {
            val retry = try {
                (application as BrainApplication).automaticOrganization.drain()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("SecondBrain", "Background organization will retry", error)
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
}
