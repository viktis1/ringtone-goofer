package com.example.ringtone_goofer

import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import java.util.UUID

/** Uploading the JSON finalizes a GCS object, which queues the request for the homelab worker. */
class RingtoneRequestUpload(private val storage: FirebaseStorage) {
    suspend fun submit(uid: String, request: GenerationRequest) {
        val bytes = request.payload().toString().toByteArray(Charsets.UTF_8)
        require(bytes.size < 16 * 1024) { "Generation request must be smaller than 16 KB." }
        val metadata = StorageMetadata.Builder().setContentType("application/json").build()
        val upload = storage.reference.child(path(uid, request.jobId)).putBytes(bytes, metadata)
        try {
            upload.await()
        } catch (e: CancellationException) {
            upload.cancel()
            throw e
        }
    }

    companion object {
        fun path(uid: String, jobId: String): String {
            require(uid.isNotBlank() && '/' !in uid) { "Invalid Firebase user ID." }
            val id = UUID.fromString(jobId)
            require(id.version() == 4 && id.toString() == jobId) { "Expected a UUID4 job ID." }
            return "users/$uid/$jobId.json"
        }
    }
}
