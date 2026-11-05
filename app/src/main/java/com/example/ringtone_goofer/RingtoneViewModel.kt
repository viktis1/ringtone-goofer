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
    val submittedJobs = MutableLiveData<List<SubmittedJob>>(emptyList())
    val jobsMessage = MutableLiveData("")
    private val jobHistory = JobHistory(File(application.noBackupFilesDir, "jobs"))
    private var jobsPolling: Job? = null
    private val authListener = FirebaseAuth.AuthStateListener { loadVoices(); loadJobs() }

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

    private suspend fun rememberJob(uid: String, job: SubmittedJob) {
        val rows = withContext(Dispatchers.IO) { jobHistory.put(uid, job) }
        if (auth.currentUser?.uid == uid) submittedJobs.value = rows
    }

    fun loadJobs() {
        jobsPolling?.cancel()
        submittedJobs.value = emptyList()
        val user = auth.currentUser ?: run {
            jobsMessage.value = "Sign in to see your submitted jobs."
            return
        }
        jobsPolling = viewModelScope.launch {
            try {
                val rows = withContext(Dispatchers.IO) { jobHistory.read(user.uid) }
                ensureActive()
                if (auth.currentUser?.uid != user.uid) return@launch
                submittedJobs.value = rows
                val client = RingtoneApi(idToken = {
                    user.getIdToken(false).await().token ?: error("Sign in first.")
                })
                while (true) {
                    jobsMessage.value = "Checking job status…"
                    for (job in submittedJobs.value.orEmpty().filterNot { it.finished }) {
                        val updated = try {
                            val result = client.status(job.serverJob())
                            job.copy(status = result.status, detail = result.error.orEmpty())
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            if (e is ApiHttpException && e.code == 404)
                                job.copy(detail = "Not on server yet; waiting for worker or acceptance.")
                            else job.copy(detail = "Status unavailable: ${e.message}")
                        }
                        ensureActive()
                        if (auth.currentUser?.uid != user.uid) return@launch
                        rememberJob(user.uid, updated)
                    }
                    jobsMessage.value = "Jobs submitted from this app. Status checks run while the app is open."
                    if (submittedJobs.value.orEmpty().all { it.finished }) break
                    delay(10_000)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (auth.currentUser?.uid == user.uid) jobsMessage.value = "Cannot load jobs: ${e.message}"
            }
        }
    }

    fun generate() {
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
        startGeneration(work)
    }

    private fun startGeneration(work: PendingGeneration) {
        busy.value = true
        generation = viewModelScope.launch {
            val temp = File(getApplication<Application>().cacheDir, "ringtone-${work.request.jobId}.wav")
            try {
                status.value = "Submitting…"
                val user = auth.currentUser ?: error("Sign in first.")
                val client = RingtoneApi(idToken = {
                    user.getIdToken(false).await().token ?: error("Sign in first.")
                })
                val submission = submitGeneration(work.request, client::generate) { request ->
                    ensureActive()
                    check(auth.currentUser?.uid == user.uid) { "Account changed. Please generate again." }
                    status.value = "Server unavailable. Uploading generation request…"
                    val bucket = getApplication<Application>().getString(R.string.generation_request_bucket_url)
                    RingtoneRequestUpload(FirebaseStorage.getInstance(bucket)).submit(user.uid, request)
                }
                val job = submission.job
                rememberJob(user.uid, SubmittedJob(job.id, work.contact.name, submission.route, job.status))
                loadJobs()
                var current = GenerationStatus(job.status, null)
                while (true) {
                    ensureActive()
                    check(auth.currentUser?.uid == user.uid) { "Account changed. Monitoring stopped." }
                    status.value = "${current.status} · ${work.contact.name}\nJob: ${job.id}"
                    when (current.status) {
                        "done" -> break
                        "failed" -> {
                            error(current.error ?: client.status(job).error ?: "Generation failed")
                        }
                        "awaiting_worker", "queued", "running" -> {
                            delay(4_000)
                            try {
                                current = client.status(job)
                            } catch (e: Exception) {
                                if (!e.isServerUnavailable() &&
                                    !(submission.route == "Upload" && e is ApiHttpException && e.code == 404)) throw e
                                status.value = "Waiting for server/worker…\nJob: ${job.id}"
                                delay(6_000)
                            }
                        }
                        else -> error("Unknown job status: ${current.status}")
                    }
                }
                status.value = "Downloading WAV…"
                client.download(job, temp)
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
