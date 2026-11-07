package com.example.ringtone_goofer

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class IncomingCallHistoryTest {
    @Test fun savedHandoffSurvivesRestartAndDoesNotResetGenerationProgress() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "call-test-${UUID.randomUUID()}.db"
        val libraryName = "call-library-test-${UUID.randomUUID()}.db"
        var calls = IncomingCallHistory(context, database)
        val library = RingtoneLibrary(context, libraryName)
        val planner: (IncomingCall, CallContact?) -> CallGenerationPlan = { call, _ ->
            CallGenerationPlan(RingtoneItem(call.id, "test-user", "contact-uri", "Caller",
                "Public:Voice", "Voice", "clip", "Clip"), "Queued")
        }
        try {
            calls.accept("RINGING", "", 100, planner)
            calls.accept("OFFHOOK", "", 101, planner)
            calls.accept("IDLE", "", 102, planner)
            val snapshot = calls.pending().single()
            calls.close()
            calls = IncomingCallHistory(context, database)
            assertEquals(snapshot, calls.pending().single())
            assertEquals(1, calls.entries().size)
            library.putIfAbsent(snapshot)
            library.update(snapshot.uid, snapshot.id) { it.copy(stage = "assigned") }
            assertEquals("assigned", library.putIfAbsent(calls.pending().single()).stage)
            calls.markDispatched(snapshot.id)
            calls.accept("IDLE", "", 103, planner)
            assertTrue(calls.pending().isEmpty())
            assertEquals(1, calls.entries().size)
            assertEquals(1, library.items(snapshot.uid).size)
        } finally {
            calls.close()
            library.close()
            context.deleteDatabase(File(context.noBackupFilesDir, database).absolutePath)
            context.deleteDatabase(File(context.noBackupFilesDir, libraryName).absolutePath)
        }
    }

    @Test fun upgradeKeepsOldCallsWithoutRetroactiveGeneration() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "call-upgrade-test-${UUID.randomUUID()}.db"
        val path = File(context.noBackupFilesDir, database)
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("CREATE TABLE session (id INTEGER PRIMARY KEY, payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE calls (id TEXT PRIMARY KEY, caller TEXT NOT NULL, received INTEGER NOT NULL)")
            db.execSQL("INSERT INTO calls VALUES ('old-call', 'Caller', 1)")
            db.version = 1
        }
        val calls = IncomingCallHistory(context, database)
        try {
            assertEquals("History updated, but no generation", calls.entries().single().detail)
            assertTrue(calls.pending().isEmpty())
        } finally {
            calls.close()
            context.deleteDatabase(path.absolutePath)
        }
    }
}
