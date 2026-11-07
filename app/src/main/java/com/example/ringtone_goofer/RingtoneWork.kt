package com.example.ringtone_goofer

import android.content.Context
import android.net.Uri
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Unique per item AND operation: a download can start while the final status check is finishing. */
object RingtoneWork {
    fun action(item: RingtoneItem): String? = when (item.stage) {
        "submitting", "awaiting_worker", "queued", "running" -> "generate"
        "downloading" -> "download"
        "assigning" -> "assign"
        "uploading" -> "upload"
        else -> null
    }

    fun enqueue(context: Context, item: RingtoneItem, explicit: Boolean = false): androidx.work.Operation? {
        val action = action(item) ?: return null
        val request = OneTimeWorkRequestBuilder<RingtoneWorker>()
            .setInputData(workDataOf("uid" to item.uid, "id" to item.id, "action" to action))
            .setConstraints(Constraints.Builder().apply {
                if (action != "assign") setRequiredNetworkType(NetworkType.CONNECTED)
            }.build())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag("ringtone:${item.uid}")
            .build()
        return WorkManager.getInstance(context).enqueueUniqueWork("ringtone:${item.uid}:${item.id}:$action",
            if (explicit) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP, request)
    }
}

/**
 * Each side effect has a durable checkpoint. Repeated submissions use the same UUID4;
 * the backend deduplicates that ID, including requests sent through cloud storage.
 * Polling is bounded per run so no worker occupies Android's execution window indefinitely.
 */
class RingtoneWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val uid = inputData.getString("uid") ?: return@withContext Result.failure()
        val id = inputData.getString("id") ?: return@withContext Result.failure()
        val action = inputData.getString("action") ?: return@withContext Result.failure()
        val library = RingtoneLibrary.get(applicationContext)
        var item = library.get(uid, id) ?: return@withContext Result.success()
        if (RingtoneWork.action(item) != action) {
            // A process can stop after committing the next stage but before scheduling it.
            RingtoneWork.enqueue(applicationContext, item)
            return@withContext Result.success()
        }
        val auth = FirebaseAuth.getInstance()
        val user = auth.currentUser
        if (user?.uid != uid) {
            library.update(uid, id) { it.copy(detail = "Waiting for the original Google account to sign in.") }
            return@withContext Result.success()
        }
        fun checkAccount() { check(auth.currentUser?.uid == uid) { "Google account changed. Sign in to continue." } }
        val api = RingtoneApi(idToken = {
            checkAccount()
            user.getIdToken(false).await().token ?: throw IOException("Sign in again to continue.")
        })
        try {
            when (action) {
                "generate" -> {
                    val finished = advanceGeneration(item,
                        submit = { request ->
                            submitGeneration(request, api::generate) { pending ->
                                checkAccount()
                                RingtoneRequestUpload(FirebaseStorage.getInstance(applicationContext.getString(
                                    R.string.generation_request_bucket_url))).submit(uid, pending)
                            }
                        },
                        status = { job -> checkAccount(); api.status(job) },
                        save = { change -> library.update(uid, id, change) },
                        pause = { delay(4_000) })
                    if (finished) {
                        library.get(uid, id)?.let { RingtoneWork.enqueue(applicationContext, it) }
                        Result.success()
                    } else Result.retry()
                }
                "download" -> {
                    val target = library.audioFile(applicationContext, item)
                    val partial = File(target.parentFile, target.name + ".part")
                    try {
                        library.update(uid, id) { it.copy(detail = "Downloading WAV. Interrupted downloads restart automatically.") }
                        api.download(item.serverJob(), partial)
                        ensureActive()
                        check(partial.renameTo(target)) { "Cannot save downloaded ringtone" }
                        item = library.update(uid, id) { it.afterDownload() }
                    } finally { partial.delete() }
                    RingtoneWork.enqueue(applicationContext, item)
                    Result.success()
                }
                "assign" -> {
                    val file = library.audioFile(applicationContext, item)
                    check(file.isFile) { "Downloaded audio is missing. Download it again." }
                    check(item.contactUri.isNotBlank()) { "Choose an assignment contact first." }
                    for (permission in listOf(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS)) {
                        check(androidx.core.content.ContextCompat.checkSelfPermission(applicationContext, permission) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            "Ringtone downloaded. Allow contacts access and retry to finish automatic assignment."
                        }
                    }
                    val store = ContactRingtoneStore(applicationContext)
                    // saveWav reuses an already published row after a process interruption.
                    val media = store.saveWav(file, item.id, item.contactName)
                    item = library.update(uid, id) { it.copy(mediaUri = media.toString()) }
                    checkAccount()
                    store.assign(AddressBookContact(Uri.parse(item.contactUri), item.contactName, null, ""), media)
                    library.update(uid, id) { it.copy(stage = "assigned", detail = "", resumeStage = "") }
                    Result.success()
                }
                "upload" -> {
                    val file = File(item.recordingPath)
                    check(file.isFile) { "Recording is no longer available." }
                    checkAccount()
                    // The snapshot is unique locally; GCP keeps one overwriteable object per contact.
                    val remoteName = contactRecordingFile(applicationContext.filesDir, item.contactName).name
                    val upload = FirebaseStorage.getInstance(applicationContext.getString(R.string.upload_bucket_url))
                        .reference.child("users/$uid/$remoteName").putFile(Uri.fromFile(file),
                            StorageMetadata.Builder().setContentType("audio/wav")
                                .setCustomMetadata("uuid", item.id).build())
                    try { upload.await() } catch (e: CancellationException) { upload.cancel(); throw e }
                    library.update(uid, id) { it.copy(stage = "uploaded", detail = "Refresh voices once the server has processed this recording.") }
                    Result.success()
                }
                else -> Result.failure()
            }
        } catch (e: CancellationException) {
            // Keep the checkpoint; WorkManager will run it again after interruption.
            throw e
        } catch (e: Exception) {
            if (auth.currentUser?.uid != uid) {
                library.update(uid, id) { it.copy(detail = "Sign in to the original Google account to continue.") }
                return@withContext Result.success()
            }
            val transient = when (e) {
                is ApiHttpException -> e.code in setOf(500, 503, 530)
                is IOException -> e !is javax.net.ssl.SSLException
                is com.google.firebase.FirebaseNetworkException -> true
                is com.google.firebase.storage.StorageException ->
                    e.errorCode == com.google.firebase.storage.StorageException.ERROR_RETRY_LIMIT_EXCEEDED
                else -> false
            }
            if (transient) {
                library.update(uid, id) { it.copy(detail = "Will retry automatically: ${e.message}") }
                Result.retry()
            } else {
                library.update(uid, id) { it.copy(stage = "failed", resumeStage = it.stage,
                    detail = e.message ?: "Operation failed. Try again.") }
                // The library owns domain failures. Finish this scheduler node normally so an
                // immediate retry appended by the user is not cancelled by a failed dependency.
                Result.success()
            }
        }
    }
}
