package com.example.ringtone_goofer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SubmittedJob(
    val id: String, val caller: String, val route: String, val status: String,
    val detail: String = "", val created: Long = System.currentTimeMillis()
) {
    // The worker accepts a hyphenated UUID but the orchestrator stores UUID.hex.
    fun serverJob(): GenerationJob {
        val serverId = id.replace("-", "")
        return GenerationJob(serverId, "/status/$serverId", "/download/$serverId", status)
    }

    val finished: Boolean get() = status in listOf("done", "failed", "submission_failed")
}

/** Internal, per-user history of this installation. Call on Dispatchers.IO. */
class JobHistory(directory: File) {
    private val cache = VoiceCache(directory)

    @Synchronized fun read(uid: String): List<SubmittedJob> {
        val rows = cache.read(uid)?.optJSONArray("jobs") ?: return emptyList()
        return try {
            List(rows.length()) {
                val row = rows.getJSONObject(it)
                SubmittedJob(row.getString("id"), row.getString("caller"), row.getString("route"),
                    row.getString("status"), row.optString("detail"), row.getLong("created"))
            }
        } catch (_: Exception) { emptyList() }
    }

    @Synchronized fun put(uid: String, job: SubmittedJob): List<SubmittedJob> {
        val jobs = (read(uid).filterNot { it.id == job.id } + job).sortedByDescending { it.created }
        val rows = JSONArray()
        jobs.forEach { rows.put(JSONObject().put("id", it.id).put("caller", it.caller)
            .put("route", it.route).put("status", it.status).put("detail", it.detail).put("created", it.created)) }
        cache.write(uid, JSONObject().put("jobs", rows))
        return jobs
    }
}
