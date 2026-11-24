package com.leo.secondbrain.ai

import android.content.res.AssetManager
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class LocalTranscription(private val assets: AssetManager) {
    suspend fun transcribe(recording: File): String = withContext(Dispatchers.IO) {
        try {
            val model = "sherpa-onnx-whisper-tiny"
            val recognizer = OfflineRecognizer(assets, OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = "$model/tiny-encoder.int8.onnx",
                        decoder = "$model/tiny-decoder.int8.onnx",
                        task = "transcribe"
                    ),
                    tokens = "$model/tiny-tokens.txt",
                    numThreads = 2
                )
            ))
            try {
                val parts = mutableListOf<String>()
                decodeRecording(recording) { samples, sampleRate ->
                    ensureActive()
                    if (samples.any { kotlin.math.abs(it) >= 0.001f }) {
                        val stream = recognizer.createStream()
                        try {
                            stream.acceptWaveform(samples, sampleRate)
                            recognizer.decode(stream)
                            ensureActive()
                            recognizer.getResult(stream).text.trim().takeIf(String::isNotBlank)?.let(parts::add)
                        } finally {
                            stream.release()
                        }
                    }
                }
                parts.joinToString(" ").ifBlank {
                    throw AiUnavailable("No speech was recognized on this device. Your recording is saved.")
                }
            } finally {
                recognizer.release()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: AiUnavailable) {
            throw error
        } catch (_: Exception) {
            throw AiUnavailable("Couldn’t transcribe this recording on this device. Your recording is saved.")
        } catch (_: LinkageError) {
            throw AiUnavailable("On-device transcription is unavailable on this device. Your recording is saved.")
        }
    }
}

private suspend fun decodeRecording(file: File, onSegment: (FloatArray, Int) -> Unit) =
    withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val track = (0 until extractor.trackCount).first { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(requireNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var pcm: SpeechSegments? = null
                var inputEnded = false
                var outputEnded = false
                while (!outputEnded) {
                    ensureActive()
                    if (!inputEnded) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val input = requireNotNull(codec.getInputBuffer(index))
                            val size = extractor.readSampleData(input, 0)
                            inputEnded = size < 0
                            codec.queueInputBuffer(index, 0, size.coerceAtLeast(0),
                                extractor.sampleTime.coerceAtLeast(0),
                                if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0)
                            if (!inputEnded) extractor.advance()
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = codec.outputFormat
                        val rate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        val channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        val encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else AudioFormat.ENCODING_PCM_16BIT
                        require(pcm == null) { "The recording changed PCM formats." }
                        pcm = SpeechSegments(rate, channels, encoding, onSegment)
                    } else if (index >= 0) {
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val buffer = requireNotNull(codec.getOutputBuffer(index))
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                requireNotNull(pcm).append(buffer.order(ByteOrder.nativeOrder()))
                            }
                            outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                    }
                }
                pcm?.finish()
            } finally {
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

private class SpeechSegments(
    private val sampleRate: Int, private val channels: Int, encoding: Int,
    private val onSegment: (FloatArray, Int) -> Unit
) {
    private val floatPcm = encoding == AudioFormat.ENCODING_PCM_FLOAT
    private val frameBytes = channels * if (floatPcm) 4 else 2
    private val samples: FloatArray
    private var count = 0

    init {
        require(sampleRate in 8_000..192_000 && channels in 1..8)
        require(floatPcm || encoding == AudioFormat.ENCODING_PCM_16BIT)
        samples = FloatArray(sampleRate * 22)
    }

    fun append(buffer: ByteBuffer) {
        require(buffer.remaining() % frameBytes == 0)
        while (buffer.remaining() >= frameBytes) {
            var sample = 0f
            repeat(channels) {
                sample += if (floatPcm) buffer.float else buffer.short / 32768f
            }
            samples[count++] = sample / channels
            if (count == samples.size) emit(quietCut())
        }
    }

    fun finish() {
        if (count > 0) emit(count)
    }

    // Prefer a pause around the 20-second mark so the model gets full words at segment boundaries.
    private fun quietCut(): Int {
        val window = sampleRate / 40
        var best = sampleRate * 20
        var lowest = Float.POSITIVE_INFINITY
        for (start in sampleRate * 18 until count - window step window) {
            var energy = 0f
            for (index in start until start + window) energy += samples[index] * samples[index]
            if (energy < lowest) {
                best = start + window / 2
                lowest = energy
            }
        }
        return best
    }

    private fun emit(end: Int) {
        onSegment(samples.copyOf(end), sampleRate)
        samples.copyInto(samples, startIndex = end, endIndex = count)
        count -= end
    }
}
