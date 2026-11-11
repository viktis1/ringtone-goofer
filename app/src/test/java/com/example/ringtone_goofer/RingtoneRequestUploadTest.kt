package com.example.ringtone_goofer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RingtoneRequestUploadTest {
    private val jobId = "12345678-1234-4234-8234-123456789abc"

    @Test fun pathMatchesWorkerAndUsesAuthenticatedUser() {
        assertEquals("users/firebase-user/$jobId.json", RingtoneRequestUpload.path("firebase-user", jobId))
    }

    @Test fun payloadMatchesWorkerAndPreservesSelectedIds() {
        val request = GenerationRequest(jobId, "Anne \"AJ\" & æ", "Your Uploads:Alex", "clip-uuid")
        val json = JSONObject(request.payload().toString())
        assertEquals(setOf("receiver", "caller", "voice_id", "clip_id", "language"), json.keys().asSequence().toSet())
        assertEquals("viktor", json.getString("receiver"))
        assertEquals(request.caller, json.getString("caller"))
        assertEquals(request.voiceId, json.getString("voice_id"))
        assertEquals(request.clipId, json.getString("clip_id"))
        assertFalse(json.has("job_id")) // The worker derives it from the filename.
    }

    @Test fun selectedLanguageSurvivesPersistenceAndSubmission() {
        for (language in listOf("auto", "da", "en", "zh", "ja")) {
            val draft = RingtoneDraft(language = language)
            assertEquals(language, RingtoneDraft.fromJson(JSONObject(draft.json().toString())).language)
            val item = RingtoneItem(jobId, "uid", "", "Alex", "Public:Alex", "Alex",
                "clip", "clip.wav", language = language)
            val restored = RingtoneItem.fromJson(JSONObject(item.json().toString()))
            assertEquals(language, restored.request().payload().getString("language"))
        }
        assertEquals("auto", RingtoneDraft.fromJson(JSONObject()).language)
        assertEquals("auto", GenerationRequest(jobId, "Alex", "voice", "clip").payload().getString("language"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNestedUserPaths() {
        RingtoneRequestUpload.path("another/user", jobId)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonUuidJobNames() {
        RingtoneRequestUpload.path("user", "bad-job")
    }
}
