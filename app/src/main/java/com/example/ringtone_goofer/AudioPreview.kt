package com.example.ringtone_goofer

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri

/** Playback belongs to the visible screen; saved audio belongs to the library. */
class AudioPreview(private val context: Context, private val message: (String) -> Unit) {
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
            message("Playing ringtone for ${contact.name}")
        } catch (e: Exception) { stop(); message("Cannot play ringtone: ${e.message}") }
    }

    fun stop() {
        player?.release()
        player = null
        ringtone?.stop()
        ringtone = null
    }
}
