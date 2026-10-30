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

class MainActivity : AppCompatActivity() {
    private lateinit var model: RingtoneViewModel
    private lateinit var selected: TextView
    private lateinit var status: TextView
    private lateinit var voicePicker: Spinner
    private lateinit var clipPicker: Spinner
    private lateinit var generateButton: Button
    private lateinit var previewButton: Button
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
        button("Load / refresh contacts") {
            withPermission(Manifest.permission.READ_CONTACTS,
                "Contacts access lets you select a person and see their current ringtone.") { model.loadContacts() }
        }
        selected = label("Select a contact from the overview below.")
        button("Play selected contact's ringtone") { playContact() }
        button("Stop audio") { stopAudio() }
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
                    if (model.busy.value != true) {
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
            voiceChoices = it
            voicePicker.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, it.map(Voice::name))
            val index = it.indexOfFirst { voice -> voice.id == model.voiceId }.coerceAtLeast(0)
            voicePicker.setSelection(index)
            it.getOrNull(index)?.let { voice -> model.voiceId = voice.id; showClips(voice) }
            updateControls()
        }
        model.status.observe(this) { status.text = it }
        model.busy.observe(this) { updateControls() }
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
        val busy = model.busy.value == true
        val contact = model.selectedContact
        selected.text = contact?.let { "Selected: ${it.name} · ${it.ringtoneName}" } ?: "Select a contact below."
        voicePicker.isEnabled = !busy
        clipPicker.isEnabled = !busy
        generateButton.isEnabled = !busy && contact != null && model.clipId != null
        previewButton.isEnabled = model.clipId != null
        cancelButton.isEnabled = busy
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
        stopAudio()
        model.status.value = "Loading preview…"
        previewTask = lifecycleScope.launch {
            var file: File? = null
            try {
                val cached = File.createTempFile("voice-preview-", ".audio", cacheDir)
                file = cached
                model.api.preview(voice, clip, cached)
                val audio = MediaPlayer()
                player = audio
                audio.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                // MediaPlayer duplicates this descriptor; the cache file can be deleted after setDataSource.
                FileInputStream(cached).use { audio.setDataSource(it.fd) }
                audio.setOnPreparedListener {
                    if (player === it) {
                        handler.removeCallbacksAndMessages(null)
                        it.start()
                        model.status.value = "Playing preview: ${clip.name}"
                    }
                }
                audio.setOnCompletionListener {
                    if (player === it) { stopAudio(); model.status.value = "Preview finished." }
                }
                audio.setOnErrorListener { failed, what, extra ->
                    if (player === failed) {
                        stopAudio()
                        model.status.value = "Cannot play preview (player error $what / $extra)."
                    }
                    true
                }
                handler.postDelayed({
                    if (player === audio) { stopAudio(); model.status.value = "Audio preview timed out." }
                }, 30_000)
                audio.prepareAsync()
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

    private fun stopAudio() {
        previewTask?.cancel()
        previewTask = null
        handler.removeCallbacksAndMessages(null)
        player?.release()
        player = null
        ringtone?.stop()
        ringtone = null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("permission", pendingPermission)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() { stopAudio(); super.onStop() }
}
