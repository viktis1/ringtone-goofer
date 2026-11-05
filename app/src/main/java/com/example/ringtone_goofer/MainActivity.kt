package com.example.ringtone_goofer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageMetadata
import com.google.firebase.storage.UploadTask
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.tasks.await
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private val auth by lazy { FirebaseAuth.getInstance() }
    private lateinit var signInButton: Button
    private lateinit var uploadButton: Button
    private var uploading = false
    private lateinit var model: RingtoneViewModel
    private lateinit var selected: TextView
    private lateinit var status: TextView
    private lateinit var voicePicker: Spinner
    private lateinit var clipPicker: Spinner
    private lateinit var generateButton: Button
    private lateinit var previewButton: Button
    private lateinit var recordButton: Button
    private lateinit var playRecordingButton: Button
    private lateinit var playContactButton: Button
    private lateinit var assignButton: Button
    private lateinit var cancelButton: Button
    private lateinit var contactRows: ArrayAdapter<String>
    private var rows = emptyList<AddressBookContact>()
    private var voiceChoices = emptyList<Voice>()
    private var clipChoices = emptyList<VoiceClip>()
    private var previewTask: Job? = null
    private var player: MediaPlayer? = null
    private var ringtone: Ringtone? = null
    private val handler = Handler(Looper.getMainLooper())
    private var pendingPermission: String? = null

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permission = pendingPermission
        pendingPermission = null
        if (granted) {
            when (permission) {
                Manifest.permission.READ_CONTACTS -> model.loadContacts()
                Manifest.permission.WRITE_CONTACTS -> model.assign()
                Manifest.permission.RECORD_AUDIO -> model.startRecording()
                Manifest.permission.WRITE_EXTERNAL_STORAGE -> model.generate()
            }
        } else {
            model.status.value = "Permission denied. This action is unavailable."
            if (permission != null && !shouldShowRequestPermissionRationale(permission)) {
                AlertDialog.Builder(this)
                    .setMessage("You can enable this permission in app settings.")
                    .setPositiveButton("Settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", packageName, null)))
                    }.setNegativeButton("Cancel", null).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingPermission = savedInstanceState?.getString("permission")
        model = ViewModelProvider(this)[RingtoneViewModel::class.java]
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        fun label(text: String) = TextView(this).apply { this.text = text; header.addView(this) }
        fun button(text: String, action: () -> Unit) = Button(this).apply {
            this.text = text; setOnClickListener { action() }; header.addView(this)
        }
        label("Ringtone Goofer")
        signInButton = button("Sign in with Google") { signIn() }
        updateSignInButton()
        button("Load / refresh contacts") {
            withPermission(Manifest.permission.READ_CONTACTS,
                "Contacts access lets you select a person and see their current ringtone.") { model.loadContacts() }
        }
        selected = label("Select a contact from the overview below.")
        playContactButton = button("Play selected contact's ringtone") { playContact() }
        button("Stop audio") { stopAudio() }
        recordButton = button("Record audio for selected contact") {
            if (model.recording.value == true) model.stopRecording() else requestRecording()
        }
        playRecordingButton = button("Play recorded audio for selected contact") { playRecording() }
        uploadButton = button("Upload recorded audio for selected contact") { uploadRecording() }
        label("Generate a new ringtone")
        label("Only the selected contact's name is sent to the ringtone server. Receiver: viktor.")
        button("Load / retry voices") { model.loadVoices() }
        label("Voice")
        voicePicker = Spinner(this).also { header.addView(it) }
        label("Clip")
        clipPicker = Spinner(this).also { header.addView(it) }
        previewButton = button("Preview clip") {
            val voice = voiceChoices.firstOrNull { it.id == model.voiceId }
            val clip = clipChoices.firstOrNull { it.id == model.clipId }
            if (voice != null && clip != null) playPreview(voice, clip)
        }
        generateButton = button("Generate ringtone") {
            stopAudio()
            if (Build.VERSION.SDK_INT < 29) {
                withPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE,
                    "Storage access is needed on this Android version to save a shared ringtone.") { model.generate() }
            } else model.generate()
        }
        cancelButton = button("Cancel job monitoring") { model.cancel() }
        assignButton = button("Assign downloaded ringtone to contact") {
            withPermission(Manifest.permission.WRITE_CONTACTS,
                "Contacts write access is needed to assign the downloaded ringtone.") { model.assign() }
        }
        status = label("")
        label("All contacts · tap to select, then Play or Generate")

        contactRows = ArrayAdapter(this, android.R.layout.simple_list_item_1, mutableListOf<String>())
        val list = ListView(this).apply {
            addHeaderView(header, null, false)
            adapter = contactRows
            setOnItemClickListener { _, _, position, _ ->
                rows.getOrNull(position - headerViewsCount)?.let {
                    if (model.busy.value != true && model.recording.value != true) {
                        stopAudio()
                        model.selectedContact = it
                        updateControls()
                    }
                }
            }
        }
        ViewCompat.setOnApplyWindowInsetsListener(list) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(list)
        voicePicker.onItemSelectedListener = selectionListener { position ->
            val voice = voiceChoices.getOrNull(position) ?: return@selectionListener
            if (model.voiceId != voice.id) { stopAudio(); model.clipId = null }
            model.voiceId = voice.id
            showClips(voice)
        }
        clipPicker.onItemSelectedListener = selectionListener { position ->
            val id = clipChoices.getOrNull(position)?.id
            if (model.clipId != id) stopAudio()
            model.clipId = id
            updateControls()
        }
        model.contacts.observe(this) {
            rows = it
            contactRows.clear()
            contactRows.addAll(it.map { contact ->
                "${contact.name.ifBlank { "(Unnamed contact)" }} · ${contact.ringtoneName}"
            })
            updateControls()
        }
        model.voices.observe(this) {
            stopAudio()
            if (it.isEmpty()) {
                model.voiceId = null
                model.clipId = null
                clipChoices = emptyList()
                clipPicker.adapter = ArrayAdapter<String>(this, android.R.layout.simple_spinner_dropdown_item, emptyList())
            }
            voiceChoices = it
            voicePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, it.map(Voice::name))
            val index = it.indexOfFirst { voice -> voice.id == model.voiceId }.coerceAtLeast(0)
            voicePicker.setSelection(index)
            it.getOrNull(index)?.let { voice -> model.voiceId = voice.id; showClips(voice) }
            updateControls()
        }
        model.status.observe(this) { status.text = it }
        model.busy.observe(this) { updateControls() }
        model.recording.observe(this) { updateControls() }
        model.stoppingRecording.observe(this) { updateControls() }
        model.generated.observe(this) { updateControls() }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
            model.loadContacts()
        }
    }

    private fun selectionListener(action: (Int) -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = action(position)
        override fun onNothingSelected(parent: AdapterView<*>?) {}
    }

    private fun showClips(voice: Voice) {
        clipChoices = voice.clips
        clipPicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, clipChoices.map(VoiceClip::name))
        val index = clipChoices.indexOfFirst { it.id == model.clipId }.coerceAtLeast(0)
        clipPicker.setSelection(index)
        model.clipId = clipChoices.getOrNull(index)?.id
        updateControls()
    }

    private fun updateControls() {
        val recording = model.recording.value == true
        val busy = model.busy.value == true || recording
        val contact = model.selectedContact
        selected.text = contact?.let { "Selected: ${it.name} · ${it.ringtoneName}" } ?: "Select a contact below."
        voicePicker.isEnabled = !busy
        clipPicker.isEnabled = !busy
        generateButton.isEnabled = !busy && contact != null && model.clipId != null
        previewButton.isEnabled = !recording && model.clipId != null
        playContactButton.isEnabled = !recording && contact != null
        recordButton.text = if (recording) "Stop and save recording" else "Record audio for selected contact"
        recordButton.isEnabled = if (recording) model.stoppingRecording.value != true else !busy && !uploading && contact != null
        uploadButton.isEnabled = !recording && !uploading && contact != null
        playRecordingButton.isEnabled = !recording && contact != null
        cancelButton.isEnabled = model.busy.value == true
        assignButton.isEnabled = !busy && model.generated.value != null
        model.generated.value?.let { assignButton.text = "Assign ringtone to ${it.contact.name}" }
    }

    private fun withPermission(permission: String, rationale: String, action: () -> Unit) {
        when {
            ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED -> action()
            shouldShowRequestPermissionRationale(permission) -> AlertDialog.Builder(this)
                .setMessage(rationale).setPositiveButton("Continue") { _, _ ->
                    pendingPermission = permission; permissionLauncher.launch(permission)
                }.setNegativeButton("Cancel", null).show()
            else -> { pendingPermission = permission; permissionLauncher.launch(permission) }
        }
    }

    private fun playContact() {
        if (model.recording.value == true) return
        val contact = model.selectedContact ?: return
        stopAudio()
        try {
            val uri = contact.ringtone ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            if (contact.ringtone == null &&
                RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_RINGTONE) == null) {
                model.status.value = "Default ringtone is silent."
                return
            }
            ringtone = RingtoneManager.getRingtone(this, uri) ?: error("Ringtone unavailable")
            ringtone?.play()
        } catch (e: Exception) {
            stopAudio()
            model.status.value = "Cannot play ringtone: ${e.message}"
        }
    }

    private fun playPreview(voice: Voice, clip: VoiceClip) {
        if (model.recording.value == true) return
        stopAudio()
        model.status.value = "Loading preview…"
        previewTask = lifecycleScope.launch {
            var file: File? = null
            try {
                val cached = File.createTempFile("voice-preview-", ".audio", cacheDir)
                file = cached
                model.api.preview(voice, clip, cached)
                playLocalAudio(cached, "preview: ${clip.name}")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                stopAudio()
                model.status.value = "Cannot preview clip: ${e.message}"
            } finally {
                file?.delete()
            }
        }
    }

    private fun requestRecording() {
        val contact = model.selectedContact ?: return
        stopAudio()
        val start = {
            withPermission(Manifest.permission.RECORD_AUDIO,
                "Microphone access is needed to record audio for this contact.") { model.startRecording() }
        }
        val file = model.recordingFile(contact)
        if (file.exists()) {
            AlertDialog.Builder(this)
                .setMessage("Replace the existing recording ${file.name}?")
                .setPositiveButton("Record again") { _, _ -> start() }
                .setNegativeButton("Cancel", null).show()
        } else start()
    }

    private fun playRecording() {
        if (model.recording.value == true) return
        val contact = model.selectedContact ?: return
        stopAudio()
        val file = model.recordingFile(contact)
        if (!file.isFile) {
            model.status.value = "No recording for ${contact.name} yet."
            return
        }
        try {
            playLocalAudio(file, "recording for ${contact.name}")
        } catch (e: Exception) {
            stopAudio()
            model.status.value = "Cannot play recording: ${e.message}"
        }
    }

    private fun playLocalAudio(file: File, description: String) {
        val audio = MediaPlayer()
        player = audio
        audio.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        // MediaPlayer keeps its own descriptor, including for a preview cache file deleted after this call.
        FileInputStream(file).use { audio.setDataSource(it.fd) }
        audio.setOnPreparedListener {
            if (player === it) {
                handler.removeCallbacksAndMessages(null)
                it.start()
                model.status.value = "Playing $description"
            }
        }
        audio.setOnCompletionListener {
            if (player === it) { stopAudio(); model.status.value = "Playback finished." }
        }
        audio.setOnErrorListener { failed, what, extra ->
            if (player === failed) {
                stopAudio()
                model.status.value = "Cannot play $description (player error $what / $extra)."
            }
            true
        }
        handler.postDelayed({
            if (player === audio) { stopAudio(); model.status.value = "Audio playback timed out." }
        }, 30_000)
        audio.prepareAsync()
    }

    private fun stopAudio() {
        previewTask?.cancel()
        previewTask = null
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        ringtone?.stop()
        ringtone = null
    }

    private fun uploadRecording() {
        if (uploading || model.recording.value == true) return
        val contact = model.selectedContact ?: return
        val file = model.recordingFile(contact)
        if (!file.isFile) {
            model.status.value = "No recording for ${contact.name} yet."
            return
        }
        val user = auth.currentUser ?: run {
            model.status.value = "Sign in before uploading."
            return
        }
        uploading = true
        updateControls()
        lifecycleScope.launch {
            var upload: UploadTask? = null
            try {
                val fileName = file.name
                val storage = FirebaseStorage.getInstance(getString(R.string.upload_bucket_url))
                val destination = storage.reference.child("users/${user.uid}/$fileName")
                val metadata = StorageMetadata.Builder()
                    .setContentType("audio/wav")
                    .setCustomMetadata("uuid", UUID.randomUUID().toString())
                    .build()
                model.status.value = "Uploading recording for ${contact.name}…"
                upload = destination.putFile(Uri.fromFile(file), metadata)
                upload.await()
                model.status.value = "Uploaded to ${destination.path}"
            } catch (e: CancellationException) {
                upload?.cancel()
                throw e
            } catch (e: Exception) {
                model.status.value = "Upload failed: ${e.localizedMessage}"
            } finally {
                uploading = false
                updateControls()
            }
        }
    }

    private fun updateSignInButton() {
        val user = auth.currentUser
        signInButton.text = if (user == null) "Sign in with Google"
            else "Signed in as ${user.email ?: user.displayName ?: "Google user"}"
        signInButton.isEnabled = user == null
    }

    private fun signIn() {
        signInButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(
                    getString(R.string.default_web_client_id)
                ).build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build()
                val credential = CredentialManager.create(this@MainActivity)
                    .getCredential(this@MainActivity, request).credential
                check(credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    "Unexpected sign-in credential."
                }
                val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
                auth.signInWithCredential(GoogleAuthProvider.getCredential(idToken, null)).await()
                model.loadVoices()
            } catch (_: GetCredentialCancellationException) {
                // Closing the account picker leaves the user signed out.
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                model.status.value = "Sign-in failed: ${e.localizedMessage}"
            } finally {
                updateSignInButton()
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("permission", pendingPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        stopAudio()
        model.stopRecording()
        super.onStop()
    }
}
