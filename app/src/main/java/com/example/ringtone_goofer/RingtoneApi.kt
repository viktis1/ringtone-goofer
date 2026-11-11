package com.example.ringtone_goofer

import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import kotlin.coroutines.coroutineContext

data class VoiceClip(val id: String, val name: String, val createdAt: Instant? = null)
data class Voice(val id: String, val name: String, val clips: List<VoiceClip>) {
    val category: String get() = id.substringBefore(':')
    val displayName: String get() = id.substringAfter(':').replace('_', ' ')

    fun isAvailableForRecipient(contactName: String): Boolean {
        if (category == "Public") return true
        if (category != "Your Uploads" || contactName.isBlank()) return false
        fun normalized(name: String) = name.trim().replace(Regex("[\\s_-]+"), " ")
        val person = normalized(id.substringAfter(':'))
        val uploadName = contactRecordingFile(File("."), contactName).nameWithoutExtension
        return person.equals(normalized(contactName), ignoreCase = true) ||
            person.equals(normalized(uploadName), ignoreCase = true)
    }
}
data class GenerationRequest(
    val jobId: String, val caller: String, val voiceId: String, val clipId: String,
    val language: String = "auto"
) {
    fun payload() = JSONObject().put("receiver", "viktor").put("caller", caller)
        .put("voice_id", voiceId).put("clip_id", clipId).put("language", language)
}
data class GenerationJob(val id: String, val statusUrl: String, val downloadUrl: String, val status: String)
data class GenerationStatus(
    val status: String, val error: String?, val scriptStatus: String = "",
    val audioStatus: String = "", val llamaStatus: String = "",
    val llamaDurationSeconds: Double? = null, val ttsDurationSeconds: Double? = null,
    val responseJson: String = ""
) {
    val detail: String get() = buildList {
        error?.takeIf { it.isNotBlank() }?.let { add(it) }
        if (scriptStatus.isNotBlank()) add("Writing script (LLM) · $scriptStatus")
        if (llamaStatus.isNotBlank()) add("Script model (Llama) · $llamaStatus")
        if (audioStatus.isNotBlank()) add("Creating audio (TTS) · $audioStatus")
        llamaDurationSeconds?.let { add("Script duration · $it s") }
        ttsDurationSeconds?.let { add("Audio duration · $it s") }
        if (isEmpty() && status == "queued") add("Waiting for a generation slot")
    }.joinToString("\n")

    fun json(): JSONObject {
        if (responseJson.isNotBlank()) return JSONObject(responseJson)
        return JSONObject().apply {
        put("status", status); put("error", error ?: JSONObject.NULL)
        put("llm_status", scriptStatus); put("llama_status", llamaStatus); put("tts_status", audioStatus)
        put("llama_duration_seconds", llamaDurationSeconds ?: JSONObject.NULL)
        put("tts_duration_seconds", ttsDurationSeconds ?: JSONObject.NULL)
        }
    }

    /** Enumerate the response without a field allowlist; nested values become labeled rows. */
    val responseFields: List<Pair<String, String>> get() = buildList {
        fun append(key: String, value: Any?) {
            when (value) {
                null, JSONObject.NULL -> add(key to "Not set")
                is JSONObject -> {
                    if (value.length() == 0) add(key to "Empty")
                    else value.keys().asSequence().sorted().forEach { child ->
                        append(if (key.isEmpty()) child else "$key · $child", value.get(child))
                    }
                }
                is org.json.JSONArray -> {
                    if (value.length() == 0) add(key to "Empty")
                    else for (index in 0 until value.length()) append("$key · ${index + 1}", value.get(index))
                }
                else -> add(key to value.toString().ifEmpty { "Empty" })
            }
        }
        append("", json())
    }

    companion object {
        fun fromJson(j: JSONObject): GenerationStatus {
            fun text(key: String) = if (j.isNull(key)) "" else j.optString(key)
            fun duration(key: String) = j.optDouble(key).takeIf { it.isFinite() && it >= 0 }
            return GenerationStatus(j.getString("status"), text("error").takeIf { it.isNotBlank() },
                text("llm_status"), text("tts_status"), text("llama_status"),
                duration("llama_duration_seconds"), duration("tts_duration_seconds"), j.toString())
        }
    }
}
class ApiHttpException(val code: Int, detail: String) : IOException(
    if (code == 530) "Server is offline (HTTP 530)."
    else "HTTP $code" + runCatching { JSONObject(detail).opt("detail") }
        .getOrNull()?.let { ": $it" }.orEmpty()
)

/** Fields and paths follow the server's /openapi.json (including its prose response contracts). */
class RingtoneApi(
    private val baseUrl: String = BASE_URL,
    private val idToken: suspend () -> String = {
        FirebaseAuth.getInstance().currentUser?.getIdToken(false)?.await()?.token
            ?: throw IOException("Sign in with Google first.")
    }
) {
    companion object {
        const val BASE_URL = "https://create-ringtone.viktor-homelab.cloud"
    }

    private suspend fun <T> request(
        path: String, body: JSONObject? = null, read: suspend (HttpURLConnection) -> T
    ): T = withContext(Dispatchers.IO) {
        val base = URL(baseUrl)
        val url = URL(base, path)
        require(url.protocol == base.protocol && url.host == base.host && url.port == base.port && url.userInfo == null) {
            "Unexpected API URL"
        }
        val token = idToken() // Token/sign-in failures must not trigger the server-offline fallback.
        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 10_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val code = connection.responseCode
            if (code !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use { it.readText().take(1000) }.orEmpty()
                throw ApiHttpException(code, detail)
            }
            coroutineContext.ensureActive()
            read(connection)
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun json(path: String, body: JSONObject? = null) =
        request(path, body) { connection ->
            JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        }

    suspend fun voices(): List<Voice> = parseVoices(voicesJson())

    suspend fun voicesJson(): JSONObject = json("/voices")

    fun parseVoices(groups: JSONObject): List<Voice> {
        return groups.keys().asSequence().flatMap { category ->
            val people = groups.getJSONObject(category)
            people.keys().asSequence().map { contact ->
                val clips = people.getJSONObject(contact).getJSONArray("clips")
                Voice("$category:$contact", "$contact · $category", List(clips.length()) {
                    val clip = clips.getJSONObject(it)
                    VoiceClip(clip.getString("id"), clip.getString("name"),
                        clip.optString("created_at").takeIf { it.isNotBlank() && it != "null" }?.let { date -> OffsetDateTime.parse(date).toInstant() })
                })
            }
        }.toList()
    }

    suspend fun preview(voice: Voice, clip: VoiceClip, target: File) {
        val voiceId = URLEncoder.encode(voice.id, "UTF-8")
        val clipId = URLEncoder.encode(clip.id, "UTF-8")
        request("/voice-preview?voice_id=$voiceId&clip_id=$clipId") { connection ->
            val type = connection.contentType.orEmpty().substringBefore(';')
            check(type in listOf("audio/mpeg", "audio/wav")) { "Expected preview audio, received $type" }
            copyAudio(connection, target)
        }
    }

    suspend fun generate(request: GenerationRequest): GenerationJob {
        val result = json("/generate", request.payload().put("job_id", request.jobId))
        return GenerationJob(result.getString("job_id"), result.getString("status_url"),
            result.getString("download_url"), result.getString("status"))
    }

    suspend fun status(job: GenerationJob): GenerationStatus {
        val result = json(job.statusUrl)
        return GenerationStatus.fromJson(result)
    }

    suspend fun download(job: GenerationJob, target: File) = request(job.downloadUrl) { connection ->
        val type = connection.contentType.orEmpty().substringBefore(';')
        check(type == "audio/wav") { "Expected WAV audio, received $type" }
        copyAudio(connection, target)
        target.inputStream().use {
            val header = ByteArray(12)
            check(it.read(header) == 12 &&
                String(header, 0, 4, Charsets.US_ASCII) == "RIFF" &&
                String(header, 8, 4, Charsets.US_ASCII) == "WAVE") { "Download is not a WAV file" }
        }
    }

    private suspend fun copyAudio(connection: HttpURLConnection, target: File) {
        connection.inputStream.use { input ->
            target.outputStream().use { output ->
                val buffer = ByteArray(16 * 1024)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    check(total <= 100 * 1024 * 1024) { "Audio exceeds 100 MB" }
                    output.write(buffer, 0, count)
                }
                check(total > 0) { "Server returned empty audio" }
            }
        }
    }
}
