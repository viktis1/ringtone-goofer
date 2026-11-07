package com.example.ringtone_goofer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.google.firebase.auth.FirebaseAuth
import java.io.File
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Queues ordered call events; workers save history and generation intent after incoming calls end. */
class IncomingCallReceiver : BroadcastReceiver() {
    @Suppress("DEPRECATION")
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val state = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
        if (state !in setOf("RINGING", "OFFHOOK", "IDLE")) return
        val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER).orEmpty()
        val time = System.currentTimeMillis()
        val pending = goAsync()
        val app = context.applicationContext
        enqueueExecutor.execute {
            try {
                val request = OneTimeWorkRequestBuilder<IncomingCallWorker>()
                    .setInputData(workDataOf("state" to state, "number" to number, "received" to time)).build()
                // Keep broadcast delivery order, including the duplicate with caller information.
                WorkManager.getInstance(app).enqueueUniqueWork(
                    "incoming-call-history", ExistingWorkPolicy.APPEND_OR_REPLACE, request
                ).result.get(5, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e("IncomingCallReceiver", "Cannot queue call history: ${e.javaClass.simpleName}")
            } finally { pending.finish() }
        }
    }

    companion object {
        private val enqueueExecutor = Executors.newSingleThreadExecutor()
    }
}

/** Hands saved requests to the existing submit/poll/download/assign pipeline without needing the UI. */
class IncomingCallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val state = inputData.getString("state") ?: return@withContext Result.failure()
        try {
            val calls = IncomingCallHistory.get(applicationContext)
            val library = RingtoneLibrary.get(applicationContext)
            val auth = FirebaseAuth.getInstance()
            val user = auth.currentUser
            val voices = if (state == "IDLE" && calls.hasActiveCall() && user != null) {
                // Refresh before choosing: a recording uploaded since the UI last opened may be newer.
                val api = RingtoneApi(idToken = {
                    check(auth.currentUser?.uid == user.uid) { "Account changed" }
                    user.getIdToken(false).await().token ?: throw java.io.IOException("Sign in again.")
                })
                val response = api.voicesJson()
                if (auth.currentUser?.uid != user.uid) return@withContext Result.retry()
                VoiceCache(File(applicationContext.noBackupFilesDir, "voices")).write(user.uid, response)
                api.parseVoices(response)
            } else emptyList()
            calls.accept(state, inputData.getString("number").orEmpty(), inputData.getLong("received", 0)) { call, contact ->
                planCallGeneration(call, contact?.uri.orEmpty(), contact?.name.orEmpty(), user?.uid, voices)
            }
            // The call ID is also the server job ID. Replaying this handoff never creates a second job.
            for (snapshot in calls.pending()) {
                val item = library.putIfAbsent(snapshot)
                RingtoneWork.enqueue(applicationContext, item)?.result?.get(5, TimeUnit.SECONDS)
                calls.markDispatched(snapshot.id)
            }
            Result.success()
        } catch (_: android.database.sqlite.SQLiteException) {
            Result.retry()
        } catch (_: java.io.IOException) {
            Result.retry()
        } catch (_: com.google.firebase.FirebaseNetworkException) {
            Result.retry()
        } catch (_: java.util.concurrent.TimeoutException) {
            Result.retry()
        } catch (_: java.util.concurrent.ExecutionException) {
            Result.retry()
        }
    }
}
