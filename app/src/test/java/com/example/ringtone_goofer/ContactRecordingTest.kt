package com.example.ringtone_goofer

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import javax.sound.sampled.AudioSystem

class ContactRecordingTest {
    @Test fun recordingUsesRequestedFolderAndContactFilename() {
        val file = contactRecordingFile(File("/private/app/files"), "John Smith")
        assertEquals(File("/private/app/files/recording/John-Smith.wav"), file)
        assertEquals("Søren-Åberg.wav", contactRecordingFile(File("/private/app/files"), "Søren Åberg").name)
    }

    @Test fun unusualNamesCannotEscapeRecordingDirectory() {
        val root = File(System.getProperty("java.io.tmpdir"), "contact-recording-test")
        listOf("../outside", "..\\outside", ".", "   ", "/etc/passwd", "A\nB",
            "😀".repeat(200)).forEach { name ->
            val file = contactRecordingFile(root, name)
            assertEquals(File(root, "recording").canonicalFile, file.canonicalFile.parentFile)
            assertTrue(file.name.toByteArray(Charsets.UTF_8).size <= 255)
            assertTrue(file.name.endsWith(".wav"))
        }
    }

    @Test fun wavIsReadableByAnIndependentAudioParser() {
        // One second of silent PCM. The JDK WAV reader validates the actual file format.
        val pcm = ByteArray(44_100 * 2)
        AudioSystem.getAudioInputStream(ByteArrayInputStream(pcmWavHeader(pcm.size.toLong()) + pcm)).use { input ->
            assertEquals(44_100f, input.format.sampleRate, 0f)
            assertEquals(16, input.format.sampleSizeInBits)
            assertEquals(1, input.format.channels)
            assertEquals(2, input.format.frameSize)
            assertFalse(input.format.isBigEndian)
            assertEquals(44_100L, input.frameLength)
            assertArrayEquals(pcm, input.readBytes())
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun wavRejectsIncompletePcmSamples() {
        pcmWavHeader(3)
    }
}
