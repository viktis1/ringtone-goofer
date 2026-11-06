package com.example.ringtone_goofer

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Uses a separate database, never the user's library or real backend. */
@RunWith(AndroidJUnit4::class)
class RingtoneLibraryTest {
    @Test fun restartPreservesDraftOperationsFilesAndAccountIsolation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = "library-test-${UUID.randomUUID()}.db"
        val item = RingtoneItem(UUID.randomUUID().toString(), "test-alice",
            "content://contacts/lookup/key/1", "Alex", "voice", "Voice", "clip", "Sample")
        var library = RingtoneLibrary(context, database)
        val file = library.audioFile(context, item)
        try {
            val draft = RingtoneDraft(item.contactUri, item.contactName, item.voiceId, item.clipId)
            library.saveDraft(item.uid, draft)
            library.put(item)
            file.writeText("saved-audio")
            for (stage in listOf("queued", "ready", "downloading", "downloaded", "assigning", "assigned")) {
                library.update(item.uid, item.id) { it.copy(stage = stage) }
                library.close()
                library = RingtoneLibrary(context, database)
                assertEquals(stage, library.get(item.uid, item.id)?.stage)
                assertEquals(draft, library.draft(item.uid))
                assertEquals(item.contactUri, library.get(item.uid, item.id)?.contactUri)
                assertEquals("saved-audio", library.audioFile(context, item).readText())
                assertTrue(library.items("test-bob").isEmpty())
                assertEquals(RingtoneDraft(), library.draft("test-bob"))
            }
            assertEquals(1, library.items(item.uid).size)
        } finally {
            library.close()
            file.delete()
            context.deleteDatabase(File(context.noBackupFilesDir, database).absolutePath)
        }
    }
}
