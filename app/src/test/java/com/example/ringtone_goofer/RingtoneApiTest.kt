package com.example.ringtone_goofer

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.UUID

/** Real HTTP against a local fake server; never creates jobs on the homelab. */
class RingtoneApiTest {
    private lateinit var server: HttpServer
    private lateinit var api: RingtoneApi

    @Before fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        api = RingtoneApi("http://127.0.0.1:${server.address.port}") { "test-token" }
    }

    @After fun tearDown() { server.stop(0) }

    private fun respond(path: String, body: String, code: Int = 200, type: String = "application/json") {
        server.createContext(path) { exchange ->
            val bytes = body.toByteArray()
            exchange.responseHeaders.set("Content-Type", type)
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
    }

    @Test fun recipientCanChoosePublicVoicesAndOnlyTheirOwnUploads() {
        fun voice(id: String) = Voice(id, id, emptyList())
        assertTrue(voice("Public:Alex").isAvailableForRecipient("Mille"))
        assertTrue(voice("Public:Alex").isAvailableForRecipient(""))
        assertTrue(voice("Your Uploads:Mille").isAvailableForRecipient("Mille"))
        assertTrue(voice("Your Uploads:mille").isAvailableForRecipient(" Mille "))
        assertTrue(voice("Your Uploads:John-Smith").isAvailableForRecipient("John Smith"))
        assertTrue(voice("Your Uploads:Søren_Åberg").isAvailableForRecipient("Søren Åberg"))
        assertFalse(voice("Your Uploads:Alex").isAvailableForRecipient("Mille"))
        assertFalse(voice("Your Uploads:Mille Jensen").isAvailableForRecipient("Mille"))
        assertFalse(voice("Your Uploads:Mille").isAvailableForRecipient(""))
        assertFalse(voice("Other:Mille").isAvailableForRecipient("Mille"))
    }

    @Test fun voicesParseGroupsAndKeepClipUuids() = runBlocking {
        val uuid = "12345678-1234-4234-8234-123456789abc"
        respond("/voices", """{"Public":{"Alex":{"clips":[{"id":"$uuid","name":"public/Alex.wav"}]}},"Your Uploads":{"Alex":{"clips":[]}}}""")
        val voices = api.voices().associateBy { it.id }
        assertEquals(setOf("Public:Alex", "Your Uploads:Alex"), voices.keys)
        assertEquals("Alex · Public", voices.getValue("Public:Alex").name)
        assertEquals(uuid, voices.getValue("Public:Alex").clips.single().id)
        assertTrue(voices.getValue("Your Uploads:Alex").clips.isEmpty())
    }

    @Test fun requestsUseCurrentBearerToken() = runBlocking {
        val headers = mutableListOf<String?>()
        server.createContext("/voices") { exchange ->
            headers.add(exchange.requestHeaders.getFirst("Authorization"))
            val bytes = """{"Public":{},"Your Uploads":{}}""".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        var token = "first"
        val client = RingtoneApi("http://127.0.0.1:${server.address.port}") { token }
        assertTrue(client.voices().isEmpty())
        token = "refreshed"
        client.voices()
        assertEquals(listOf("Bearer first", "Bearer refreshed"), headers)
    }

    @Test fun submissionUsesSchemaAndRetryKeepsClientUuid() = runBlocking {
        val bodies = mutableListOf<JSONObject>()
        val methods = mutableListOf<String>()
        server.createContext("/generate") { exchange ->
            methods.add(exchange.requestMethod)
            bodies.add(JSONObject(exchange.requestBody.bufferedReader().use { it.readText() }))
            val response = """{"job_id":"returned-id","status":"queued","status_url":"/status/returned-id","download_url":"/download/returned-id"}""".toByteArray()
            exchange.sendResponseHeaders(202, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
            exchange.close()
        }
        val request = GenerationRequest(UUID.randomUUID().toString(), "Anne \"AJ\"", "voice", "clip.wav")
        val job = api.generate(request)
        api.generate(request)
        assertEquals(listOf("POST", "POST"), methods)
        assertEquals("returned-id", job.id)
        assertEquals("/status/returned-id", job.statusUrl)
        assertEquals("/download/returned-id", job.downloadUrl)
        bodies.forEach {
            assertEquals(setOf("job_id", "caller", "receiver", "voice_id", "clip_id"), it.keys().asSequence().toSet())
            assertEquals(request.jobId, it.getString("job_id"))
            assertEquals(4, UUID.fromString(it.getString("job_id")).version())
            assertEquals(request.caller, it.getString("caller"))
            assertEquals("viktor", it.getString("receiver"))
            assertEquals("voice", it.getString("voice_id"))
            assertEquals("clip.wav", it.getString("clip_id"))
        }
    }

    @Test fun statusReadsFailureDetailFromReturnedStatusUrl() = runBlocking {
        respond("/status/server-id", """{"status":"failed","error":"GPU unavailable"}""")
        val status = api.status(GenerationJob("server-id", "/status/server-id", "/download/server-id", "queued"))
        assertEquals("failed", status.status)
        assertEquals("GPU unavailable", status.error)
    }

    @Test fun unavailableServerReturnsHttpError() = runBlocking {
        respond("/voices", """{"detail":"Offline"}""", 503)
        try {
            api.voices()
            fail("Expected HTTP failure")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("503"))
            assertTrue(e.message.orEmpty().contains("Offline"))
        }
    }

    @Test fun previewDownloadsChunkedAudioAndEncodesClipNames() = runBlocking {
        val query = java.util.concurrent.atomic.AtomicReference<String>()
        server.createContext("/voice-preview") { exchange ->
            query.set(exchange.requestURI.rawQuery)
            exchange.responseHeaders.set("Content-Type", "audio/mpeg")
            // Like the live preview API: no Content-Length and no byte-range support.
            exchange.sendResponseHeaders(200, 0)
            exchange.responseBody.use { it.write("MP3 clip bytes".toByteArray()) }
            exchange.close()
        }
        val target = File.createTempFile("preview-test", ".audio")
        try {
            api.preview(Voice("voice + &", "Voice", emptyList()),
                VoiceClip("A clip's name + & ?.mp3", "Clip"), target)
            assertEquals("MP3 clip bytes", target.readText())
            val parameters = query.get().split("&").associate {
                val (key, value) = it.split("=", limit = 2)
                key to java.net.URLDecoder.decode(value, "UTF-8")
            }
            assertEquals("voice + &", parameters["voice_id"])
            assertEquals("A clip's name + & ?.mp3", parameters["clip_id"])
        } finally { target.delete() }
    }

    @Test fun previewSurfacesServerErrorInsteadOfPassingItToPlayer() = runBlocking {
        respond("/voice-preview", """{"detail":"Clip missing"}""", 404)
        val target = File.createTempFile("preview-test", ".audio")
        try {
            try {
                api.preview(Voice("voice", "Voice", emptyList()), VoiceClip("missing", "Missing"), target)
                fail("Expected preview HTTP failure")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("404"))
                assertTrue(e.message.orEmpty().contains("Clip missing"))
            }
            assertEquals(0L, target.length())
        } finally { target.delete() }
    }

    @Test fun downloadWritesWavAndRejectsHtml() = runBlocking {
        respond("/download/ok", "RIFF0000WAVEaudio", type = "audio/wav")
        respond("/download/bad", "<html>Bad gateway</html>", type = "text/html")
        val target = File.createTempFile("ringtone-test", ".wav")
        try {
            api.download(GenerationJob("ok", "/status/ok", "/download/ok", "done"), target)
            assertEquals("RIFF0000WAVEaudio", target.readText())
            try {
                api.download(GenerationJob("bad", "/status/bad", "/download/bad", "done"), target)
                fail("Expected invalid content rejection")
            } catch (e: IOException) {
                assertTrue(e.message.orEmpty().contains("Expected WAV"))
            }
        } finally { target.delete() }
    }
}
