package com.example.ringtone_goofer

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

data class GeneratedRingtone(val uri: Uri, val contact: AddressBookContact)
data class PendingGeneration(val request: GenerationRequest, val contact: AddressBookContact)

/** All screen and job state lives here so rotation never submits a second job. */
class RingtoneViewModel(application: Application) : AndroidViewModel(application) {
    val api = RingtoneApi()
    private val store = ContactRingtoneStore(application)
    val contacts = MutableLiveData<List<AddressBookContact>>(emptyList())
    val voices = MutableLiveData<List<Voice>>(emptyList())
    val status = MutableLiveData("Load contacts and choose a person.")
    val busy = MutableLiveData(false)
    val generated = MutableLiveData<GeneratedRingtone?>(null)
    var selectedContact: AddressBookContact? = null
    var voiceId: String? = null
    var clipId: String? = null
    private var generation: Job? = null
    private var loadingContacts = false
    private var loadingVoices = false

    init { loadVoices() }

    fun loadContacts() {
        if (loadingContacts) return
        loadingContacts = true
        viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { store.contacts() }
                contacts.value = rows
                selectedContact = rows.firstOrNull { it.lookupUri == selectedContact?.lookupUri }
                if (rows.isEmpty()) status.value = "No contacts found."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status.value = "Cannot load contacts: ${e.message}"
            } finally { loadingContacts = false }
        }
    }

    fun loadVoices() {
        if (loadingVoices) return
        loadingVoices = true
        viewModelScope.launch {
            try {
                voices.value = api.voices()
                if (voices.value.isNullOrEmpty()) status.value = "The server has no voices."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status.value = "Cannot load voices: ${e.message}"
            } finally { loadingVoices = false }
        }
    }

    fun generate() {
        if (busy.value == true) return
        val contact = selectedContact ?: return
        val voice = voices.value?.firstOrNull { it.id == voiceId } ?: return
        val clip = voice.clips.firstOrNull { it.id == clipId } ?: return
        if (contact.name.isBlank()) {
            status.value = "This contact needs a name before generating a ringtone."
            return
        }
        val work = PendingGeneration(GenerationRequest(UUID.randomUUID().toString(),
            contact.name, voice.id, clip.id), contact)
        generated.value = null
        startGeneration(work)
    }

    private fun startGeneration(work: PendingGeneration) {
        busy.value = true
        generation = viewModelScope.launch {
            val temp = File(getApplication<Application>().cacheDir, "ringtone-${work.request.jobId}.wav")
            try {
                status.value = "Submitting…"
                val job = api.generate(work.request)
                var current = GenerationStatus(job.status, null)
                while (true) {
                    status.value = "${current.status} · ${work.contact.name}\nJob: ${job.id}"
                    when (current.status) {
                        "done" -> break
                        "failed" -> {
                            error(current.error ?: api.status(job).error ?: "Generation failed")
                        }
                        "queued", "running" -> {
                            delay(4_000)
                            current = api.status(job)
                        }
                        else -> error("Unknown job status: ${current.status}")
                    }
                }
                status.value = "Downloading WAV…"
                api.download(job, temp)
                val uri = withContext(Dispatchers.IO) {
                    ensureActive()
                    store.saveWav(temp, work.request.jobId, work.contact.name)
                }
                generated.value = GeneratedRingtone(uri, work.contact)
                status.value = "Downloaded for ${work.contact.name}. Tap Assign to contact."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = "Generation/download error: ${e.message}"
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.IO) { temp.delete() }
                    busy.value = false
                }
            }
        }
    }

    fun cancel() {
        generation?.cancel()
        status.value = "Stopped checking this job. The server may still finish it."
    }

    fun assign() {
        val result = generated.value ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { store.assign(result.contact, result.uri) }
                status.value = "Assigned to ${result.contact.name}."
                loadContacts()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status.value = "Cannot assign ringtone: ${e.message}"
            }
        }
    }
}
