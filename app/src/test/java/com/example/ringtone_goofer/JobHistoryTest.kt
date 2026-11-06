package com.example.ringtone_goofer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JobHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun readsLegacyHistoryForOriginalAccount() {
        val directory = temporary.newFolder()
        VoiceCache(directory).write("alice", JSONObject("""{"jobs":[{"id":"old-id","caller":"Alex","route":"Upload","status":"done","created":42}]}"""))
        val history = JobHistory(directory)
        assertTrue(history.read("bob").isEmpty())
        assertEquals(SubmittedJob("old-id", "Alex", "Upload", "done", "", 42), history.read("alice").single())
    }

    @Test fun ignoresMalformedLegacyHistory() {
        val directory = temporary.newFolder()
        VoiceCache(directory).write("alice", JSONObject("""{"jobs":[{"id":"missing-fields"}]}"""))
        assertTrue(JobHistory(directory).read("alice").isEmpty())
    }
}
