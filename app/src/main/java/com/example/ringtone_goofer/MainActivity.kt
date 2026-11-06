package com.example.ringtone_goofer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * Contacts manages local contact audio; Generate owns its saved recipient and voice draft.
 * History actions use the item snapshot, independently of either selection.
 */
class MainActivity : AppCompatActivity() {
    private lateinit var model: RingtoneViewModel
    private lateinit var audio: AudioPreview
    private lateinit var content: LinearLayout
    private lateinit var summary: TextView
    private lateinit var next: Button
    private lateinit var status: TextView
    private lateinit var signInButton: Button
    private lateinit var navigation: BottomNavigationView
    private var section = R.id.section_contacts
    private var previewFile: File? = null
    private enum class ContactPlayback { RINGTONE, RECORDING }
    private var contactPlayback: ContactPlayback? = null
    private var contactQuery = ""
    private var voiceQuery = ""
    private data class VoiceRow(val title: String, val voice: Voice? = null)
    private var previewTask: Job? = null
    private var refreshSection: (() -> Unit)? = null
    private var pendingPermission: String? = null
    private var pendingAction: String? = null
    private var pendingItem: String? = null
    private val auth get() = FirebaseAuth.getInstance()
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permission = pendingPermission
        val action = pendingAction
        val id = pendingItem
        pendingPermission = null; pendingAction = null; pendingItem = null
        if (granted) perform(action, id)
        else {
            model.status.value = "Access was denied. Your draft and ringtones are still saved."
            if (permission != null && !shouldShowRequestPermissionRationale(permission)) {
                AlertDialog.Builder(this).setMessage("You can enable access in app settings when you are ready.")
                    .setPositiveButton("Settings") { _, _ ->
                        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
                    }.setNegativeButton("Later", null).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[RingtoneViewModel::class.java]
        audio = AudioPreview(this,
            message = { model.status.value = it },
            playbackChanged = { active ->
                if (!active) contactPlayback = null
                if (section == R.id.section_contacts) refreshSection?.invoke()
            })
        val savedSection = savedInstanceState?.getInt("section")
            ?: getPreferences(MODE_PRIVATE).getInt("section", R.id.section_contacts)
        section = savedSection.takeIf { it in setOf(R.id.section_contacts, R.id.section_voices, R.id.section_ringtones) }
            ?: R.id.section_contacts
        contactQuery = savedInstanceState?.getString("contactQuery").orEmpty()
        voiceQuery = savedInstanceState?.getString("voiceQuery").orEmpty()
        pendingPermission = savedInstanceState?.getString("permission")
        pendingAction = savedInstanceState?.getString("action")
        pendingItem = savedInstanceState?.getString("item")
        val root = column()
        root.setBackgroundColor(MaterialColors.getColor(root, com.google.android.material.R.attr.colorSurface))
        val heading = column(16)
        heading.addView(text("Ringtone Goofer", 26))
        signInButton = button("", ::signIn)
        heading.addView(signInButton)

        root.addView(heading)
        content = column()
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        navigation = BottomNavigationView(this).apply {
            menu.add(0, R.id.section_contacts, 0, "Contacts").setIcon(android.R.drawable.ic_menu_call)
            menu.add(0, R.id.section_voices, 1, "Generate").setIcon(android.R.drawable.ic_btn_speak_now)
            menu.add(0, R.id.section_ringtones, 2, "History").setIcon(android.R.drawable.ic_menu_save)
            setOnItemSelectedListener { item -> showSection(item.itemId); true }
            selectedItemId = section
        }
        root.addView(navigation)
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }
        setContentView(root)
        showSection(section)
        model.contactSelection.observe(this) { if (section == R.id.section_contacts) refreshSection?.invoke() }
        model.draft.observe(this) { updateSummary(); refreshSection?.invoke() }
        model.voices.observe(this) { updateSummary(); if (section == R.id.section_voices) refreshSection?.invoke() }
        model.contacts.observe(this) { updateSummary(); if (section == R.id.section_contacts) refreshSection?.invoke() }
        model.items.observe(this) { if (section == R.id.section_ringtones) refreshSection?.invoke() }
        model.loaded.observe(this) { updateSummary(); updateAccount() }
        model.saving.observe(this) { updateSummary() }
        model.recording.observe(this) { updateSummary(); if (section == R.id.section_contacts) refreshSection?.invoke() }
        model.stoppingRecording.observe(this) { if (section == R.id.section_contacts) refreshSection?.invoke() }
        model.status.observe(this) { status.text = it }
        updateAccount()
    }

    private fun updateAccount() {
        val user = auth.currentUser
        signInButton.text = if (user == null) "Sign in with Google" else user.email ?: "Google account connected"
        signInButton.isEnabled = true
    }

    private fun updateSummary() {
        if (section != R.id.section_voices) return
        val draft = model.draft.value ?: RingtoneDraft()
        val voice = model.voices.value.orEmpty().firstOrNull { it.id == draft.voiceId }
        val clip = voice?.clips?.firstOrNull { it.id == draft.clipId }
        summary.text = when {
            draft.contactUri.isBlank() -> "1 · Select a contact\nMake their next call sound personal."
            voice == null -> "2 · Choose a voice\nFor ${draft.contactName}"
            clip == null -> "2 · Choose a clip\n${voice.displayName} · ${voice.category}"
            else -> "3 · Ready to generate\n${draft.contactName} · ${voice.name}\n${clip.name.substringAfterLast('/')}"
        }
        next.text = when {
            draft.contactUri.isBlank() -> "Select contact"
            voice == null -> "Choose voice"
            clip == null -> "Choose clip"
            else -> "Generate & set ringtone"
        }
        next.isEnabled = model.loaded.value == true && model.saving.value != true && model.recording.value != true
    }

    private fun nextStep() {
        val draft = model.draft.value ?: return
        val voice = model.voices.value.orEmpty().firstOrNull { it.id == draft.voiceId }
        when {
            draft.contactUri.isBlank() -> chooseGenerationContact()
            voice == null -> navigation.selectedItemId = R.id.section_voices
            voice.clips.none { it.id == draft.clipId } -> showVoiceClips(voice)
            model.generationContact == null -> chooseGenerationContact()
            else -> requestGeneration()
        }
    }

    private fun requestGeneration(id: String? = null) {
        request(Manifest.permission.READ_CONTACTS, "generateRead", id)
    }

    private fun showSection(value: Int) {
        if (contactPlayback != null) stopAudio()
        section = value
        getPreferences(MODE_PRIVATE).edit().putInt("section", value).apply()
        refreshSection = null
        content.removeAllViews()
        when (value) {
            R.id.section_contacts -> contactsScreen()
            R.id.section_voices -> generateScreen()
            R.id.section_ringtones -> historyScreen()
        }
        updateSummary()
    }

    private fun contactsScreen() {
        val list = ListView(this).apply { divider = null }
        val header = column(16)
        header.addView(text("Contacts", 22))
        header.addView(text("Select a contact to manage their ringtone and voice recordings.", 14))
        statusHeader(header)
        header.addView(button("Load / refresh contacts") { request(Manifest.permission.READ_CONTACTS, "contacts") })
        val search = EditText(this).apply {
            hint = "Search contacts"
            setSingleLine()
            setText(contactQuery)
            contentDescription = "Search contacts"
        }
        header.addView(search)
        list.addHeaderView(header, null, false)
        var filtered = emptyList<AddressBookContact>()
        var expandedContact = if (model.recording.value == true) model.contactSelection.value else null
        val toggleContact: (AddressBookContact) -> Unit = { contact ->
            if (model.recording.value != true && model.stoppingRecording.value != true) {
                stopAudio()
                val uri = contact.lookupUri.toString()
                expandedContact = if (expandedContact == uri) null else uri
                model.selectContact(contact)
                refreshSection?.invoke()
            }
        }
        val rows = object : BaseAdapter() {
            override fun getCount() = filtered.size
            override fun getItem(position: Int) = filtered[position]
            override fun getItemId(position: Int) = filtered[position].lookupUri.toString().hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val contact = filtered[position]
                return column(16).apply {
                    val chosen = model.contactSelection.value == contact.lookupUri.toString()
                    val expanded = expandedContact == contact.lookupUri.toString() && chosen
                    addView(button((if (expanded) "▾  " else "▸  ") + contact.name.ifBlank { "Unnamed contact" }) {
                        toggleContact(contact)
                    }.apply {
                        ViewCompat.setStateDescription(this, if (expanded) "Expanded" else "Collapsed")
                    })
                    addView(text(contact.ringtoneName, 13))
                    if (expanded) {
                        val tools = button("Record voice sample") {
                            if (model.recording.value == true) model.stopRecording() else requestRecording()
                        }
                        val listenRingtone = button("Listen to current ringtone") {
                            if (contactPlayback == ContactPlayback.RINGTONE) {
                                stopAudio()
                            } else model.selectedContact?.let {
                                stopAudio()
                                audio.playContact(it)
                                if (audio.isActive) contactPlayback = ContactPlayback.RINGTONE
                                refreshSection?.invoke()
                            }
                        }
                        val listenRecording = button("Listen to saved recording") {
                            if (contactPlayback == ContactPlayback.RECORDING) stopAudio() else playRecording()
                        }
                        val upload = button("Upload saved recording") { model.uploadRecording() }
                        val recording = model.recording.value == true
                        tools.text = if (recording) "Stop and save recording" else "Record voice sample"
                        val playing = contactPlayback != null
                        listenRingtone.text = if (contactPlayback == ContactPlayback.RINGTONE) "Stop audio" else "Listen to current ringtone"
                        listenRecording.text = if (contactPlayback == ContactPlayback.RECORDING) "Stop audio" else "Listen to saved recording"
                        tools.isEnabled = !playing && model.stoppingRecording.value != true
                        listenRingtone.isEnabled = !recording && (contactPlayback == ContactPlayback.RINGTONE || !playing)
                        listenRecording.isEnabled = !recording && (contactPlayback == ContactPlayback.RECORDING || !playing)
                        upload.isEnabled = !recording && !playing && auth.currentUser != null
                        addView(tools)
                        addView(listenRingtone)
                        addView(listenRecording)
                        addView(upload)
                    }
                    if (chosen) setBackgroundColor(MaterialColors.layer(
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface),
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary), 0.12f))
                }
            }
        }
        list.adapter = rows
        refreshSection = {
            filtered = model.contacts.value.orEmpty().filter { it.name.contains(contactQuery, ignoreCase = true) }
            rows.notifyDataSetChanged()

        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                contactQuery = s.toString(); refreshSection?.invoke()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        list.setOnItemClickListener { _, _, position, _ ->
            filtered.getOrNull(position - list.headerViewsCount)?.let { contact ->
                toggleContact(contact)
            }
        }
        content.addView(list, LinearLayout.LayoutParams(-1, -1))
        refreshSection?.invoke()
    }

    private fun generateScreen() {
        val list = ListView(this).apply { divider = null }
        val header = column(16)
        header.addView(text("Generate", 22))
        header.addView(button("Choose / change recipient") { chooseGenerationContact() })
        workflowHeader(header)
        header.addView(text("Choose a voice", 20))
        header.addView(text("Browse Public voices or Your Uploads. Tap a person to choose and preview their clips.", 14))
        header.addView(button("Refresh voices") { model.loadVoices() })
        header.addView(button("Listen to selected voice") { previewVoice() })
        header.addView(button("Stop audio") { stopAudio() })
        val search = EditText(this).apply {
            hint = "Search voices"
            contentDescription = "Search voices"
            setSingleLine()
            setText(voiceQuery)
        }
        header.addView(search)
        val empty = text("", 14)
        header.addView(empty)
        list.addHeaderView(header, null, false)
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                voiceQuery = s.toString()
                refreshSection?.invoke()
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        var rows = emptyList<VoiceRow>()
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = rows[position].voice != null
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row = rows[position]
                val voice = row.voice
                return column(16).apply {
                    val chosen = voice != null && model.draft.value?.voiceId == voice.id
                    val title = text((if (chosen) "✓  " else "") + row.title, if (voice == null) 20 else 17)
                    ViewCompat.setAccessibilityHeading(title, voice == null)
                    addView(title)
                    if (voice != null) addView(text(
                        if (voice.clips.size == 1) "1 clip · Tap to choose"
                        else "${voice.clips.size} clips · Tap to choose", 14))
                    if (chosen) setBackgroundColor(MaterialColors.layer(
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface),
                        MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary), 0.12f))
                }
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            rows.getOrNull(position - list.headerViewsCount)?.voice?.let { voice ->
                stopAudio()
                model.selectVoice(voice)
                showVoiceClips(voice)
            }
        }
        refreshSection = {
            val voices = model.voices.value.orEmpty()
            val categories = listOf("Public", "Your Uploads") +
                voices.map { it.category }.distinct().filterNot { it in setOf("Public", "Your Uploads") }.sorted()
            rows = buildList {
                for (category in categories) {
                    val matches = voices.filter { it.category == category && it.displayName.contains(voiceQuery, ignoreCase = true) }
                        .sortedBy { it.displayName.lowercase() }
                    add(VoiceRow("$category (${matches.size})"))
                    matches.forEach { add(VoiceRow(it.displayName, it)) }
                }
            }
            empty.text = when {
                voices.isEmpty() -> "No voices loaded. Sign in and refresh, or upload a recording from Contacts."
                rows.none { it.voice != null } -> "No voices match your search."
                else -> "Choose a voice below to see its clips."
            }
            adapter.notifyDataSetChanged()
        }
        content.addView(list, LinearLayout.LayoutParams(-1, -1))
        refreshSection?.invoke()
    }

    private fun showVoiceClips(voice: Voice) {
        if (voice.clips.isEmpty()) {
            model.status.value = "No clips available for ${voice.displayName}."
            return
        }
        val selected = voice.clips.indexOfFirst { it.id == model.draft.value?.clipId }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${voice.displayName} · ${voice.category}")
            .setSingleChoiceItems(voice.clips.map { it.name.substringAfterLast('/') }.toTypedArray(), selected) { chooser, index ->
                stopAudio()
                model.selectVoice(voice, voice.clips[index])
                (chooser as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
            }
            .setPositiveButton("Done", null)
            .setNeutralButton("Preview selected clip", null)
            .create()
        dialog.setOnDismissListener { stopAudio() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = selected >= 0
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { previewVoice() }
        }
        dialog.show()
    }

    private fun historyScreen() {
        val list = ListView(this).apply { divider = null }
        val header = column(16)
        header.addView(text("History", 22))
        statusHeader(header)
        header.addView(text("Generation and downloads continue in the background. Tap an item to download, listen or assign.", 14))
        val empty = text("", 14)
        header.addView(empty)
        list.addHeaderView(header, null, false)
        var rows = emptyList<RingtoneItem>()
        val adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = rows[position].id.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val item = rows[position]
                return column(16).apply {
                    addView(text(item.contactName, 19))
                    addView(text(item.voiceName + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.created)), 13))
                    addView(text(item.label, 16))
                    if (item.detail.isNotBlank()) addView(text(item.detail, 13))
                    if (item.active) addView(ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true })
                    addView(text("Open details  ›", 14))
                }
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            rows.getOrNull(position - list.headerViewsCount)?.let(::showItem)
        }
        refreshSection = {
            rows = model.items.value.orEmpty()
            empty.text = if (rows.isEmpty()) "Your generated ringtones and uploads will appear here. Create a ringtone in Generate."
                else "${rows.size} saved items"
            adapter.notifyDataSetChanged()
        }
        content.addView(list, LinearLayout.LayoutParams(-1, -1))
        refreshSection?.invoke()
    }

    private fun showItem(initial: RingtoneItem) {
        val body = column(20)
        val scroll = android.widget.ScrollView(this).apply { addView(body) }
        val dialog = AlertDialog.Builder(this).setTitle(initial.contactName).setView(scroll)
            .setPositiveButton("Close", null).create()
        fun render(item: RingtoneItem) {
            body.removeAllViews()
            body.addView(text(item.voiceName, 18))
            if (item.clipName.isNotBlank()) body.addView(text(item.clipName.substringAfterLast('/'), 14))
            body.addView(text(item.label, 20))
            if (item.detail.isNotBlank()) body.addView(text(item.detail, 14))
            if (item.active) body.addView(ProgressBar(this))
            when (item.stage) {
                "ready" -> body.addView(button("Download ringtone") { model.download(item) })
                "downloaded", "assigned" -> {
                    body.addView(button("Listen") { playItem(item) })
                    body.addView(button("Stop audio") { stopAudio() })
                    body.addView(button("Assign to ${item.contactName}") { requestAssignment(item) })
                    body.addView(button("Choose assignment contact") { chooseAssignmentContact(item) })
                    body.addView(button("Download again") { stopAudio(); model.download(item) })
                }
                "failed" -> {
                    if (item.resumeStage.isNotBlank()) body.addView(button("Retry") {
                        if (item.resumeStage == "assigning") requestAssignment(item) else model.retry(item)
                    })
                    if (item.resumeStage == "assigning") {
                        body.addView(button("Choose assignment contact") { chooseAssignmentContact(item) })
                        body.addView(button("Listen") { playItem(item) })
                        body.addView(button("Download again") { stopAudio(); model.download(item) })
                    }
                    if (item.clipId.isNotBlank()) body.addView(button("Generate again") { requestGeneration(item.id); dialog.dismiss() })
                    body.addView(text("Your other ringtones and selections are saved.", 14))
                }
                "uploaded" -> body.addView(button("Refresh voices") { model.loadVoices(); dialog.dismiss(); navigation.selectedItemId = R.id.section_voices })
            }
            body.addView(text("Job ${item.id}" + if (item.route.isBlank()) "" else "\nSubmitted via ${item.route}", 12).apply { setTextIsSelectable(true) })
        }
        val observer = Observer<List<RingtoneItem>> { items -> items.firstOrNull { it.id == initial.id }?.let(::render) }
        model.items.observe(this, observer)
        dialog.setOnDismissListener { model.items.removeObserver(observer) }
        render(initial)
        dialog.show()
    }

    private fun requestAssignment(item: RingtoneItem) {
        if (item.contactUri.isBlank()) { chooseAssignmentContact(item); return }
        request(Manifest.permission.READ_CONTACTS, "assignRead", item.id)
    }

    private fun chooseAssignmentContact(item: RingtoneItem) {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            request(Manifest.permission.READ_CONTACTS, "chooseContact", item.id); return
        }
        model.loadContacts()
        val contacts = model.contacts.value.orEmpty()
        if (contacts.isEmpty()) { model.status.value = "Contacts are loading. Try Choose assignment contact again."; return }
        AlertDialog.Builder(this).setTitle("Assign this ringtone to…")
            .setItems(contacts.map { it.name }.toTypedArray()) { _, index -> model.setAssignmentContact(item, contacts[index]) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun playItem(item: RingtoneItem) {
        if (model.recording.value == true) return
        stopAudio()
        previewTask = lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { RingtoneLibrary.get(this@MainActivity).audioFile(this@MainActivity, item) }
            if (file.isFile) audio.play(Uri.fromFile(file), "ringtone for ${item.contactName}")
            else model.status.value = "Local audio is missing. Tap Download again."
        }
    }

    private fun previewVoice() {
        if (model.recording.value == true) return
        val voice = model.voices.value.orEmpty().firstOrNull { it.id == model.draft.value?.voiceId } ?: return
        val clip = voice.clips.firstOrNull { it.id == model.draft.value?.clipId } ?: return
        stopAudio()
        model.status.value = "Loading voice sample…"
        previewTask = lifecycleScope.launch {
            var file: File? = null
            try {
                val target = withContext(Dispatchers.IO) { File.createTempFile("voice-preview-", ".audio", cacheDir) }
                file = target
                previewFile = target
                model.api.preview(voice, clip, target)
                audio.play(Uri.fromFile(target), clip.name.substringAfterLast('/'))
                // Keep this small cache file until playback has stopped.
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                model.status.value = "Cannot preview voice: ${e.message}"
                file?.delete()
            }
        }
    }

    private fun requestRecording() {
        val contact = model.selectedContact ?: return
        stopAudio()
        val file = model.recordingFile(contact)
        if (file.exists()) AlertDialog.Builder(this)
            .setMessage("Replace the saved voice recording for ${contact.name}?")
            .setPositiveButton("Record") { _, _ -> request(Manifest.permission.RECORD_AUDIO, "record") }
            .setNegativeButton("Cancel", null).show()
        else request(Manifest.permission.RECORD_AUDIO, "record")
    }

    private fun playRecording() {
        if (model.recording.value == true) return
        val contact = model.selectedContact ?: return
        stopAudio()
        val file = model.recordingFile(contact)
        if (file.isFile) {
            audio.play(Uri.fromFile(file), "recording for ${contact.name}")
            if (audio.isActive) contactPlayback = ContactPlayback.RECORDING
            refreshSection?.invoke()
        } else model.status.value = "Record a voice sample for ${contact.name} first."
    }

    private fun chooseGenerationContact() {
        if (!hasPermission(Manifest.permission.READ_CONTACTS)) {
            request(Manifest.permission.READ_CONTACTS, "generateContact")
            return
        }
        if (model.loaded.value != true) {
            model.status.value = "Sign in before choosing a generation recipient."
            return
        }
        lifecycleScope.launch {
            try {
                val contacts = withContext(Dispatchers.IO) { ContactRingtoneStore(this@MainActivity).contacts() }
                model.contacts.value = contacts
                if (section != R.id.section_voices) return@launch
                if (contacts.isEmpty()) {
                    model.status.value = "No contacts found."
                    return@launch
                }
                AlertDialog.Builder(this@MainActivity).setTitle("Generate a ringtone for…")
                    .setItems(contacts.map { it.name }.toTypedArray()) { _, index ->
                        model.selectGenerationContact(contacts[index])
                    }.setNegativeButton("Cancel", null).show()
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                model.status.value = "Cannot load contacts: ${e.message}"
            }
        }
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun request(permission: String, action: String, id: String? = null) {
        if (hasPermission(permission)) { perform(action, id); return }
        val launch = {
            pendingPermission = permission; pendingAction = action; pendingItem = id
            permissionLauncher.launch(permission)
        }
        if (shouldShowRequestPermissionRationale(permission)) {
            val reason = when (permission) {
                Manifest.permission.READ_CONTACTS -> "Contacts access lets you choose a person and resolve their saved contact."
                Manifest.permission.WRITE_CONTACTS -> "Allow contact updates to set this person's ringtone."
                Manifest.permission.RECORD_AUDIO -> "Microphone access is used only while recording a voice sample."
                else -> "Storage access is needed on this Android version to publish the ringtone."
            }
            AlertDialog.Builder(this).setMessage(reason).setPositiveButton("Continue") { _, _ -> launch() }
                .setNegativeButton("Cancel", null).show()
        } else launch()
    }

    private fun perform(action: String?, id: String?, restored: RingtoneItem? = null) {
        val item = restored ?: model.items.value.orEmpty().firstOrNull { it.id == id }
        if (id != null && item == null) {
            // Permission results can arrive before the library observer has restored its rows.
            val uid = auth.currentUser?.uid ?: return
            lifecycleScope.launch {
                val saved = withContext(Dispatchers.IO) { RingtoneLibrary.get(this@MainActivity).get(uid, id) }
                if (saved != null && auth.currentUser?.uid == uid) perform(action, id, saved)
                else model.status.value = "Open this ringtone from its original Google account to continue."
            }
            return
        }
        when (action) {
            "contacts" -> model.loadContacts()
            "generateContact" -> { model.loadContacts(); chooseGenerationContact() }
            "generateRead" -> request(Manifest.permission.WRITE_CONTACTS, "generateWrite", id)
            "generateWrite" -> if (Build.VERSION.SDK_INT < 29) {
                request(Manifest.permission.WRITE_EXTERNAL_STORAGE, "generate", id)
            } else perform("generate", id, item)
            "generate" -> {
                stopAudio()
                if (id == null) model.generate() else item?.let { model.generateAgain(it) }
                navigation.selectedItemId = R.id.section_ringtones
            }
            "record" -> model.startRecording()
            "chooseContact" -> item?.let(::chooseAssignmentContact)
            "assignRead" -> request(Manifest.permission.WRITE_CONTACTS, "assignWrite", id)
            "assignWrite" -> if (Build.VERSION.SDK_INT < 29) request(Manifest.permission.WRITE_EXTERNAL_STORAGE, "assign", id)
                else item?.let { model.assign(it) }
            "assign" -> item?.let { model.assign(it) }
        }
    }

    private fun signIn() {
        if (auth.currentUser != null) {
            AlertDialog.Builder(this).setTitle("Google account").setMessage(auth.currentUser?.email)
                .setPositiveButton("Sign out") { _, _ ->
                    stopAudio()
                    auth.signOut()
                    updateAccount()
                    lifecycleScope.launch {
                        runCatching { CredentialManager.create(this@MainActivity).clearCredentialState(ClearCredentialStateRequest()) }
                    }
                }.setNegativeButton("Close", null).show()
            return
        }
        signInButton.isEnabled = false
        lifecycleScope.launch {
            try {
                val option = GetSignInWithGoogleOption.Builder(getString(R.string.default_web_client_id)).build()
                val credential = CredentialManager.create(this@MainActivity).getCredential(this@MainActivity,
                    GetCredentialRequest.Builder().addCredentialOption(option).build()).credential
                check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                val token = GoogleIdTokenCredential.createFrom(credential.data).idToken
                auth.signInWithCredential(GoogleAuthProvider.getCredential(token, null)).await()
            } catch (_: NoCredentialException) {
                model.status.value = "No Google account is available. Add one in device settings, then sign in again."
            } catch (_: GetCredentialCancellationException) {
                // Account picker dismissed.
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                model.status.value = "Sign-in failed: ${e.message}"
            } finally { updateAccount() }
        }
    }

    private fun stopAudio() {
        previewTask?.cancel(); previewTask = null
        audio.stop()
        previewFile?.delete(); previewFile = null
    }

    /** The draft scrolls with each section, leaving room on small and landscape windows. */
    private fun workflowHeader(header: LinearLayout) {
        val card = MaterialCardView(this).apply {
            radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
        }
        val body = column(12)
        summary = text("", 16)
        next = button("", ::nextStep)
        body.addView(summary); body.addView(next)
        card.addView(body)
        header.addView(card)
        statusHeader(header)
    }

    private fun statusHeader(header: LinearLayout) {
        status = text(model.status.value.orEmpty(), 13).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(status)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun column(padding: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    private fun text(value: String, size: Int) = TextView(this).apply {
        text = value; textSize = size.toFloat(); setPadding(0, dp(3), 0, dp(3))
    }
    private fun button(value: String, action: () -> Unit): Button = MaterialButton(this).apply {
        text = value; isAllCaps = false
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    override fun onResume() {
        super.onResume()
        if (::model.isInitialized && hasPermission(Manifest.permission.READ_CONTACTS)) model.loadContacts()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("section", section); outState.putString("contactQuery", contactQuery)
        outState.putString("voiceQuery", voiceQuery)
        outState.putString("permission", pendingPermission); outState.putString("action", pendingAction)
        outState.putString("item", pendingItem)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        stopAudio()
        model.stopRecording()
        super.onStop()
    }
}
