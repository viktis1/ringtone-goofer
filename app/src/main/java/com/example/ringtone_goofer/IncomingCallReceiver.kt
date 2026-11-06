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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Only queues work; ordered workers persist state and add History after an incoming call ends. */
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

/** No network requests, generation or ringtone changes belong in this baseline task. */
class IncomingCallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val state = inputData.getString("state") ?: return@withContext Result.failure()
        try {
            IncomingCallHistory.get(applicationContext).accept(state,
                inputData.getString("number").orEmpty(), inputData.getLong("received", 0))
            Result.success()
        } catch (_: android.database.sqlite.SQLiteException) {
            Result.retry()
        }
    }
}
