package com.example.ringtone_goofer

import org.junit.Assert.*
import org.junit.Test

class CallSessionTest {
    private fun runCall(states: List<Pair<String, String>>): List<IncomingCall> {
        var session = CallSession()
        return buildList {
            states.forEachIndexed { index, (state, number) ->
                val result = session.advance(state, number, index.toLong(), "call-$index")
                // Copying mirrors restoring the durable state between workers.
                session = result.session.copy()
                result.completed?.let(::add)
            }
        }
    }

    @Test fun answeredCallRetainsNumberAndIgnoresDuplicateBroadcasts() {
        val calls = runCall(listOf("RINGING" to "123", "RINGING" to "",
            "OFFHOOK" to "", "OFFHOOK" to "123", "IDLE" to "", "IDLE" to "123"))
        assertEquals(1, calls.size)
        assertEquals("123", calls.single().number)
    }

    @Test fun missedCallAlsoCompletesAndNextCallIsIndependent() {
        val calls = runCall(listOf("RINGING" to "", "RINGING" to "123",
            "IDLE" to "", "IDLE" to "123", "RINGING" to "456", "IDLE" to ""))
        assertEquals(listOf("123", "456"), calls.map { it.number })
        assertNotEquals(calls[0].id, calls[1].id)
    }

    @Test fun outgoingCallAndUnrelatedIdleDoNotCreateEntries() {
        assertTrue(runCall(listOf("IDLE" to "", "OFFHOOK" to "123", "IDLE" to "123")).isEmpty())
    }

    @Test fun withheldNumberStillProducesAnEntry() {
        val calls = runCall(listOf("RINGING" to "", "IDLE" to ""))
        assertEquals(1, calls.size)
        assertEquals("", calls.single().number)
    }
}
