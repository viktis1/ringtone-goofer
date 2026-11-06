package com.example.ringtone_goofer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Screen state is disposable; drafts, operation intent and results live in RingtoneLibrary. */
class RingtoneViewModel(application: Application) : AndroidViewModel(application) {
    val api = RingtoneApi()
    private val library = RingtoneLibrary.get(application)
    private val contactStore = ContactRingtoneStore(application)
    private val auth = FirebaseAuth.getInstance()
    private val voiceCache = VoiceCache(File(application.noBackupFilesDir, "voices"))
    val contacts = MutableLiveData<List<AddressBookContact>>(emptyList())
    val voices = MutableLiveData<List<Voice>>(emptyList())
    val voiceRefreshError = MutableLiveData<String?>(null)
    val refreshingVoices = MutableLiveData(false)
    val items = MutableLiveData<List<RingtoneItem>>(emptyList())
    val draft = MutableLiveData(RingtoneDraft())
    val status = MutableLiveData("Choose a contact to get started.")
    val recording = MutableLiveData(false)
    val stoppingRecording = MutableLiveData(false)
    val saving = MutableLiveData(false)
    val loaded = MutableLiveData(false)
    private var recorder: ContactAudioRecorder? = null
    private var voiceLoad: Job? = null
    private var accountLoad: Job? = null
    private var loadingContacts = false
    private val draftWrites = Channel<Pair<String, RingtoneDraft>>(Channel.UNLIMITED)
    private val authListener = FirebaseAuth.AuthStateListener { restoreAccount() }
    // Browsing contact audio is independent of the saved generation recipient.
    private val contactPreferences = application.getSharedPreferences("contact-selection", 0)
    val contactSelection = MutableLiveData("")
    val selectedContact get() = contacts.value.orEmpty().firstOrNull { it.lookupUri.toString() == contactSelection.value }
    val availableVoices get() = voices.value.orEmpty().filter {
        it.isAvailableForRecipient(draft.value?.contactName.orEmpty())
    }
    val generationContact get() = contacts.value.orEmpty().firstOrNull { it.lookupUri.toString() == draft.value?.contactUri }

    init {
        // A single consumer preserves rapid contact/voice changes in exactly their UI order.
        viewModelScope.launch(Dispatchers.IO) {
            for ((uid, value) in draftWrites) {
                try { library.saveDraft(uid, value) }
                catch (e: Exception) {
                    if (e is CancellationException) throw e
                    status.postValue("Could not save your selection: ${e.message}")
                }
            }
        }
        auth.addAuthStateListener(authListener)
    }

    private fun restoreAccount() {
        accountLoad?.cancel()
        voiceLoad?.cancel()
        voiceRefreshError.value = null
        refreshingVoices.value = false
        stopRecording()
        items.value = emptyList()
        voices.value = emptyList()
        draft.value = RingtoneDraft()
        loaded.value = false
        contactSelection.value = contactPreferences.getString(auth.currentUser?.uid ?: "guest", "").orEmpty()
        val uid = auth.currentUser?.uid ?: run {
            status.value = "Sign in to save your selections and generate ringtones."
            return
        }
        accountLoad = viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO) {
                    library.importHistory(getApplication(), uid)
                    library.draft(uid)
                }
                ensureActive()
                draft.value = saved
                loaded.value = true
                status.value = "Choose a contact to get started."
                loadVoices()
                library.revision.collect {
                    val rows = withContext(Dispatchers.IO) { library.items(uid) }
                    ensureActive()
                    if (auth.currentUser?.uid != uid) return@collect
                    val previous = items.value.orEmpty().associateBy { it.id }
                    val assignmentChanged = rows.any { it.stage == "assigned" && previous[it.id] != it }
                    items.value = rows
                    if (assignmentChanged) loadContacts()
                    // Repairs the tiny gap between saving an intent and enqueueing WorkManager.
                    rows.filter { it.active }.forEach { RingtoneWork.enqueue(getApplication(), it) }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status.value = "Cannot restore your library: ${e.message}"
            }
        }
    }

    private fun saveDraft(value: RingtoneDraft) {
        val uid = auth.currentUser?.uid ?: return
        if (loaded.value != true) return
        draft.value = value
        draftWrites.trySend(uid to value)
    }

    fun selectContact(contact: AddressBookContact) {
        if (recording.value == true) return
        contactSelection.value = contact.lookupUri.toString()
        contactPreferences.edit().putString(auth.currentUser?.uid ?: "guest", contact.lookupUri.toString()).apply()
    }

    fun selectGenerationContact(contact: AddressBookContact) {
        val current = draft.value ?: return
        val keepVoice = Voice(current.voiceId, "", emptyList()).isAvailableForRecipient(contact.name)
        saveDraft(current.copy(contactUri = contact.lookupUri.toString(), contactName = contact.name,
            voiceId = if (keepVoice) current.voiceId else "",
            clipId = if (keepVoice) current.clipId else ""))
    }

    fun selectVoice(voice: Voice, clip: VoiceClip? = null) {
        val current = draft.value ?: return
        if (!voice.isAvailableForRecipient(current.contactName)) return
        if (clip == null && current.voiceId == voice.id) return
        saveDraft(current.copy(voiceId = voice.id, clipId = clip?.id.orEmpty()))
    }

    fun loadContacts() {
        if (loadingContacts) return
        loadingContacts = true
        viewModelScope.launch {
            try {
                refreshContacts()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                contacts.value = emptyList()
                status.value = "Cannot load contacts: ${e.message}"
            } finally { loadingContacts = false }
        }
    }

    /** Refresh once for either contact picker or the Contacts tab; call on the main dispatcher. */
    suspend fun refreshContacts(): List<AddressBookContact> {
        val savedUri = draft.value?.contactUri.orEmpty()
        val (rows, resolved) = withContext(Dispatchers.IO) {
            val rows = contactStore.contacts()
            val direct = rows.firstOrNull { it.lookupUri.toString() == savedUri }
            val resolved = direct ?: if (savedUri.isNotBlank()) {
                val uri = android.provider.ContactsContract.Contacts.lookupContact(
                    getApplication<Application>().contentResolver, android.net.Uri.parse(savedUri))
                rows.firstOrNull { uri != null && it.lookupUri.lastPathSegment == uri.lastPathSegment }
            } else null
            rows to resolved
        }
        contacts.value = rows
        if (draft.value?.contactUri == savedUri && resolved != null) {
            saveDraft(draft.value!!.copy(contactUri = resolved.lookupUri.toString(), contactName = resolved.name))
        }
        if (rows.isEmpty()) status.value = "No contacts found."
        return rows
    }

    fun loadVoices() {
        val user = auth.currentUser ?: run {
            voiceRefreshError.value = "Voices couldn't be refreshed. Sign in and try again."
            return
        }
        if (voiceLoad?.isActive == true) return
        voiceRefreshError.value = null
        refreshingVoices.value = true
        voiceLoad = viewModelScope.launch {
            val client = RingtoneApi(idToken = {
                check(auth.currentUser?.uid == user.uid) { "Account changed" }
                user.getIdToken(false).await().token ?: error("Sign in again.")
            })
            try {
                val cached = withContext(Dispatchers.IO) { voiceCache.read(user.uid)?.let(client::parseVoices) }
                ensureActive()
                cached?.let { voices.value = it }
                val response = client.voicesJson()
                val fresh = client.parseVoices(response).sortedBy { it.name.lowercase() }
                withContext(Dispatchers.IO) { voiceCache.write(user.uid, response) }
                ensureActive()
                voices.value = fresh
                if (fresh.isEmpty()) status.value = "No voices available. Upload a recording or refresh later."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                voiceRefreshError.value = "Voices couldn't be refreshed. ${e.message ?: "Try again."}" +
                    if (voices.value.isNullOrEmpty()) "" else " Your saved voices are still available."
            } finally {
                refreshingVoices.value = false
            }
        }
    }

    fun generate() {
        if (saving.value == true || recording.value == true || loaded.value != true) return
        val uid = auth.currentUser?.uid ?: return
        val contact = generationContact ?: run { status.value = "Choose an available contact in Generate first."; return }
        val voice = availableVoices.firstOrNull { it.id == draft.value?.voiceId } ?: return
        val clip = voice.clips.firstOrNull { it.id == draft.value?.clipId } ?: return
        if (contact.name.isBlank()) { status.value = "Give this contact a name first."; return }
        val item = RingtoneItem(UUID.randomUUID().toString(), uid, contact.lookupUri.toString(),
            contact.name, voice.id, voice.name, clip.id, clip.name)
        saving.value = true
        operation {
            library.put(item) // Commit the complete request before any network side effect.
            RingtoneWork.enqueue(getApplication(), item)
            status.postValue("Generation saved. You can close the app and return to History.")
        }.invokeOnCompletion { saving.postValue(false) }
    }

    fun download(item: RingtoneItem) = changeStage(item, "downloading")
    fun assign(item: RingtoneItem) = changeStage(item, "assigning")
    fun retry(item: RingtoneItem) {
        if (item.resumeStage.isNotBlank()) changeStage(item, item.resumeStage)
    }

    private fun changeStage(item: RingtoneItem, stage: String) = operation {
        check(auth.currentUser?.uid == item.uid) { "Sign in to the original account." }
        val updated = library.update(item.uid, item.id) { it.copy(stage = stage, detail = "", resumeStage = "") }
        RingtoneWork.enqueue(getApplication(), updated, explicit = true)
    }

    fun setAssignmentContact(item: RingtoneItem, contact: AddressBookContact) = operation {
        check(auth.currentUser?.uid == item.uid)
        library.update(item.uid, item.id) { it.copy(contactUri = contact.lookupUri.toString(), contactName = contact.name) }
    }

    fun generateAgain(item: RingtoneItem) {
        if (item.clipId.isBlank() || saving.value == true) return
        saving.value = true
        operation {
            check(auth.currentUser?.uid == item.uid)
            val next = item.copy(id = UUID.randomUUID().toString(), created = System.currentTimeMillis(),
                stage = "submitting", route = "", detail = "", mediaUri = "", resumeStage = "", generationStatusJson = "")
            // A new UUID needs new server URLs.
            val normalized = next.id.replace("-", "")
            val request = next.copy(statusUrl = "/status/$normalized", downloadUrl = "/download/$normalized")
            library.put(request)
            RingtoneWork.enqueue(getApplication(), request)
        }.invokeOnCompletion { saving.postValue(false) }
    }

    private fun operation(block: suspend () -> Unit): Job = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: Exception) {
            if (e is CancellationException) throw e
            status.postValue(e.message ?: "Operation failed.")
        }
    }

    fun recordingFile(contact: AddressBookContact): File =
        contactRecordingFile(getApplication<Application>().filesDir, contact.name)

    fun startRecording() {
        if (recording.value == true) return
        val contact = selectedContact ?: return
        val file = recordingFile(contact)
        val session = ContactAudioRecorder(getApplication(), file)
        recorder = session
        recording.value = true
        stoppingRecording.value = false
        status.value = "Recording for ${contact.name}… Stop and save in Contacts. Leaving the app saves automatically."
        viewModelScope.launch {
            try {
                session.record()
                status.value = "Recording saved for ${contact.name}."
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                status.value = "Cannot record: ${e.message}"
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
        recorder?.stop()
    }

    fun uploadRecording() {
        val contact = selectedContact ?: return
        val uid = auth.currentUser?.uid ?: return
        operation {
            val original = recordingFile(contact)
            check(original.isFile) { "Record a voice sample first." }
            // Freeze the upload bytes so recording again cannot alter a queued upload.
            val id = UUID.randomUUID().toString()
            val snapshot = File(getApplication<Application>().noBackupFilesDir, "voice-uploads/${original.name.removeSuffix(".wav")}-$id.wav")
            check(snapshot.parentFile!!.isDirectory || snapshot.parentFile!!.mkdirs())
            original.copyTo(snapshot)
            val item = RingtoneItem(id, uid, contact.lookupUri.toString(), contact.name,
                "", "Voice recording", "", original.name, stage = "uploading", recordingPath = snapshot.absolutePath)
            library.put(item)
            RingtoneWork.enqueue(getApplication(), item)
            status.postValue("Upload saved. Track it in History, then refresh voices in Generate after processing.")
        }
    }

    override fun onCleared() {
        auth.removeAuthStateListener(authListener)
        recorder?.stop()
        super.onCleared()
    }
}
