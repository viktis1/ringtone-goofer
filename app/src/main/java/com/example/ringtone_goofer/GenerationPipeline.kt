package com.example.ringtone_goofer

/**
 * One bounded pass through generation. All dependencies are supplied so recovery can be
 * tested without Firebase or Android. A false result asks WorkManager to check again later.
 */
suspend fun advanceGeneration(
    snapshot: RingtoneItem,
    submit: suspend (GenerationRequest) -> GenerationSubmission,
    status: suspend (GenerationJob) -> GenerationStatus,
    save: suspend ((RingtoneItem) -> RingtoneItem) -> RingtoneItem,
    pause: suspend () -> Unit,
    maxChecks: Int = 24
): Boolean {
    var item = snapshot
    if (item.stage == "submitting") {
        val accepted = submit(item.request())
        item = save { it.copy(
            stage = if (accepted.job.status == "done") "downloading" else accepted.job.status,
            route = accepted.route, statusUrl = accepted.job.statusUrl,
            downloadUrl = accepted.job.downloadUrl, detail = ""
        ) }
    }
    if (item.stage == "downloading") return true
    repeat(maxChecks) {
        val result = try { status(item.serverJob()) } catch (e: ApiHttpException) {
            if (e.code != 404 || item.route != "Upload") throw e
            save { it.copy(detail = "Request saved in the cloud. Waiting for the server.") }
            return false
        }
        val next = when (result.status) {
            "done" -> "downloading"
            "queued", "running", "awaiting_worker", "failed" -> result.status
            else -> "failed"
        }
        item = save { it.copy(stage = next, resumeStage = "", generationStatusJson = result.json().toString(), detail =
            if (next == "failed" && result.status != "failed") "Unrecognized server status: ${result.status}"
            else result.detail) }
        if (next in setOf("downloading", "failed")) return true
        pause()
    }
    return false
}
