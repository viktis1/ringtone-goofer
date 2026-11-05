package com.example.ringtone_goofer

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONException
import org.junit.Assert.*
import org.junit.Test

class GenerationSubmissionTest {
    private val request = GenerationRequest("12345678-1234-4234-8234-123456789abc", "Alex", "Public:Voice", "clip")

    @Test fun healthyApiDoesNotUpload() = runBlocking {
        val job = GenerationJob("id", "/status/id", "/download/id", "queued")
        val result = submitGeneration(request, { job }, { fail("Unexpected upload") })
        assertEquals(job, result.job)
        assertEquals("API", result.route)
    }

    @Test fun unavailableServerUploadsSameRequestOnce() = runBlocking {
        for (failure in listOf(ApiConnectionException(IOException("timeout")), ApiHttpException(503, "offline"))) {
            val uploads = mutableListOf<GenerationRequest>()
            val result = submitGeneration(request, { throw failure }, { uploads.add(it) })
            assertEquals(listOf(request), uploads)
            assertEquals("Upload", result.route)
            assertEquals("/status/12345678123442348234123456789abc", result.job.statusUrl)
            assertEquals("awaiting_worker", result.job.status)
        }
    }

    @Test fun rejectedMalformedAuthAndCancelledRequestsDoNotUpload() = runBlocking {
        for (failure in listOf(ApiHttpException(400, "bad"), ApiHttpException(401, "auth"),
            ApiHttpException(403, "denied"), ApiHttpException(404, "missing"),
            ApiHttpException(422, "invalid"), ApiHttpException(429, "limit"),
            IOException("token unavailable"), JSONException("bad JSON"),
            CancellationException("cancelled"), javax.net.ssl.SSLException("certificate"))) {
            try {
                submitGeneration(request, { throw failure }, { fail("Unexpected upload for $failure") })
                fail("Expected failure")
            } catch (e: Exception) { assertSame(failure, e) }
        }
    }

    @Test fun uploadFailureIsReportedWithoutRetryingSubmission() = runBlocking {
        val failure = IOException("storage unavailable")
        try {
            submitGeneration(request, { throw ApiHttpException(502, "offline") }, { throw failure })
            fail("Expected upload failure")
        } catch (e: IOException) { assertSame(failure, e) }
    }
}
