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
import kotlin.coroutines.coroutineContext

data class VoiceClip(val id: String, val name: String)
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
    val jobId: String, val caller: String, val voiceId: String, val clipId: String
)
data class GenerationJob(val id: String, val statusUrl: String, val downloadUrl: String, val status: String)
data class GenerationStatus(val status: String, val error: String?, val scriptStatus: String = "", val audioStatus: String = "") {
    val detail: String get() = error ?: when {
        audioStatus.isNotBlank() -> "Creating audio · $audioStatus"
        scriptStatus.isNotBlank() -> "Writing script · $scriptStatus"
        status == "queued" -> "Waiting for a generation slot"
        else -> ""
    }
}
class ApiHttpException(val code: Int, detail: String) : IOException("HTTP $code: $detail")
class ApiConnectionException(cause: IOException) : IOException(cause.message, cause)

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
        } catch (e: IOException) {
            if (e is ApiHttpException || e is javax.net.ssl.SSLException) throw e
            throw ApiConnectionException(e)
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
                    VoiceClip(clip.getString("id"), clip.getString("name"))
                })
            }
        }.toList()
    }

    suspend fun preview(voice: Voice, clip: VoiceClip, target: File) {
        val voiceId = URLEncoder.encode(voice.id, "UTF-8")
        val clipId = URLEncoder.encode(clip.id, "UTF-8")
        request("/voice-preview?voice_id=$voiceId&clip_id=$clipId") { connection ->
            val type = connection.contentType.orEmpty().substringBefore(';')
            if (type !in listOf("audio/mpeg", "audio/mp3", "audio/wav", "audio/x-wav", "audio/wave")) {
                throw IOException("Expected preview audio, received $type")
            }
            copyAudio(connection, target)
        }
    }

    suspend fun generate(request: GenerationRequest): GenerationJob {
        val result = json("/generate", JSONObject()
            .put("job_id", request.jobId).put("caller", request.caller).put("receiver", "viktor")
            .put("voice_id", request.voiceId).put("clip_id", request.clipId))
        return GenerationJob(result.getString("job_id"), result.getString("status_url"),
            result.getString("download_url"), result.getString("status"))
    }

    suspend fun status(job: GenerationJob): GenerationStatus {
        val result = json(job.statusUrl)
        return GenerationStatus(result.getString("status"),
            result.optString("error").takeIf { it.isNotBlank() && it != "null" },
            result.optString("llm_status"), result.optString("tts_status"))
    }

    suspend fun download(job: GenerationJob, target: File) = request(job.downloadUrl) { connection ->
        val type = connection.contentType.orEmpty().substringBefore(';')
        if (type !in listOf("audio/wav", "audio/x-wav", "audio/wave", "application/octet-stream")) {
            throw IOException("Expected WAV audio, received $type")
        }
        copyAudio(connection, target)
        target.inputStream().use {
            val header = ByteArray(12)
            if (it.read(header) != 12 ||
                String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(header, 8, 4, Charsets.US_ASCII) != "WAVE") {
                throw IOException("Download is not a WAV file")
            }
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
                    if (total > 100 * 1024 * 1024) throw IOException("Audio exceeds 100 MB")
                    output.write(buffer, 0, count)
                }
                if (total == 0L) throw IOException("Server returned empty audio")
            }
        }
    }
}
