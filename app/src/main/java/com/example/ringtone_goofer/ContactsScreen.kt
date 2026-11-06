package com.example.ringtone_goofer

import android.net.Uri
import android.view.View
import android.widget.EditText
import android.widget.ListView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import androidx.core.view.ViewCompat
import com.google.android.material.color.MaterialColors
import com.google.firebase.auth.FirebaseAuth

/** Browse a contact, listen to their ringtone, or record and upload their voice. */
internal class ContactsScreen(activity: MainActivity, var contactQuery: String = "") : TabScreen(activity) {
    private enum class ContactPlayback { RINGTONE, RECORDING }
    private var contactPlayback: ContactPlayback? = null

    fun playbackChanged(active: Boolean) {
        if (!active) contactPlayback = null
        if (activity.currentScreen === this) refresh()
    }

    override fun create(): View {
        val list = ListView(this).apply { divider = null }
        val header = column(16)
        header.addView(text("Contacts", 22))
        header.addView(text("Select a contact to manage their ringtone and voice recordings.", 14))
        activity.statusHeader(header)
        header.addView(secondaryButton("Load / refresh contacts") { activity.runAction(ScreenAction.CONTACTS) })
        val search = EditText(this).apply {
            hint = "Search contacts"
            setSingleLine()
            setText(contactQuery)
            contentDescription = "Search contacts"
        }
        header.addView(search)
        list.addHeaderView(header, null, false)
        var expandedContact = if (model.recording.value == true) model.contactSelection.value else null
        val toggleContact: (AddressBookContact) -> Unit = { contact ->
            if (model.recording.value != true && model.stoppingRecording.value != true) {
                activity.stopAudio()
                val uri = contact.lookupUri.toString()
                expandedContact = if (expandedContact == uri) null else uri
                model.selectContact(contact)
                refresh()
            }
        }
        val rows = RowsAdapter<AddressBookContact> { contact ->
            column(16).apply contactRow@{
                val chosen = model.contactSelection.value == contact.lookupUri.toString()
                val expanded = expandedContact == contact.lookupUri.toString() && chosen
                addView(column().apply {
                    minimumHeight = dp(64)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val label = text(contact.name.ifBlank { "Unnamed contact" }, 18).apply {
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setCompoundDrawablesRelativeWithIntrinsicBounds(
                            0, 0, if (expanded) android.R.drawable.arrow_up_float else android.R.drawable.arrow_down_float, 0)
                        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                            this, android.content.res.ColorStateList.valueOf(
                                MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary)))
                    }
                    addView(label)
                    addView(text(contact.ringtoneName, 13).apply {
                        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
                        alpha = 0.7f
                    })
                    val attributes = obtainStyledAttributes(intArrayOf(android.R.attr.selectableItemBackground))
                    setBackgroundResource(attributes.getResourceId(0, 0))
                    attributes.recycle()
                    isFocusable = true
                    setOnClickListener { toggleContact(contact) }
                    ViewCompat.setStateDescription(this, if (expanded) "Expanded" else "Collapsed")
                })
                if (expanded) {
                    val tools = button("Record voice sample") {
                        if (model.recording.value == true) model.stopRecording() else requestRecording()
                    }
                    val listenRingtone = secondaryButton("Listen to current ringtone") {
                        if (contactPlayback == ContactPlayback.RINGTONE) {
                            activity.stopAudio()
                        } else model.selectedContact?.let {
                            activity.stopAudio()
                            audio.playContact(it)
                            if (audio.isActive) contactPlayback = ContactPlayback.RINGTONE
                            refresh()
                        }
                    }
                    val listenRecording = secondaryButton("Listen to saved recording") {
                        if (contactPlayback == ContactPlayback.RECORDING) activity.stopAudio() else playRecording()
                    }
                    val upload = secondaryButton("Upload saved recording") { model.uploadRecording() }
                    val recording = model.recording.value == true
                    tools.text = if (recording) "Stop and save recording" else "Record voice sample"
                    val playing = contactPlayback != null
                    listenRingtone.text = if (contactPlayback == ContactPlayback.RINGTONE) "Stop audio" else "Listen to current ringtone"
                    listenRecording.text = if (contactPlayback == ContactPlayback.RECORDING) "Stop audio" else "Listen to saved recording"
                    tools.isEnabled = !playing && model.stoppingRecording.value != true
                    listenRingtone.isEnabled = !recording && (contactPlayback == ContactPlayback.RINGTONE || !playing)
                    listenRecording.isEnabled = !recording && (contactPlayback == ContactPlayback.RECORDING || !playing)
                    upload.isEnabled = !recording && !playing && FirebaseAuth.getInstance().currentUser != null
                    addView(tools)
                    addView(listenRingtone)
                    addView(listenRecording)
                    addView(upload)
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = dp(20).toFloat()
                    setColor(MaterialColors.layer(
                        MaterialColors.getColor(this@contactRow, com.google.android.material.R.attr.colorSurface),
                        MaterialColors.getColor(this@contactRow, com.google.android.material.R.attr.colorPrimary),
                        if (chosen) 0.09f else 0f))
                }
            }
        }
        list.adapter = rows
        refresh = {
            rows.items = model.contacts.value.orEmpty().filter { it.name.contains(contactQuery, ignoreCase = true) }
        }
        search.doOnTextChanged { value, _, _, _ ->
            contactQuery = value.toString()
            refresh()
        }
        list.setOnItemClickListener { _, _, position, _ ->
            rows.items.getOrNull(position - list.headerViewsCount)?.let { contact ->
                toggleContact(contact)
            }
        }
        refresh()
        return list
    }

    private fun requestRecording() {
        val contact = model.selectedContact ?: return
        activity.stopAudio()
        val file = model.recordingFile(contact)
        if (file.exists()) AlertDialog.Builder(this)
            .setMessage("Replace the saved voice recording for ${contact.name}?")
            .setPositiveButton("Record") { _, _ -> activity.runAction(ScreenAction.RECORD) }
            .setNegativeButton("Cancel", null).show()
        else activity.runAction(ScreenAction.RECORD)
    }

    private fun playRecording() {
        if (model.recording.value == true) return
        val contact = model.selectedContact ?: return
        activity.stopAudio()
        val file = model.recordingFile(contact)
        if (file.isFile) {
            audio.play(Uri.fromFile(file), "recording for ${contact.name}")
            if (audio.isActive) contactPlayback = ContactPlayback.RECORDING
            refresh()
        } else model.status.value = "Record a voice sample for ${contact.name} first."
    }
}
