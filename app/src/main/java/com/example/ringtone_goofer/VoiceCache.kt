package com.example.ringtone_goofer

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Call from Dispatchers.IO. Each Firebase UID has its own file; no tokens are stored. */
class VoiceCache(private val directory: File) {
    private fun file(uid: String): File {
        val key = MessageDigest.getInstance("SHA-256").digest(uid.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, "voices-$key.json")
    }

    fun read(uid: String): JSONObject? = try {
        val cached = JSONObject(file(uid).readText())
        if (cached.getString("uid") == uid) cached.getJSONObject("response") else null
    } catch (_: Exception) {
        // Missing or corrupt caches must not prevent a network refresh.
        null
    }

    fun write(uid: String, response: JSONObject) {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create voice cache")
        val target = file(uid)
        val temporary = File.createTempFile("voices-", ".tmp", directory)
        try {
            temporary.writeText(JSONObject().put("uid", uid).put("response", response).toString())
            // Replace only after the complete JSON has been written, preserving the last good cache.
            if (!temporary.renameTo(target)) throw IOException("Cannot replace voice cache")
        } finally {
            temporary.delete()
        }
    }
}
