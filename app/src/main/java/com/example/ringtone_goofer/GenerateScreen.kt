package com.example.ringtone_goofer

import android.Manifest
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doOnTextChanged
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Choose a recipient and reference clip, preview it, and submit generation. */
internal class GenerateScreen(activity: MainActivity, var voiceQuery: String = "") : TabScreen(activity) {
    private lateinit var recipientButton: Button
    private lateinit var voiceButton: Button
    private lateinit var languageButton: Button
    private lateinit var next: Button
    private data class VoiceRow(val title: String, val voice: Voice? = null)

    private fun updateSummary() {
        val draft = model.draft.value ?: RingtoneDraft()
        val voice = model.availableVoices.firstOrNull { it.id == draft.voiceId }
        val clip = voice?.clips?.firstOrNull { it.id == draft.clipId }
        recipientButton.text = if (draft.contactUri.isBlank()) "Choose / change recipient"
            else "✓  Recipient: ${draft.contactName}\nTap to change"
        voiceButton.text = when {
            voice == null -> "Choose a voice"
            clip == null -> "Voice: ${voice.displayName}\nChoose a clip"
            else -> "✓  Voice: ${voice.displayName}\n${clip.name.substringAfterLast('/')} · Tap to change"
        }
        languageButton.text = "Language: ${languageName(draft.language)}"
        languageButton.isEnabled = model.loaded.value == true
        next.isEnabled = model.loaded.value == true && model.saving.value != true &&
            model.recording.value != true && draft.contactUri.isNotBlank() && clip != null
    }

    private fun nextStep() {
        val draft = model.draft.value ?: return
        val voice = model.availableVoices.firstOrNull { it.id == draft.voiceId }
        when {
            draft.contactUri.isBlank() -> chooseGenerationContact()
            voice == null -> chooseVoice()
            voice.clips.none { it.id == draft.clipId } -> showVoiceClips(voice)
            model.generationContact == null -> chooseGenerationContact()
            else -> activity.requestGeneration()
        }
    }

    override fun create(): View {
        val body = column(16)
        val error = voiceRefreshBanner()
        body.addView(error)
        body.addView(text("Generate", 22))
        activity.statusHeader(body)
        recipientButton = secondaryButton("Choose / change recipient") { chooseGenerationContact() }
        voiceButton = secondaryButton("Choose a voice") { chooseVoice() }
        body.addView(recipientButton)
        body.addView(voiceButton)
        languageButton = secondaryButton("Language: Automatic") { chooseLanguage() }
        body.addView(languageButton)
        next = button("Generate & set ringtone") { nextStep() }
        body.addView(next)
        refresh = { updateVoiceRefreshBanner(error); updateSummary() }
        refresh()
        return android.widget.ScrollView(this).apply { addView(body) }
    }

    private fun voiceRefreshBanner() = text("", 14).apply {
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorError))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    private fun updateVoiceRefreshBanner(banner: TextView) {
        val message = model.voiceRefreshError.value
        banner.text = message?.let { "✕  $it" }.orEmpty()
        banner.visibility = if (message == null) View.GONE else View.VISIBLE
    }

    private fun languageName(code: String): String = if (code == "auto") "Automatic (voice language)"
        else java.util.Locale.forLanguageTag(code).getDisplayLanguage(java.util.Locale.ENGLISH)

    private fun chooseLanguage() {
        val list = ListView(this)
        val header = column(16)
        val search = EditText(this).apply {
            hint = "Search languages"
            contentDescription = "Search languages"
            setSingleLine()
        }
        header.addView(search)
        val suggestions = listOf("da", "en", "zh")
        val codes = listOf("auto") + suggestions + java.util.Locale.getISOLanguages()
            .filter { it !in suggestions && languageName(it).isNotBlank() }.distinct()
            .sortedBy { languageName(it) }
        val adapter = RowsAdapter<String> { code ->
            text((if (code == model.draft.value?.language) "✓  " else "") + languageName(code), 17)
                .apply { setPadding(24, 24, 24, 24) }
        }
        list.adapter = adapter
        val empty = text("No languages match your search.", 14)
        header.addView(empty)
        val body = column()
        body.addView(header)
        body.addView(list, android.widget.LinearLayout.LayoutParams(-1, 0, 1f))
        fun filter(query: String) {
            adapter.items = codes.filter {
                query.isBlank() || languageName(it).contains(query, ignoreCase = true) ||
                    it.equals(query.trim(), ignoreCase = true)
            }
            empty.visibility = if (adapter.items.isEmpty()) View.VISIBLE else View.GONE
        }
        search.doOnTextChanged { value, _, _, _ -> filter(value.toString()) }
        val dialog = AlertDialog.Builder(this).setTitle("Choose a language")
            .setView(body).setNegativeButton("Cancel", null).create()
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.items.getOrNull(position)?.let {
                model.selectLanguage(it)
                dialog.dismiss()
            }
        }
        filter("")
        dialog.show()
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.8f).toInt())
    }

    private fun chooseVoice() {
        val list = ListView(this).apply { divider = null }
        val header = column(16)
        val error = voiceRefreshBanner()
        header.addView(error)
        header.addView(text("Choose a Public voice or an upload matching your recipient. Tap a person to choose and preview their clips.", 14))
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
        var refreshPicker: () -> Unit = {}
        search.doOnTextChanged { value, _, _, _ ->
            voiceQuery = value.toString()
            refreshPicker()
        }
        val adapter = RowsAdapter<VoiceRow>(enabled = { it.voice != null }) { row ->
            val voice = row.voice
            column(16).apply {
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
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            adapter.items.getOrNull(position - list.headerViewsCount)?.voice?.let { voice ->
                activity.stopAudio()
                model.selectVoice(voice)
                showVoiceClips(voice)
            }
        }
        refreshPicker = {
            updateVoiceRefreshBanner(error)
            val voices = model.availableVoices
            adapter.items = buildList {
                for (category in listOf("Your Uploads", "Public")) {
                    val matches = voices.filter { it.category == category && it.displayName.contains(voiceQuery, ignoreCase = true) }
                        .sortedBy { it.displayName.lowercase() }
                    add(VoiceRow("$category (${matches.size})"))
                    matches.forEach { add(VoiceRow(it.displayName, it)) }
                }
            }
            empty.text = when {
                model.refreshingVoices.value == true -> "Refreshing voices…"
                voices.isEmpty() -> "No voices loaded. Sign in and refresh, or upload a recording from Contacts."
                adapter.items.none { it.voice != null } -> "No voices match your search."
                else -> "Choose a voice below to see its clips."
            }
        }
        val dialog = AlertDialog.Builder(this).setTitle("Choose a voice")
            .setView(list).setPositiveButton("Done", null).create()
        dialog.observeUntilDismissed(activity, model.voices, model.draft,
            model.voiceRefreshError, model.refreshingVoices, onDismiss = { activity.stopAudio() }) { refreshPicker() }
        dialog.show()
        dialog.window?.setLayout(-1, (resources.displayMetrics.heightPixels * 0.8f).toInt())
        refreshPicker()
        model.loadVoices()
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
                activity.stopAudio()
                model.selectVoice(voice, voice.clips[index])
                (chooser as AlertDialog).getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = true
            }
            .setPositiveButton("Done", null)
            .setNeutralButton("Preview selected clip", null)
            .create()
        dialog.setOnDismissListener { activity.stopAudio() }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).isEnabled = selected >= 0
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { previewVoice() }
        }
        dialog.show()
    }

    fun chooseGenerationContact() {
        if (!activity.hasPermission(Manifest.permission.READ_CONTACTS)) {
            activity.runAction(ScreenAction.RECIPIENT)
            return
        }
        if (model.loaded.value != true) {
            model.status.value = "Sign in before choosing a generation recipient."
            return
        }
        chooseContact("Generate a ringtone for…", model::selectGenerationContact)
    }

    private fun previewVoice() {
        if (model.recording.value == true) return
        val voice = model.availableVoices.firstOrNull { it.id == model.draft.value?.voiceId } ?: return
        val clip = voice.clips.firstOrNull { it.id == model.draft.value?.clipId } ?: return
        activity.stopAudio()
        model.status.value = "Loading voice sample…"
        activity.previewTask = activity.lifecycleScope.launch {
            var file: File? = null
            try {
                val target = withContext(Dispatchers.IO) { File.createTempFile("voice-preview-", ".audio", cacheDir) }
                file = target
                activity.previewFile = target
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
}
