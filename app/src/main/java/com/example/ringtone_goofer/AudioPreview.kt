package com.example.ringtone_goofer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper

/** Playback belongs to the visible screen; saved audio belongs to the library. */
class AudioPreview(
    private val context: Context,
    private val message: (String) -> Unit,
    private val playbackChanged: (Boolean) -> Unit = {}
) {
    private val handler = Handler(Looper.getMainLooper())
    val isActive: Boolean get() = player != null || ringtone != null
    private var player: MediaPlayer? = null
    private var ringtone: Ringtone? = null

    fun play(uri: Uri, description: String) {
        stop()
        val audio = MediaPlayer()
        player = audio
        try {
            audio.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            audio.setDataSource(context, uri)
            audio.setOnPreparedListener {
                if (player === it) { it.start(); message("Playing $description") }
            }
            audio.setOnCompletionListener {
                if (player === it) { stop(); message("Playback finished.") }
            }
            audio.setOnErrorListener { failed, _, _ ->
                if (player === failed) { stop(); message("Audio is unavailable. Try downloading it again.") }
                true
            }
            audio.prepareAsync()
            playbackChanged(true)
        } catch (e: Exception) {
            stop()
            message("Cannot play audio: ${e.message}")
        }
    }

    fun playContact(contact: AddressBookContact) {
        stop()
        try {
            val uri = contact.ringtone ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(context, uri)
            ringtone?.play() ?: error("Ringtone unavailable")
            playbackChanged(true)
            message("Playing ringtone for ${contact.name}")
            // Ringtone has no completion callback. Watch it so the UI resets when it ends.
            handler.postDelayed(object : Runnable {
                override fun run() {
                    val current = ringtone ?: return
                    if (current.isPlaying) handler.postDelayed(this, 250)
                    else { stop(); message("Playback finished.") }
                }
            }, 250)
        } catch (e: Exception) { stop(); message("Cannot play ringtone: ${e.message}") }
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        ringtone?.stop()
        ringtone = null
        playbackChanged(false)
    }
}
