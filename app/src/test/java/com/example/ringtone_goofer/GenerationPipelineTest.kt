package com.example.ringtone_goofer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GenerationPipelineTest {
    private val initial = RingtoneItem("12345678-1234-4234-8234-123456789abc", "alice",
        "content://contacts/lookup/alex/1", "Alex", "Public:Voice", "Voice", "clip", "Sample")
    private val accepted = GenerationJob("server-id", "/status/custom", "/download/custom", "queued")

    /** JSON round trips model reloading the committed checkpoint into a new process. */
    private fun reload(item: RingtoneItem) = RingtoneItem.fromJson(JSONObject(item.json().toString()))

    @Test fun processDeathAfterSubmissionResumesStatusWithoutResubmitting() = runBlocking {
        var disk = initial
        var submits = 0
        try {
            advanceGeneration(initial,
                submit = { submits++; GenerationSubmission(accepted, "API") },
                status = { throw CancellationException("Process stopped") },
                save = { change -> change(disk).also { disk = reload(it) } }, pause = {})
            fail("Expected interruption")
        } catch (_: CancellationException) { }
        assertEquals("queued", disk.stage)
        assertEquals("/status/custom", disk.statusUrl)

        val finished = advanceGeneration(reload(disk),
            submit = { fail("An accepted job must not be resubmitted"); error("unreachable") },
            status = {
                assertEquals("/status/custom", it.statusUrl)
                GenerationStatus("done", null)
            }, save = { change -> change(disk).also { disk = reload(it) } }, pause = {})
        assertTrue(finished)
        assertEquals(1, submits)
        assertEquals("ready", disk.stage)
        assertEquals(initial.contactUri, disk.contactUri)
        assertEquals(initial.clipId, disk.clipId)
    }

    @Test fun uncertainSubmissionReusesOriginalIdOnNextRun() = runBlocking {
        var disk = initial
        val requests = mutableListOf<GenerationRequest>()
        try {
            advanceGeneration(initial, submit = {
                requests.add(it); throw CancellationException("Lost response")
            }, status = { error("Not accepted locally yet") },
                save = { change -> change(disk).also { disk = reload(it) } }, pause = {})
        } catch (_: CancellationException) { }
        advanceGeneration(reload(disk),
            submit = { requests.add(it); GenerationSubmission(accepted.copy(status = "done"), "API") },
            status = { error("Already done") },
            save = { change -> change(disk).also { disk = reload(it) } }, pause = {})
        assertEquals(listOf(initial.request(), initial.request()), requests)
        assertEquals("ready", disk.stage)
    }

    @Test fun cloudRequestNotYetOnServerWaitsWithoutGeneratingAgain() = runBlocking {
        var disk = initial.copy(stage = "awaiting_worker", route = "Upload")
        assertFalse(advanceGeneration(disk,
            submit = { error("Cloud request already submitted") },
            status = { throw ApiHttpException(404, "Not yet ingested") },
            save = { change -> change(disk).also { disk = reload(it) } }, pause = {}))
        assertEquals("awaiting_worker", disk.stage)
        assertTrue(disk.detail.contains("cloud"))
    }

    @Test fun boundedPollingSavesRealStagesAndFailureIsTerminal() = runBlocking {
        var disk = initial.copy(stage = "queued")
        var checks = 0
        assertFalse(advanceGeneration(disk, submit = { error("Already submitted") },
            status = { checks++; GenerationStatus("running", null, "completed", "running") },
            save = { change -> change(disk).also { disk = reload(it) } }, pause = {}, maxChecks = 2))
        assertEquals(2, checks)
        assertEquals("Creating audio · running", disk.detail)
        assertTrue(advanceGeneration(disk, submit = { error("Already submitted") },
            status = { GenerationStatus("failed", "GPU unavailable") },
            save = { change -> change(disk).also { disk = reload(it) } }, pause = {}))
        assertEquals("failed", disk.stage)
        assertEquals("GPU unavailable", disk.detail)
        assertEquals("", disk.resumeStage) // A server failure needs a new UUID, not a submit retry.
    }

    @Test fun completedItemKeepsDownloadAndAssignmentStateAcrossRestarts() {
        for (stage in listOf("ready", "downloading", "downloaded", "assigning", "assigned")) {
            val item = initial.copy(stage = stage, mediaUri = "content://media/external/audio/media/9")
            assertEquals(item, reload(item))
        }
        val draft = RingtoneDraft(initial.contactUri, initial.contactName, initial.voiceId, initial.clipId)
        assertEquals(draft, RingtoneDraft.fromJson(JSONObject(draft.json().toString())))
    }
}
