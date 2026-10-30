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
        api = RingtoneApi("http://127.0.0.1:${server.address.port}")
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

    @Test fun voicesKeepClipIdsExactlyAsReturned() = runBlocking {
        respond("/voices", """{"voices":[{"id":"voice","name":"Voice","clips":[{"id":"A clip's name.mp3","name":"Clip"}]}]}""")
        val voice = api.voices().single()
        assertEquals("voice", voice.id)
        assertEquals("A clip's name.mp3", voice.clips.single().id)
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
