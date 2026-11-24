package com.leo.secondbrain.ai

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** Speaks the committed receipt verbatim using an installed, offline voice. */
internal suspend fun speakWorkspaceReceipt(context: Context, text: String) {
    val initialized = CompletableDeferred<Int>()
    val engine = TextToSpeech(context.applicationContext) { initialized.complete(it) }
    try {
        withTimeoutOrNull(12_000) {
            if (initialized.await() != TextToSpeech.SUCCESS) return@withTimeoutOrNull
            val language = Locale.getDefault().language
            val voices = engine.voices.orEmpty().filter { !it.isNetworkConnectionRequired }
            val voice = voices.firstOrNull { it.locale == Locale.getDefault() }
                ?: voices.firstOrNull { it.locale.language == language }
                ?: voices.firstOrNull { it.locale.language == Locale.ENGLISH.language }
                ?: return@withTimeoutOrNull
            if (engine.setVoice(voice) != TextToSpeech.SUCCESS) return@withTimeoutOrNull
            engine.setAudioAttributes(conversationAudioAttributes)
            val spoken = CompletableDeferred<Unit>()
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { spoken.complete(Unit) }
                override fun onError(utteranceId: String?) { spoken.complete(Unit) }
            })
            if (engine.speak(text.take(TextToSpeech.getMaxSpeechInputLength()), TextToSpeech.QUEUE_FLUSH,
                    null, "workspace-receipt") == TextToSpeech.SUCCESS) spoken.await()
        }
    } finally {
        engine.stop()
        engine.shutdown()
    }
}
