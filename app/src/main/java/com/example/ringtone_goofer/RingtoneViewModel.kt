package com.example.ringtone_goofer

import android.app.Application
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
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
    val recording = MutableLiveData(false)
    val stoppingRecording = MutableLiveData(false)
    private var recorder: ContactAudioRecorder? = null
    val generated = MutableLiveData<GeneratedRingtone?>(null)
    var selectedContact: AddressBookContact? = null
    var voiceId: String? = null
    var clipId: String? = null
    private var generation: Job? = null
    private var loadingContacts = false
    private val auth = FirebaseAuth.getInstance()
    private val voiceCache = VoiceCache(File(application.noBackupFilesDir, "voices"))
    private var voiceUserId: String? = null
    private var voiceLoad: Job? = null
    private val authListener = FirebaseAuth.AuthStateListener { loadVoices() }

    init { auth.addAuthStateListener(authListener) }

    override fun onCleared() {
        auth.removeAuthStateListener(authListener)
        super.onCleared()
    }

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
        val user = auth.currentUser
        if (voiceUserId != user?.uid) {
            voiceLoad?.cancel()
            voiceUserId = user?.uid
            voiceId = null
            clipId = null
            voices.value = emptyList()
        }
        if (user == null || voiceLoad?.isActive == true) return
        voiceLoad = viewModelScope.launch {
            // Pin this request to the captured user, even if the account changes while awaiting IO.
            val client = RingtoneApi(idToken = {
                user.getIdToken(false).await().token ?: error("Sign in with Google first.")
            })
            if (voices.value.isNullOrEmpty()) {
                val cached = withContext(Dispatchers.IO) {
                    try { voiceCache.read(user.uid)?.let(client::parseVoices) }
                    catch (_: Exception) { null }
                }
                ensureActive()
                if (auth.currentUser?.uid != user.uid) return@launch
                cached?.let { voices.value = it }
            }
            try {
                val response = client.voicesJson()
                val fresh = client.parseVoices(response)
                ensureActive()
                if (auth.currentUser?.uid != user.uid) return@launch
                voices.value = fresh
                try {
                    withContext(Dispatchers.IO) { voiceCache.write(user.uid, response) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (auth.currentUser?.uid == user.uid)
                        status.value = "Voices refreshed, but could not save cache: ${e.message}"
                    return@launch
                }
                ensureActive()
                if (auth.currentUser?.uid == user.uid)
                    status.value = if (fresh.isEmpty()) "The server has no voices." else "Voices refreshed."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (auth.currentUser?.uid == user.uid) {
                    status.value = if (voices.value.isNullOrEmpty()) "Cannot load voices: ${e.message}"
                        else "Cannot refresh voices; keeping saved voices: ${e.message}"
                }
            }
        }
    }

    fun generate(viaUpload: Boolean = false) {
        if (busy.value == true || recording.value == true) return
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
        if (viaUpload) uploadGeneration(work) else startGeneration(work)
    }

    private fun uploadGeneration(work: PendingGeneration) {
        val user = auth.currentUser ?: run {
            status.value = "Sign in before submitting a generation request."
            return
        }
        busy.value = true
        generation = viewModelScope.launch {
            try {
                status.value = "Uploading generation request…"
                val bucket = getApplication<Application>().getString(R.string.generation_request_bucket_url)
                RingtoneRequestUpload(FirebaseStorage.getInstance(bucket)).submit(user.uid, work.request)
                status.value = "Generation request uploaded for ${work.contact.name}.\nJob: ${work.request.jobId}\nQueued for the server; no status monitoring on this route yet."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = "Generation upload failed: ${e.message}"
            } finally {
                busy.value = false
            }
        }
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
        status.value = "Stopped local work. A submitted request may still finish on the server."
    }

    fun recordingFile(contact: AddressBookContact): File =
        contactRecordingFile(getApplication<Application>().filesDir, contact.name)

    fun startRecording() {
        if (recording.value == true || busy.value == true) return
        val contact = selectedContact ?: return
        val file = recordingFile(contact)
        val session = ContactAudioRecorder(getApplication(), file)
        recorder = session
        recording.value = true
        stoppingRecording.value = false
        status.value = "Recording for ${contact.name}… Tap Stop and save."
        viewModelScope.launch {
            try {
                session.record()
                status.value = "Saved recording/${file.name}."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.value = "Cannot record audio: ${e.message}"
            } finally {
                recorder = null
                recording.value = false
                stoppingRecording.value = false
            }
        }
    }

    fun stopRecording() {
        if (recording.value != true || stoppingRecording.value == true) return
        stoppingRecording.value = true
        status.value = "Saving recording…"
        recorder?.stop()
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
