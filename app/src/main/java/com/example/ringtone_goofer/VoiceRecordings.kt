package com.example.ringtone_goofer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/** A per-account mirror of uploaded clips. Local microphone recordings remain untouched. */
internal class VoiceRecordings(private val directory: File) {
    private fun key(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }

    internal fun file(uid: String, clip: VoiceClip) =
        File(File(directory, key(uid)), key(clip.id) + ".audio")

    suspend fun sync(
        uid: String, voices: List<Voice>,
        download: suspend (Voice, VoiceClip, File) -> Unit
    ): Int = withContext(Dispatchers.IO) {
        var restored = 0
        for (voice in voices.filter { it.category == "Your Uploads" }) {
            for (clip in voice.clips) {
                ensureActive()
                val target = file(uid, clip)
                if (target.isFile && target.length() > 0) continue
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                val partial = File.createTempFile("download-", ".part", target.parentFile)
                try {
                    download(voice, clip, partial)
                    ensureActive()
                    check(partial.length() > 0) { "Server returned an empty recording." }
                    check(partial.renameTo(target)) { "Cannot save voice recording." }
                    restored++
                } finally {
                    partial.delete()
                }
            }
        }
        restored
    }

    /** Prefer the newest upload, while preserving a newer, not-yet-uploaded phone recording. */
    fun forContact(uid: String, name: String, voices: List<Voice>, local: File? = null): File? {
        val phone = local?.takeIf { it.isFile && it.length() > 0 }
        val clip = voices.asSequence()
            .filter { it.category == "Your Uploads" && it.isAvailableForRecipient(name) }
            .flatMap { it.clips.asSequence() }.sortedByDescending { it.createdAt }
            .firstOrNull { file(uid, it).let { audio -> audio.isFile && audio.length() > 0 } }
            ?: return phone
        if (phone != null && (clip.createdAt == null ||
                !clip.createdAt.isAfter(Instant.ofEpochMilli(phone.lastModified())))) return phone
        return file(uid, clip)
    }
}
