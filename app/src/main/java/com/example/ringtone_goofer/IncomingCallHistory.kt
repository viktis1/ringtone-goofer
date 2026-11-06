package com.example.ringtone_goofer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.util.UUID

internal data class IncomingCall(val id: String, val number: String, val received: Long)
internal data class CallTransition(val session: CallSession, val completed: IncomingCall? = null)

/** Empty duplicate broadcasts must not erase a number captured while ringing. */
internal data class CallSession(
    val state: String = "", val id: String = "", val number: String = "", val received: Long = 0
) {
    fun advance(next: String, incomingNumber: String, time: Long, newId: String): CallTransition {
        if (next == "RINGING" && state != "RINGING") {
            return CallTransition(CallSession(next, newId, incomingNumber, time))
        }
        if (next == "OFFHOOK" && state == "IDLE") return CallTransition(CallSession(state = next))
        val updated = copy(state = next, number = incomingNumber.ifBlank { number })
        if (next == "IDLE") {
            val call = if (id.isNotEmpty()) IncomingCall(id, updated.number, received) else null
            return CallTransition(CallSession(state = next), call)
        }
        return CallTransition(updated)
    }
}

internal data class CallHistoryEntry(val id: String, val caller: String, val received: Long) {
    val message get() = "received a call from $caller. History updated, but no generation"
}

/** Local device history, deliberately independent of Firebase accounts and generation jobs. */
internal class IncomingCallHistory private constructor(private val context: Context) {
    private val helper = object : SQLiteOpenHelper(context,
        File(context.noBackupFilesDir, "incoming-calls.db").absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, caller TEXT NOT NULL, received INTEGER NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    val revision = MutableStateFlow(0L)

    @Synchronized
    fun accept(state: String, number: String, time: Long) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val session = db.rawQuery("SELECT payload FROM session WHERE id=1", null).use {
                if (!it.moveToFirst()) CallSession() else JSONObject(it.getString(0)).let { j ->
                    CallSession(j.optString("state"), j.optString("id"), j.optString("number"), j.optLong("received"))
                }
            }
            val transition = session.advance(state, number, time, UUID.randomUUID().toString())
            transition.completed?.let { call ->
                db.insertWithOnConflict("calls", null, ContentValues().apply {
                    put("id", call.id); put("caller", resolveCaller(call.number)); put("received", call.received)
                }, SQLiteDatabase.CONFLICT_IGNORE)
            }
            val next = transition.session
            db.insertWithOnConflict("session", null, ContentValues().apply {
                put("id", 1)
                put("payload", JSONObject().put("state", next.state).put("id", next.id)
                    .put("number", next.number).put("received", next.received).toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        revision.value++
    }

    @Synchronized
    fun entries(): List<CallHistoryEntry> = helper.readableDatabase
        .rawQuery("SELECT id,caller,received FROM calls ORDER BY received DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(CallHistoryEntry(
                cursor.getString(0), cursor.getString(1), cursor.getLong(2))) }
        }

    private fun resolveCaller(number: String): String {
        if (number.isBlank()) return "Unknown caller"
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return number
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0)?.takeIf(String::isNotBlank) else null } ?: number
        } catch (_: SecurityException) { number }
    }

    companion object {
        @Volatile private var instance: IncomingCallHistory? = null
        fun get(context: Context): IncomingCallHistory = instance ?: synchronized(this) {
            instance ?: IncomingCallHistory(context.applicationContext).also { instance = it }
        }
    }
}
