package com.example.ringtone_goofer

import android.Manifest
import android.net.Uri
import android.view.View
import android.widget.ListView
import android.widget.ProgressBar
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/** Inspect saved generation/upload results, download, listen, retry, or assign. */
internal class HistoryScreen(activity: MainActivity) : TabScreen(activity) {
    override fun create(): View {
        val list = ListView(this).apply { divider = null }
        val header = column(16)
        header.addView(text("History", 22))
        activity.statusHeader(header)
        val enableCalls = button("Enable incoming call history") { activity.runAction(ScreenAction.CALL_HISTORY) }
        header.addView(enableCalls)
        val calls = column()
        header.addView(calls)
        header.addView(text("Generation and downloads continue in the background. Tap an item to download, listen or assign.", 14))
        val empty = text("", 14)
        header.addView(empty)
        list.addHeaderView(header, null, false)
        val adapter = RowsAdapter<RingtoneItem> { item ->
            column(16).apply {
                addView(text(item.contactName, 19))
                addView(text(item.voiceName + " · " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(item.created)), 13))
                addView(text(item.label, 16))
                if (item.detail.isNotBlank()) addView(text(item.detail, 13))
                if (item.active) addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true })
                addView(text("Open details  ›", 14))
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.items.getOrNull(position - list.headerViewsCount)?.let(::showItem)
        }
        refresh = {
            enableCalls.visibility = if (activity.hasPermission(Manifest.permission.READ_PHONE_STATE) &&
                activity.hasPermission(Manifest.permission.READ_CALL_LOG)) View.GONE else View.VISIBLE
            calls.removeAllViews()
            model.callHistory.value.orEmpty().forEach { entry ->
                calls.addView(text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    .format(Date(entry.received)), 13))
                calls.addView(text(entry.message, 16))
            }
            val rows = model.items.value.orEmpty()
            adapter.items = rows
            empty.text = if (rows.isEmpty()) "Your generated ringtones and uploads will appear here. Create a ringtone in Generate."
                else "${rows.size} saved items"
        }
        refresh()
        return list
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
            item.generationStatus?.let { generation ->
                body.addView(text("Generation · ${generation.status}", 16))
                if (generation.detail.isNotBlank() && generation.detail != item.detail) {
                    body.addView(text(generation.detail, 14))
                }
                if (generation.metadata.isNotBlank()) body.addView(text(generation.metadata, 14).apply {
                    setTextIsSelectable(true)
                })
            }
            if (item.active) body.addView(ProgressBar(this))
            when (item.stage) {
                "ready" -> body.addView(button("Download ringtone") { model.download(item) })
                "downloaded", "assigned" -> {
                    body.addView(button("Listen") { playItem(item) })
                    body.addView(button("Stop audio") { activity.stopAudio() })
                    body.addView(button("Assign to ${item.contactName}") { requestAssignment(item) })
                    body.addView(button("Choose assignment contact") { chooseAssignmentContact(item) })
                    body.addView(button("Download again") { activity.stopAudio(); model.download(item) })
                }
                "failed" -> {
                    if (item.resumeStage.isNotBlank()) body.addView(button("Retry") {
                        if (item.resumeStage == "assigning") requestAssignment(item) else model.retry(item)
                    })
                    if (item.resumeStage == "assigning") {
                        body.addView(button("Choose assignment contact") { chooseAssignmentContact(item) })
                        body.addView(button("Listen") { playItem(item) })
                        body.addView(button("Download again") { activity.stopAudio(); model.download(item) })
                    }
                    if (item.clipId.isNotBlank()) body.addView(button("Generate again") { activity.requestGeneration(item.id); dialog.dismiss() })
                    body.addView(text("Your other ringtones and selections are saved.", 14))
                }
                "uploaded" -> body.addView(button("Refresh voices") { model.loadVoices(); dialog.dismiss(); activity.navigate(R.id.section_voices) })
            }
            body.addView(text("Job ${item.id}" + if (item.route.isBlank()) "" else "\nSubmitted via ${item.route}", 12).apply { setTextIsSelectable(true) })
        }
        dialog.observeUntilDismissed(activity, model.items) {
            render(model.items.value.orEmpty().firstOrNull { it.id == initial.id } ?: initial)
        }
        dialog.show()
    }

    private fun requestAssignment(item: RingtoneItem) {
        if (item.contactUri.isBlank()) { chooseAssignmentContact(item); return }
        activity.runAction(ScreenAction.ASSIGN, item.id)
    }

    fun chooseAssignmentContact(item: RingtoneItem) {
        if (!activity.hasPermission(Manifest.permission.READ_CONTACTS)) {
            activity.runAction(ScreenAction.ASSIGNMENT_CONTACT, item.id); return
        }
        chooseContact("Assign this ringtone to…") { model.setAssignmentContact(item, it) }
    }

    private fun playItem(item: RingtoneItem) {
        if (model.recording.value == true) return
        activity.stopAudio()
        activity.previewTask = activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) { RingtoneLibrary.get(activity).audioFile(activity, item) }
            if (file.isFile) audio.play(Uri.fromFile(file), "ringtone for ${item.contactName}")
            else model.status.value = "Local audio is missing. Tap Download again."
        }
    }
}
