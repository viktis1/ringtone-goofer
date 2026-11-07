package com.example.ringtone_goofer

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import kotlin.random.Random

class CallGenerationTest {
    private val call = IncomingCall("call-uuid", "123", 42)
    private val publicVoice = Voice("Public:Alex", "Alex", listOf(VoiceClip("public", "Sample")))
    private fun plan(voices: List<Voice>, name: String = "Alice") =
        planCallGeneration(call, "caller-uri", name, "uid", voices, Random(1))

    @Test fun newestCallerUploadWinsOverPublicAndOtherContacts() {
        val older = VoiceClip("old", "Old", Instant.parse("2026-01-01T00:00:00Z"))
        val newest = VoiceClip("new", "New", Instant.parse("2026-11-01T00:00:00Z"))
        val upload = Voice("Your Uploads:Alice", "Alice", listOf(newest, VoiceClip("undated", "Unknown"), older))
        val other = Voice("Your Uploads:Bob", "Bob", listOf(newest.copy(id = "bob")))
        val item = requireNotNull(plan(listOf(publicVoice, upload, other)).item)
        assertEquals(upload.id, item.voiceId)
        assertEquals("new", item.clipId)
        assertEquals(call.id, item.id)
        assertEquals("caller-uri", item.contactUri)
        assertEquals("Alice", item.request().caller)
        assertEquals("assigning", item.afterDownload().stage)
    }

    @Test fun missingOrEmptyCallerUploadFallsBackOnlyToPublicClips() {
        val other = Voice("Your Uploads:Bob", "Bob", listOf(VoiceClip("private", "Private")))
        val empty = Voice("Your Uploads:Alice", "Alice", emptyList())
        assertEquals("public", plan(listOf(other, empty, publicVoice)).item?.clipId)
        assertNull(plan(listOf(other, empty)).item)
    }

    @Test fun fallbackCanSelectDifferentFamousPersonClips() {
        val another = Voice("Public:Other", "Other", listOf(VoiceClip("second", "Second")))
        val selections = (0..30).map {
            planCallGeneration(call, "uri", "Alice", "uid", listOf(publicVoice, another), Random(it)).item!!.clipId
        }.toSet()
        assertEquals(setOf("public", "second"), selections)
    }

    @Test fun uploadMatchingUsesExistingContactNameNormalization() {
        val upload = Voice("Your Uploads:John-Smith", "John", listOf(VoiceClip("john", "John")))
        assertEquals("john", plan(listOf(publicVoice, upload), "John Smith").item?.clipId)
    }

    @Test fun undatedUploadStillWinsAndEmptyClipIdsAreIgnored() {
        val upload = Voice("Your Uploads:Alice", "Alice",
            listOf(VoiceClip("", "Invalid"), VoiceClip("valid", "Valid")))
        assertEquals("valid", plan(listOf(publicVoice, upload)).item?.clipId)
    }

    @Test fun unknownCallerSignedOutAndEmptyCatalogDoNotGenerate() {
        assertNull(planCallGeneration(call, "", "", "uid", listOf(publicVoice)).item)
        assertNull(planCallGeneration(call, "uri", "Alice", null, listOf(publicVoice)).item)
        assertNull(plan(emptyList()).item)
    }
}
