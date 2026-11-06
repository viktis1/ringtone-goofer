package com.example.ringtone_goofer

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

class VoiceRecordingsTest {
    @get:Rule val temporary = TemporaryFolder()
    private val newest = VoiceClip("new", "users/alice/Alex.wav")
    private val older = VoiceClip("old", "users/alice/Alex.wav")
    private val own = Voice("Your Uploads:Alex", "Alex", listOf(newest, older))
    private val public = Voice("Public:Alex", "Alex", listOf(VoiceClip("public", "Alex.mp3")))

    @Test fun downloadsOnlyMissingPrivateClipsAndFindsNewestContactRecording() = runBlocking {
        val recordings = VoiceRecordings(temporary.newFolder())
        val requested = mutableListOf<String>()
        assertEquals(2, recordings.sync("alice", listOf(own, public)) { _, clip, file ->
            requested.add(clip.id)
            file.writeText(clip.id)
        })
        assertEquals(listOf("new", "old"), requested)
        assertEquals("new", recordings.forContact("alice", "Alex", listOf(own))!!.readText())
        assertNull(recordings.forContact("alice", "Other", listOf(own)))
        assertEquals(0, recordings.sync("alice", listOf(own, public)) { _, _, _ -> error("Already saved") })
        recordings.file("alice", older).delete()
        assertEquals(1, recordings.sync("alice", listOf(own)) { _, clip, file ->
            assertEquals(older, clip)
            file.writeText("restored")
        })
    }

    @Test fun timestampWinsOverResponseOrderAndStalePhoneRecording() = runBlocking {
        val recordings = VoiceRecordings(temporary.newFolder())
        val recent = newest.copy(createdAt = Instant.parse("2026-11-06T10:30:00.123456Z"))
        val previous = older.copy(createdAt = Instant.parse("2026-11-06T12:00:00+02:00"))
        val voice = own.copy(clips = listOf(previous, newest.copy(id = "undated"), recent))
        recordings.sync("alice", listOf(voice)) { _, clip, file -> file.writeText(clip.id) }
        val local = temporary.newFile().apply { writeText("local") }
        assertTrue(local.setLastModified(Instant.parse("2026-11-06T09:00:00Z").toEpochMilli()))
        assertEquals("new", recordings.forContact("alice", "Alex", listOf(voice), local)!!.readText())
        assertTrue(local.setLastModified(Instant.parse("2026-11-06T11:00:00Z").toEpochMilli()))
        assertEquals(local, recordings.forContact("alice", "Alex", listOf(voice), local))
        assertEquals("local", local.readText()) // Cloud sync never overwrites the phone sample.
    }

    @Test fun accountsAndUnsafeServerIdsCannotShareOrEscapeFiles() = runBlocking {
        val directory = temporary.newFolder()
        val recordings = VoiceRecordings(directory)
        val voice = own.copy(clips = listOf(newest.copy(id = "../../escape")))
        recordings.sync("alice/../", listOf(voice)) { _, _, file -> file.writeText("alice") }
        assertNotNull(recordings.forContact("alice/../", "Alex", listOf(voice)))
        assertNull(recordings.forContact("bob", "Alex", listOf(voice)))
        assertTrue(recordings.file("alice/../", voice.clips.single()).canonicalPath.startsWith(directory.canonicalPath + "/"))
    }

    @Test fun interruptedDownloadIsNotPublishedAndRetriesNextSync() = runBlocking {
        val directory = temporary.newFolder()
        val recordings = VoiceRecordings(directory)
        for (failure in listOf(IOException("Disconnected"), CancellationException("Account changed"))) {
            try {
                recordings.sync("alice", listOf(own)) { _, _, file ->
                    file.writeText("incomplete")
                    throw failure
                }
                fail("Expected download failure")
            } catch (e: Exception) {
                assertEquals(failure.javaClass, e.javaClass)
                assertEquals(failure.message, e.message)
            }
            assertNull(recordings.forContact("alice", "Alex", listOf(own)))
            assertTrue(directory.walkTopDown().none { it.extension == "part" })
        }
        assertEquals(2, recordings.sync("alice", listOf(own)) { _, _, file -> file.writeText("complete") })
    }

    @Test fun emptyDownloadIsRetriedRatherThanMarkedComplete() = runBlocking {
        val recordings = VoiceRecordings(temporary.newFolder())
        try {
            recordings.sync("alice", listOf(own)) { _, _, _ -> }
            fail("Expected empty audio failure")
        } catch (_: IllegalStateException) { }
        assertNull(recordings.forContact("alice", "Alex", listOf(own)))
    }
}
