package com.example.ringtone_goofer

import android.Manifest
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.LiveData
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import com.google.android.material.R.attr as MaterialAttr

private const val HISTORY_PREVIEW_LIMIT = 3

/** Inspect saved generation/upload results, download, listen, retry, or assign. */
internal class HistoryScreen(activity: MainActivity) : TabScreen(activity) {
    override fun create(): View {
        val body = column(16).apply {
            addView(text("History", 26))
            addView(text("Recent calls and ringtone activity, at a glance.", 14))
            activity.statusHeader(this)
        }
        val sections = column()
        body.addView(sections)
        refresh = {
            val calls = model.callHistory.value.orEmpty()
            val items = model.items.value.orEmpty()
            sections.removeAllViews()
            sections.addView(previewSection(
                "Incoming calls", "What happened after each incoming call.",
                if (calls.isEmpty()) "No incoming calls yet."
                else "${calls.size} calls · Latest ${minOf(HISTORY_PREVIEW_LIMIT, calls.size)} shown",
                calls.take(HISTORY_PREVIEW_LIMIT).map(::callRow),
                "View all calls (${calls.size})", ::showAllCalls,
                if (canGenerateFromCalls()) null else secondaryButton("Enable automatic call ringtones") {
                    activity.runAction(ScreenAction.CALL_HISTORY)
                }
            ))
            sections.addView(previewSection(
                "Ringtones & uploads", "Generation, downloads and voice recordings.",
                if (items.isEmpty()) "No activity yet. Create your first ringtone in Generate."
                else "${items.size} saved · ${items.count { it.active }} in progress · ${items.count { it.stage == "failed" }} need attention",
                items.take(HISTORY_PREVIEW_LIMIT).map { item ->
                    itemRow(item, compact = true).apply {
                        isFocusable = true
                        setOnClickListener { showItem(item) }
                    }
                },
                "View all activity (${items.size})", ::showAllItems
            ))
        }
        refresh()
        return ScrollView(this).apply { addView(body) }
    }

    private fun canGenerateFromCalls() = listOfNotNull(
        Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
        Manifest.permission.WRITE_EXTERNAL_STORAGE.takeIf { android.os.Build.VERSION.SDK_INT < 29 }
    ).all(activity::hasPermission)

    private fun previewSection(
        title: String, subtitle: String, summary: String, rows: List<View>,
        action: String, onClick: () -> Unit, setup: View? = null
    ) = column(16).apply {
        val surface = MaterialColors.getColor(this, MaterialAttr.colorSurface)
        val primary = MaterialColors.getColor(this, MaterialAttr.colorPrimary)
        background = GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            // Material 2 themes do not define the Material 3 container color.
            setColor(MaterialColors.getColor(this@HistoryScreen, MaterialAttr.colorSurfaceContainer,
                MaterialColors.layer(surface, primary, 0.05f)))
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }
        addView(text(title, 21).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(text(subtitle, 14))
        setup?.let(::addView)
        addView(text(summary, 14))
        addView(column().apply {
            dividerDrawable = historyDivider()
            showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
            rows.forEach { addView(it) }
        })
        addView(secondaryButton(action, onClick).apply { isEnabled = rows.isNotEmpty() })
    }

    private fun historyDivider() = GradientDrawable().apply {
        setColor(ColorUtils.setAlphaComponent(
            MaterialColors.getColor(this@HistoryScreen, MaterialAttr.colorOnSurface, "History divider"), 45))
        setSize(1, dp(1).coerceAtLeast(1))
    }

    private fun timestamp(value: Long) =
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(value))

    private fun compactText(value: String, size: Int) = text(value, size).apply {
        setPadding(0, 0, 0, 0)
        includeFontPadding = false
    }

    private fun callRow(entry: CallHistoryEntry) = column().apply {
        setPadding(dp(8), dp(4), dp(8), 0)
        addView(compactText(entry.caller, 17).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(compactText(timestamp(entry.received), 12).apply { setPadding(0, dp(1), 0, 0) })
        val queued = entry.detail.startsWith("Automatic ringtone generation queued")
        val darkTheme = resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val status = if (queued) "Queued" else "Not queued"
        addView(compactText("● $status  ›", 14).apply {
            minHeight = dp(32)
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            contentDescription = "$status. Show call details for ${entry.caller}"
            val feedback = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackground, feedback, true)
            setBackgroundResource(feedback.resourceId)
            setOnClickListener { showCall(entry) }
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(android.graphics.Color.parseColor(
                if (queued) {
                    if (darkTheme) "#81C784" else "#2E7D32"
                } else {
                    if (darkTheme) "#EF9A9A" else "#B71C1C"
                }))
        })
    }

    private fun showCall(initial: CallHistoryEntry) {
        val body = column(20)
        val scroll = ScrollView(this).apply { addView(body) }
        val dialog = AlertDialog.Builder(this).setTitle(initial.caller).setView(scroll)
            .setPositiveButton("Close", null).create()
        dialog.observeUntilDismissed(activity, model.callHistory) {
            val entry = model.callHistory.value.orEmpty().firstOrNull { it.id == initial.id } ?: initial
            body.removeAllViews()
            body.addView(text(timestamp(entry.received), 13))
            body.addView(text(entry.detail, 16).apply { setTextIsSelectable(true) })
        }
        dialog.show()
    }

    private fun itemRow(item: RingtoneItem, compact: Boolean = false) = column(8).apply {
        addView(text(item.contactName, 17).apply { setTypeface(typeface, Typeface.BOLD) })
        addView(text(item.voiceName + " · " + timestamp(item.created), 12))
        addView(text(item.label, 15).apply {
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(MaterialColors.getColor(
                this, if (item.stage == "failed") MaterialAttr.colorError
                else MaterialAttr.colorPrimary))
        })
        if (item.detail.isNotBlank()) addView(text(item.detail, 14).apply {
            if (compact) { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
        })
        if (item.active) addView(ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { isIndeterminate = true })
        addView(text("Open details  ›", 14))
    }

    private fun showAllCalls() = showHistoryList(
        "All incoming calls", model.callHistory, ::callRow)

    private fun showAllItems() = showHistoryList(
        "All ringtone activity", model.items, { itemRow(it) }, ::showItem)

    private fun <T> showHistoryList(
        title: String,
        source: LiveData<List<T>>,
        render: (T) -> View,
        onClick: ((T) -> Unit)? = null
    ) {
        val adapter = RowsAdapter<T>(render = render)
        val list = ListView(this).apply {
            divider = historyDivider()
            dividerHeight = dp(1).coerceAtLeast(1)
            setFooterDividersEnabled(false)
            setHeaderDividersEnabled(false)
            this.adapter = adapter
            if (onClick != null) setOnItemClickListener { _, _, position, _ ->
                adapter.items.getOrNull(position)?.let(onClick)
            }
        }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(list)
            .setPositiveButton("Close", null).create()
        dialog.observeUntilDismissed(activity, source) { adapter.items = source.value.orEmpty() }
        dialog.show()
    }

    private fun showItem(initial: RingtoneItem) {
        val body = column(20)
        val scroll = ScrollView(this).apply { addView(body) }
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
