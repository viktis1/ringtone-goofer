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

internal data class CallHistoryEntry(val id: String, val caller: String, val received: Long,
    val detail: String = "History updated, but no generation") {
    val message get() = "received a call from $caller. $detail"
}

internal data class CallContact(val name: String, val uri: String)

/** Local call history and an outbox committed atomically with each completed call. */
internal class IncomingCallHistory internal constructor(private val context: Context,
    databaseName: String = "incoming-calls.db") {
    private val helper = object : SQLiteOpenHelper(context,
        File(context.noBackupFilesDir, databaseName).absolutePath, null, 2) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, caller TEXT NOT NULL, received INTEGER NOT NULL, detail TEXT NOT NULL, pending TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion < 2) {
                db.execSQL("ALTER TABLE calls ADD COLUMN detail TEXT NOT NULL DEFAULT 'History updated, but no generation'")
                db.execSQL("ALTER TABLE calls ADD COLUMN pending TEXT NOT NULL DEFAULT ''")
            }
        }
    }
    val revision = MutableStateFlow(0L)

    @Synchronized
    fun accept(state: String, number: String, time: Long,
        plan: (IncomingCall, CallContact?) -> CallGenerationPlan) {
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
                val contact = resolveContact(call.number)
                val generation = plan(call, contact)
                db.insertWithOnConflict("calls", null, ContentValues().apply {
                    put("id", call.id); put("caller", contact?.name ?: call.number.ifBlank { "Unknown caller" })
                    put("received", call.received); put("detail", generation.detail)
                    put("pending", generation.item?.json()?.toString().orEmpty())
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
        .rawQuery("SELECT id,caller,received,detail FROM calls ORDER BY received DESC", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(CallHistoryEntry(
                cursor.getString(0), cursor.getString(1), cursor.getLong(2), cursor.getString(3))) }
        }

    @Synchronized
    fun hasActiveCall(): Boolean = helper.readableDatabase
        .rawQuery("SELECT payload FROM session WHERE id=1", null).use {
            it.moveToFirst() && JSONObject(it.getString(0)).optString("id").isNotBlank()
        }

    @Synchronized
    fun pending(): List<RingtoneItem> = helper.readableDatabase
        .rawQuery("SELECT pending FROM calls WHERE pending != ''", null).use { cursor ->
            buildList { while (cursor.moveToNext()) add(RingtoneItem.fromJson(JSONObject(cursor.getString(0)))) }
        }

    @Synchronized
    fun markDispatched(id: String) {
        helper.writableDatabase.update("calls", ContentValues().apply { put("pending", "") }, "id=?", arrayOf(id))
    }

    internal fun close() = helper.close()

    private fun resolveContact(number: String): CallContact? {
        if (number.isBlank()) return null
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup._ID,
                ContactsContract.PhoneLookup.LOOKUP_KEY, ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use {
                    if (it.moveToFirst()) CallContact(it.getString(2).orEmpty(),
                        ContactsContract.Contacts.getLookupUri(it.getLong(0), it.getString(1)).toString()) else null
                }
        } catch (_: SecurityException) { null }
    }

    companion object {
        @Volatile private var instance: IncomingCallHistory? = null
        fun get(context: Context): IncomingCallHistory = instance ?: synchronized(this) {
            instance ?: IncomingCallHistory(context.applicationContext).also { instance = it }
        }
    }
}
