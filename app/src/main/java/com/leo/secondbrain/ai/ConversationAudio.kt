package com.leo.secondbrain.ai

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build

internal val conversationAudioAttributes: AudioAttributes
    get() = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

internal class ConversationAudioRoute(context: Context, onFocusLost: () -> Unit) : AutoCloseable {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val previousMode = audio.mode
    private val previousRoute = if (Build.VERSION.SDK_INT >= 31) audio.communicationDevice else null
    @Suppress("DEPRECATION")
    private val previousSpeaker = audio.isSpeakerphoneOn
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(conversationAudioAttributes)
        .setOnAudioFocusChangeListener { if (it < 0 && it != AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) onFocusLost() }
        .build()
    private var changedRoute = false
    private var active = false

    @Suppress("DEPRECATION")
    fun start(): Boolean {
        if (audio.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        active = true
        return runCatching {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            if (Build.VERSION.SDK_INT >= 31) {
                val current = audio.communicationDevice
                if (current == null || current.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE) {
                    val routes = audio.availableCommunicationDevices
                    val device = routes.firstOrNull { it.type in externalRoutes }
                        ?: routes.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (device != null) changedRoute = audio.setCommunicationDevice(device)
                }
            } else if (audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).none { it.type in externalRoutes }) {
                audio.isSpeakerphoneOn = true
                changedRoute = true
            }
            true
        }.getOrElse { close(); false }
    }

    @Suppress("DEPRECATION")
    override fun close() {
        if (!active) return
        active = false
        runCatching {
            if (changedRoute) {
                if (Build.VERSION.SDK_INT >= 31) {
                    audio.clearCommunicationDevice()
                    if (previousRoute != null && previousRoute in audio.availableCommunicationDevices) {
                        audio.setCommunicationDevice(previousRoute)
                    }
                } else audio.isSpeakerphoneOn = previousSpeaker
            }
            audio.mode = previousMode
        }
        audio.abandonAudioFocusRequest(request)
    }

    private companion object {
        val externalRoutes = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_HEARING_AID, AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET)
    }
}

/** Capture stays local while output is queued or physically playing. A tap explicitly interrupts. */
internal class EchoGate {
    private var streaming = false
    private var queued = 0
    private var written = 0L
    private var wasPlaying = false
    private var quietSince = Long.MIN_VALUE
    private var epoch = 0

    @Synchronized
    fun enqueue(): Int {
        streaming = true
        queued++
        return epoch
    }

    @Synchronized
    fun isCurrent(packet: Int) = packet == epoch

    @Synchronized
    fun wrote(packet: Int, frames: Int) {
        if (packet == epoch) {
            written += frames
            queued--
        }
    }

    @Synchronized
    fun finish() {
        streaming = false
    }

    @Synchronized
    fun interrupt(now: Long) {
        epoch++
        streaming = false
        queued = 0
        written = 0
        wasPlaying = false
        quietSince = now
    }

    @Synchronized
    fun blocks(playbackHead: Long, now: Long, tailMs: Long): Boolean {
        if (streaming || queued > 0 || written > playbackHead) {
            wasPlaying = true
            return true
        }
        if (wasPlaying) {
            wasPlaying = false
            quietSince = now
        }
        return quietSince != Long.MIN_VALUE && now - quietSince < tailMs
    }
}
