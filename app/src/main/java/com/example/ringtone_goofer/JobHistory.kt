package com.example.ringtone_goofer

import java.io.File

internal data class SubmittedJob(
    val id: String, val caller: String, val route: String, val status: String,
    val detail: String, val created: Long
)

/** Read-only migration from the old JSON history. All new writes go to RingtoneLibrary. */
internal class JobHistory(directory: File) {
    private val cache = VoiceCache(directory)

    fun read(uid: String): List<SubmittedJob> {
        val rows = cache.read(uid)?.optJSONArray("jobs") ?: return emptyList()
        return runCatching {
            List(rows.length()) {
                val row = rows.getJSONObject(it)
                SubmittedJob(row.getString("id"), row.getString("caller"), row.getString("route"),
                    row.getString("status"), row.optString("detail"), row.getLong("created"))
            }
        }.getOrDefault(emptyList())
    }
}
