package com.example.ringtone_goofer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val response = """{"Public":{},"Your Uploads":{"Alex":{"clips":[{"id":"clip-uuid","name":"private/Alex.wav"}]}}}"""

    @Test fun survivesNewInstanceAndIsolatesUsers() {
        val directory = temporary.newFolder()
        VoiceCache(directory).write("alice/uid", JSONObject(response))
        val cache = VoiceCache(directory)
        val api = RingtoneApi(idToken = { "unused" })
        assertEquals("clip-uuid", api.parseVoices(cache.read("alice/uid")!!).single().clips.single().id)
        assertNull(cache.read("bob"))
        cache.write("bob", JSONObject("""{"Public":{},"Your Uploads":{}}"""))
        assertTrue(api.parseVoices(cache.read("bob")!!).isEmpty())
        assertEquals(1, api.parseVoices(cache.read("alice/uid")!!).size)
    }

    @Test fun latestSuccessfulResponseReplacesPreviousIncludingEmptyList() {
        val cache = VoiceCache(temporary.newFolder())
        cache.write("alice", JSONObject(response))
        cache.write("alice", JSONObject("""{"Public":{},"Your Uploads":{}}"""))
        assertTrue(RingtoneApi(idToken = { "unused" }).parseVoices(cache.read("alice")!!).isEmpty())
    }

    @Test fun missingCorruptAndWrongOwnerCachesAreIgnored() {
        val directory = temporary.newFolder()
        val cache = VoiceCache(directory)
        assertNull(cache.read("alice"))
        cache.write("alice", JSONObject(response))
        val file = directory.listFiles()!!.single()
        file.writeText("broken json")
        assertNull(cache.read("alice"))
        file.writeText(JSONObject().put("uid", "bob").put("response", JSONObject(response)).toString())
        assertNull(cache.read("alice"))
    }

}
