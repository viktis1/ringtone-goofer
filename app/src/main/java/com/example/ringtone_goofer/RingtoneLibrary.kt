package com.example.ringtone_goofer

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File

/** A snapshot: later changes to the draft must never retarget an existing job. */
data class RingtoneItem(
    val id: String,
    val uid: String,
    val contactUri: String,
    val contactName: String,
    val voiceId: String,
    val voiceName: String,
    val clipId: String,
    val clipName: String,
    val created: Long = System.currentTimeMillis(),
    val stage: String = "submitting",
    val route: String = "",
    val statusUrl: String = "/status/${id.replace("-", "")}",
    val downloadUrl: String = "/download/${id.replace("-", "")}",
    val detail: String = "",
    val mediaUri: String = "",
    val resumeStage: String = "",
    val recordingPath: String = ""
) {
    fun serverJob() = GenerationJob(id.replace("-", ""), statusUrl, downloadUrl, stage)
    fun request() = GenerationRequest(id, contactName, voiceId, clipId)

    /** Persist assignment intent with the original target before scheduling the next worker. */
    fun afterDownload() = copy(
        stage = if (contactUri.isNotBlank()) "assigning" else "downloaded",
        detail = if (contactUri.isBlank()) "Downloaded. Choose a contact to finish assignment." else "",
        resumeStage = ""
    )
    val active get() = stage in setOf("submitting", "awaiting_worker", "queued", "running", "downloading", "assigning", "uploading")
    val label get() = when (stage) {
        "submitting" -> "Waiting to submit"
        "awaiting_worker" -> "Waiting for the server"
        "queued" -> "Queued"
        "running" -> "Generating"
        "ready" -> "Ready to download"
        "downloading" -> "Downloading"
        "downloaded" -> "Ready to listen and assign"
        "assigning" -> "Assigning"
        "assigned" -> "Assigned to ${contactName}"
        "uploading" -> "Uploading voice recording"
        "uploaded" -> "Voice recording uploaded"
        "failed" -> "Needs attention"
        else -> stage
    }

    fun json() = JSONObject().apply {
        put("id", id); put("uid", uid); put("contactUri", contactUri); put("contactName", contactName)
        put("voiceId", voiceId); put("voiceName", voiceName); put("clipId", clipId); put("clipName", clipName)
        put("created", created); put("stage", stage); put("route", route)
        put("statusUrl", statusUrl); put("downloadUrl", downloadUrl); put("detail", detail)
        put("mediaUri", mediaUri); put("resumeStage", resumeStage); put("recordingPath", recordingPath)
    }

    companion object {
        fun fromJson(j: JSONObject) = RingtoneItem(
            id = j.getString("id"), uid = j.getString("uid"),
            contactUri = j.getString("contactUri"), contactName = j.getString("contactName"),
            voiceId = j.getString("voiceId"), voiceName = j.getString("voiceName"),
            clipId = j.getString("clipId"), clipName = j.getString("clipName"),
            created = j.getLong("created"), stage = j.getString("stage"), route = j.optString("route"),
            statusUrl = j.getString("statusUrl"), downloadUrl = j.getString("downloadUrl"),
            detail = j.optString("detail"), mediaUri = j.optString("mediaUri"),
            resumeStage = j.optString("resumeStage"), recordingPath = j.optString("recordingPath")
        )
    }
}

data class RingtoneDraft(
    val contactUri: String = "", val contactName: String = "",
    val voiceId: String = "", val clipId: String = ""
) {
    fun json() = JSONObject().put("contactUri", contactUri).put("contactName", contactName)
        .put("voiceId", voiceId).put("clipId", clipId)
    companion object {
        fun fromJson(j: JSONObject) = RingtoneDraft(j.optString("contactUri"), j.optString("contactName"),
            j.optString("voiceId"), j.optString("clipId"))
    }
}

/**
 * SQLite is the source of truth, including intent to perform work.
 * Every caller uses Dispatchers.IO. Updates are serialized across the UI and workers.
 * The database and audio stay on this installation, outside Android cloud backup.
 */
class RingtoneLibrary internal constructor(context: Context, databaseName: String = "ringtone-library.db") {
    private val helper = object : SQLiteOpenHelper(context,
        File(context.noBackupFilesDir, databaseName).absolutePath, null, 1) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE items (uid TEXT NOT NULL, id TEXT NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(uid,id))")
            db.execSQL("CREATE TABLE drafts (uid TEXT PRIMARY KEY, payload TEXT NOT NULL)")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }
    val revision = MutableStateFlow(0L)

    @Synchronized fun items(uid: String): List<RingtoneItem> =
        helper.readableDatabase.query("items", arrayOf("payload"), "uid=?", arrayOf(uid), null, null, null)
            .use { c -> buildList { while (c.moveToNext()) add(RingtoneItem.fromJson(JSONObject(c.getString(0)))) } }
            .sortedByDescending { it.created }

    @Synchronized fun get(uid: String, id: String): RingtoneItem? =
        helper.readableDatabase.query("items", arrayOf("payload"), "uid=? AND id=?", arrayOf(uid, id), null, null, null)
            .use { c -> if (c.moveToFirst()) RingtoneItem.fromJson(JSONObject(c.getString(0))) else null }

    @Synchronized fun put(item: RingtoneItem) {
        helper.writableDatabase.insertOrThrow("items", null, ContentValues().apply {
            put("uid", item.uid); put("id", item.id); put("payload", item.json().toString())
        })
        revision.value++
    }

    @Synchronized fun update(uid: String, id: String, change: (RingtoneItem) -> RingtoneItem): RingtoneItem {
        val next = change(requireNotNull(get(uid, id)) { "Ringtone no longer exists" })
        check(helper.writableDatabase.update("items", ContentValues().apply {
            put("payload", next.json().toString())
        }, "uid=? AND id=?", arrayOf(uid, id)) == 1)
        revision.value++
        return next
    }

    @Synchronized fun draft(uid: String): RingtoneDraft =
        helper.readableDatabase.query("drafts", arrayOf("payload"), "uid=?", arrayOf(uid), null, null, null)
            .use { c -> if (c.moveToFirst()) RingtoneDraft.fromJson(JSONObject(c.getString(0))) else RingtoneDraft() }

    @Synchronized fun saveDraft(uid: String, draft: RingtoneDraft) {
        helper.writableDatabase.insertWithOnConflict("drafts", null, ContentValues().apply {
            put("uid", uid); put("payload", draft.json().toString())
        }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** Old history did not contain a contact lookup key. Never infer identity from a display name. */
    @Synchronized fun importHistory(context: Context, uid: String) {
        JobHistory(File(context.noBackupFilesDir, "jobs")).read(uid).forEach { old ->
            if (get(uid, old.id) == null) {
                put(RingtoneItem(old.id, uid, "", old.caller, "", "Previous generation", "", "",
                    created = old.created,
                    stage = when (old.status) { "done" -> "ready"; "submission_failed" -> "failed"; else -> old.status },
                    route = old.route, detail = old.detail))
            }
        }
    }

    fun audioFile(context: Context, item: RingtoneItem): File {
        // Hash account and job IDs: server-supplied IDs never become file paths.
        val key = java.security.MessageDigest.getInstance("SHA-256")
            .digest("${item.uid}:${item.id}".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.noBackupFilesDir, "ringtones").apply { mkdirs() }, "$key.wav")
    }

    internal fun close() = helper.close()

    companion object {
        @Volatile private var instance: RingtoneLibrary? = null
        fun get(context: Context): RingtoneLibrary = instance ?: synchronized(this) {
            instance ?: RingtoneLibrary(context.applicationContext).also { instance = it }
        }
    }
}
