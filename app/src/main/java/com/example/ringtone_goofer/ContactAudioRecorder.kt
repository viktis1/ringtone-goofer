package com.example.ringtone_goofer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

private const val SAMPLE_RATE = 44_100
private const val BYTES_PER_SAMPLE = 2

/** Name-based storage, with path separators removed so contacts cannot escape this folder. */
internal fun contactRecordingFile(filesDir: File, contactName: String): File {
    val name = contactName.trim()
        .replace(Regex("\\s+"), "-")
        .replace(Regex("[/\\\\\\p{Cntrl}]"), "-")
        .trim('.')
        .take(60)
        .ifBlank { "unnamed-contact" }
    return File(File(filesDir, "recording"), "$name.wav")
}

/** PCM, mono, 44.1 kHz, 16-bit: uncompressed audio, approximately 5 MB per minute. */
internal fun pcmWavHeader(audioBytes: Long): ByteArray {
    require(audioBytes in 0..0xffff_ffffL - 36 && audioBytes % BYTES_PER_SAMPLE == 0L)
    return ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt((36 + audioBytes).toInt())
        put("WAVEfmt ".toByteArray(Charsets.US_ASCII))
        putInt(16) // PCM format chunk length
        putShort(1) // Linear PCM
        putShort(1) // Mono
        putInt(SAMPLE_RATE)
        putInt(SAMPLE_RATE * BYTES_PER_SAMPLE)
        putShort(BYTES_PER_SAMPLE.toShort())
        putShort(16)
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(audioBytes.toInt())
    }.array()
}

/** One recording session. Only the IO coroutine touches AudioRecord or the output file. */
class ContactAudioRecorder(private val context: Context, private val target: File) {
    private val stopRequested = AtomicBoolean(false)

    fun stop() { stopRequested.set(true) }

    suspend fun record() = withContext(Dispatchers.IO) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Microphone permission is required.")
        }
        val directory = target.parentFile ?: error("Recording directory unavailable")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create recording folder." }
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "This device does not support 44.1 kHz PCM recording." }
        val bufferSize = maxOf(minimum * 2, SAMPLE_RATE / 5 * BYTES_PER_SAMPLE)
        val audio = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(bufferSize)
            .build()
        var temporary: File? = null
        try {
            check(audio.state == AudioRecord.STATE_INITIALIZED) { "Microphone unavailable." }
            val temp = File.createTempFile("recording-", ".tmp", directory)
            temporary = temp
            RandomAccessFile(temp, "rw").use { output ->
                output.write(pcmWavHeader(0))
                audio.startRecording()
                check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone did not start." }
                val buffer = ByteArray(bufferSize)
                var bytes = 0L
                try {
                    while (!stopRequested.get()) {
                        ensureActive()
                        val count = audio.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                        check(count >= 0) { "Microphone read failed ($count)." }
                        if (count == 0) {
                            delay(10)
                        } else {
                            check(bytes + count <= 0xffff_ffffL - 36) { "Recording reached the WAV size limit." }
                            output.write(buffer, 0, count)
                            bytes += count
                        }
                    }
                } catch (e: CancellationException) {
                    // onStop requests a save before activity destruction cancels its ViewModel.
                    // Finish that save on this IO thread, even if cancellation arrives during read/delay.
                    if (!stopRequested.get()) throw e
                }
                audio.stop()
                check(bytes > 0) { "No audio captured. Please record for a little longer." }
                output.seek(0)
                output.write(pcmWavHeader(bytes))
                output.fd.sync()
            }
            // Replace only after the complete WAV is safely written; preserve an old recording on errors.
            check(temp.renameTo(target)) { "Cannot save recording." }
        } finally {
            runCatching { if (audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) audio.stop() }
            audio.release()
            temporary?.delete()
        }
    }
}
