package com.example.ringtone_goofer

data class GenerationSubmission(val job: GenerationJob, val route: String)

/** Only submission failures can trigger upload; reuse the UUID if the HTTP result was uncertain. */
suspend fun submitGeneration(
    request: GenerationRequest,
    api: suspend (GenerationRequest) -> GenerationJob,
    upload: suspend (GenerationRequest) -> Unit
): GenerationSubmission {
    val job = try {
        api(request)
    } catch (e: ApiHttpException) {
        if (e.code != 530) throw e
        upload(request)
        val id = request.jobId.replace("-", "")
        return GenerationSubmission(
            GenerationJob(id, "/status/$id", "/download/$id", "awaiting_worker"), "Upload")
    }
    return GenerationSubmission(job, "API")
}
