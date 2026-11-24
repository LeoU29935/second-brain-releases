package com.leo.secondbrain.capture

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import com.leo.secondbrain.data.BrainDump
import com.leo.secondbrain.data.BrainRepository
import com.leo.secondbrain.data.newId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

internal class RecordingStore(context: Context) {
    private val directory = File(context.filesDir, "recordings")
    fun file(name: String): File {
        require(name.matches(Regex("[0-9a-f-]{36}\\.m4a")))
        check(directory.isDirectory || directory.mkdirs())
        return File(directory, name)
    }

    private val pending = context.getSharedPreferences("pending-recordings", Context.MODE_PRIVATE)

    fun pendingFiles() = pending.all.keys.mapNotNull { name ->
        runCatching { file(name) }.getOrNull()?.let { recording ->
            if (recording.isFile && recording.length() >= 256) recording
            else {
                recording.delete()
                saved(recording)
                null
            }
        }
    }.sortedBy(::startedAt)

    fun begin(file: File) = pending.edit().putLong(file.name, System.currentTimeMillis()).commit()

    fun startedAt(file: File) = (pending.all[file.name] as? Long) ?: file.lastModified()

    fun saved(file: File) { pending.edit().remove(file.name).apply() }
}

internal enum class RecordingResult { SAVED, CANCELED, EMPTY, ERROR }

internal data class RecordingState(
    val started: Long? = null,
    val saving: Boolean = false,
    val message: String? = null,
    val level: Float = 0f,
    val result: RecordingResult? = null,
    val captureId: String? = null
)

internal class CaptureController(
    private val context: Context, private val store: RecordingStore, private val repository: BrainRepository,
    private val onCaptured: () -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutable = MutableStateFlow(RecordingState())
    val state = mutable.asStateFlow()
    private var recorder: MediaRecorder? = null
    private var output: File? = null
    private var meter: Job? = null

    init {
        val pending = store.pendingFiles()
        if (pending.isNotEmpty()) {
            mutable.value = RecordingState(saving = true)
            scope.launch {
                for (file in pending) {
                    try {
                        storeDump(file)
                        store.saved(file)
                    } catch (error: CancellationException) {
                        throw error
                    } catch (_: Exception) {
                        output = file
                    }
                }
                mutable.value = RecordingState(message = if (output == null) "Recovered saved recordings."
                    else "Tap Retry saving to recover your recording.",
                    result = if (output == null) RecordingResult.SAVED else RecordingResult.ERROR,
                    captureId = pending.last().nameWithoutExtension.takeIf { output == null })
            }
        }
    }

    fun start() {
        if (recorder != null || output != null || mutable.value.saving) return
        val file = runCatching { store.file(newId() + ".m4a") }.getOrNull()
        val media = runCatching { if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else legacyRecorder() }.getOrNull()
        if (file == null || media == null) {
            media?.release()
            mutable.value = RecordingState(message = "Microphone unavailable", result = RecordingResult.ERROR)
            return
        }
        try {
            check(store.begin(file))
            media.setAudioSource(MediaRecorder.AudioSource.MIC)
            media.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            media.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            media.setAudioChannels(1)
            media.setAudioSamplingRate(24_000)
            media.setAudioEncodingBitRate(48_000)
            media.setMaxDuration(600_000)
            media.setOutputFile(file.absolutePath)
            media.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stop()
            }
            media.setOnErrorListener { source, _, _ -> if (recorder === source) stop(interrupted = true) }
            media.prepare()
            media.start()
            recorder = media
            output = file
            mutable.value = RecordingState(started = SystemClock.elapsedRealtime(), captureId = file.nameWithoutExtension)
            meter = scope.launch {
                delay(100)
                while (recorder === media) {
                    readLevel(media)
                    delay(100)
                }
            }
        } catch (_: Exception) {
            media.release()
            file.delete()
            store.saved(file)
            mutable.value = RecordingState(message = "Couldn’t start microphone", result = RecordingResult.ERROR)
        }
    }

    private fun readLevel(media: MediaRecorder) {
        val level = runCatching { media.maxAmplitude }.getOrNull() ?: return
        mutable.value = mutable.value.copy(level = (level / 7_000f).coerceIn(0f, 1f))
    }

    fun stop() = stop(interrupted = false)

    private fun stop(interrupted: Boolean) {
        val media = recorder ?: return
        val file = requireNotNull(output)
        meter?.cancel()
        readLevel(media)
        recorder = null
        output = null
        val durationReached = SystemClock.elapsedRealtime() - (mutable.value.started ?: 0) >= 599_000
        val stopped = runCatching { media.stop() }.isSuccess
        media.release()
        if (!file.exists() || file.length() < 256 || (!interrupted && !stopped && !durationReached && !playable(file))) {
            file.delete()
            store.saved(file)
            mutable.value = RecordingState(message = if (interrupted) "Recording interrupted" else "Recording too short",
                result = if (interrupted) RecordingResult.ERROR else RecordingResult.EMPTY)
            return
        }
        val id = file.nameWithoutExtension
        mutable.value = RecordingState(saving = true, captureId = id)
        scope.launch {
            try {
                storeDump(file)
                store.saved(file)
                mutable.value = RecordingState(message = "Recording saved", result = RecordingResult.SAVED, captureId = id)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                output = file
                mutable.value = RecordingState(message = "Retry saving to Brain Dump", result = RecordingResult.ERROR)
            }
        }
    }

    fun cancel() {
        val media = recorder ?: return
        val file = output
        meter?.cancel()
        recorder = null
        output = null
        runCatching { media.stop() }
        media.release()
        file?.let {
            it.delete()
            store.saved(it)
        }
        mutable.value = RecordingState(result = RecordingResult.CANCELED)
    }

    fun retrySave() {
        val file = output ?: return
        if (recorder != null || mutable.value.saving) return
        mutable.value = RecordingState(saving = true)
        scope.launch {
            try {
                for (pending in (listOf(file) + store.pendingFiles()).distinctBy(File::getName)) {
                    storeDump(pending)
                    store.saved(pending)
                }
                output = null
                mutable.value = RecordingState(message = "Recording saved", result = RecordingResult.SAVED,
                    captureId = file.nameWithoutExtension)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                mutable.value = RecordingState(message = "Couldn’t save recording", result = RecordingResult.ERROR)
            }
        }
    }

    val canRetry: Boolean get() = output != null && recorder == null

    private suspend fun storeDump(file: File) {
        val id = file.nameWithoutExtension
        repository.saveCapture(BrainDump(id = id, recording = file.name, createdAt = store.startedAt(file)))
        onCaptured()
    }

    private fun playable(file: File): Boolean = runCatching {
        val metadata = android.media.MediaMetadataRetriever()
        try {
            metadata.setDataSource(file.absolutePath)
            metadata.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.let { it > 0 } == true
        } finally {
            metadata.release()
        }
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    private fun legacyRecorder() = MediaRecorder()
}
