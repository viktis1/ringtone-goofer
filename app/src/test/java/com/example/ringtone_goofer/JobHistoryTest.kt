package com.example.ringtone_goofer

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class JobHistoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun historySurvivesRestartIsIsolatedAndUpdatesWithoutDuplicates() {
        val directory = temporary.newFolder()
        val job = SubmittedJob("12345678-1234-4234-8234-123456789abc", "Alex", "Upload", "awaiting_worker")
        JobHistory(directory).put("alice", job)
        val history = JobHistory(directory)
        assertTrue(history.read("bob").isEmpty())
        assertEquals(job, history.read("alice").single())
        history.put("alice", job.copy(status = "done"))
        assertEquals(1, history.read("alice").size)
        assertTrue(history.read("alice").single().finished)
    }

    @Test fun uploadedJobUsesOrchestratorHexUuidForPolling() {
        val job = SubmittedJob("12345678-1234-4234-8234-123456789abc", "Alex", "Upload", "awaiting_worker")
        assertEquals("/status/12345678123442348234123456789abc", job.serverJob().statusUrl)
        assertFalse(job.finished)
    }
}
