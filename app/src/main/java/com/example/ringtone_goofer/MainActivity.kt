package com.example.ringtone_goofer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
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
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.material.bottomnavigation.BottomNavigationView
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

internal enum class ScreenAction { CONTACTS, RECIPIENT, RECORD, GENERATE, ASSIGN, ASSIGNMENT_CONTACT, CALL_HISTORY }

/** Navigation, account sign-in, permission results, and screen lifecycle. */
class MainActivity : AppCompatActivity() {
    internal lateinit var model: RingtoneViewModel
    internal lateinit var audio: AudioPreview
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var signInButton: Button
    private lateinit var navigation: BottomNavigationView
    private var section = R.id.section_contacts
    internal var previewFile: File? = null
    internal var previewTask: Job? = null
    internal var currentScreen: TabScreen? = null
        private set
    private lateinit var contactsTab: ContactsScreen
    private lateinit var generateTab: GenerateScreen
    private lateinit var historyTab: HistoryScreen
    private var pendingPermission: String? = null
    private var pendingAction: ScreenAction? = null
    private var pendingItem: String? = null
    private val auth get() = FirebaseAuth.getInstance()
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val permission = pendingPermission
        val action = pendingAction
        val id = pendingItem
        pendingPermission = null; pendingAction = null; pendingItem = null
        if (granted && action != null) runAction(action, id)
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
                if (::contactsTab.isInitialized) contactsTab.playbackChanged(active)
            })
        val savedSection = savedInstanceState?.getInt("section")
            ?: getPreferences(MODE_PRIVATE).getInt("section", R.id.section_contacts)
        section = savedSection.takeIf { it in setOf(R.id.section_contacts, R.id.section_voices, R.id.section_ringtones) }
            ?: R.id.section_contacts
        contactsTab = ContactsScreen(this, savedInstanceState?.getString("contactQuery").orEmpty())
        generateTab = GenerateScreen(this, savedInstanceState?.getString("voiceQuery").orEmpty())
        historyTab = HistoryScreen(this)
        pendingPermission = savedInstanceState?.getString("permission")
        pendingAction = savedInstanceState?.getString("action")?.let(ScreenAction::valueOf)
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
        listOf(model.contactSelection, model.draft, model.voices, model.voiceRefreshError, model.refreshingVoices,
            model.contacts, model.items, model.callHistory, model.loaded, model.saving, model.recording,
            model.stoppingRecording).forEach { state ->
            state.observe(this) { currentScreen?.refresh?.invoke() }
        }
        model.loaded.observe(this) { updateAccount() }
        model.status.observe(this) { status.text = it }
        updateAccount()
    }

    private fun updateAccount() {
        val user = auth.currentUser
        signInButton.text = if (user == null) "Sign in with Google" else user.email ?: "Google account connected"
        signInButton.isEnabled = true
    }

    internal fun navigate(value: Int) { navigation.selectedItemId = value }

    internal fun requestGeneration(id: String? = null) {
        runAction(ScreenAction.GENERATE, id)
    }

    private fun showSection(value: Int) {
        val enteringGenerate = section != value && value == R.id.section_voices
        stopAudio()
        section = value
        getPreferences(MODE_PRIVATE).edit().putInt("section", value).apply()
        currentScreen = when (value) {
            R.id.section_voices -> generateTab
            R.id.section_ringtones -> historyTab
            else -> contactsTab
        }
        content.removeAllViews()
        content.addView(currentScreen!!.create(), LinearLayout.LayoutParams(-1, -1))
        if (enteringGenerate) model.retryVoiceRefresh()
    }

    internal fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    internal fun runAction(action: ScreenAction, id: String? = null) {
        val permissions = when (action) {
            ScreenAction.CALL_HISTORY -> listOfNotNull(Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE.takeIf { Build.VERSION.SDK_INT < 29 })
            ScreenAction.RECORD -> listOf(Manifest.permission.RECORD_AUDIO)
            ScreenAction.GENERATE, ScreenAction.ASSIGN -> listOfNotNull(
                Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS,
                Manifest.permission.WRITE_EXTERNAL_STORAGE.takeIf { Build.VERSION.SDK_INT < 29 })
            else -> listOf(Manifest.permission.READ_CONTACTS)
        }
        val permission = permissions.firstOrNull { !hasPermission(it) }
        if (permission == null) { perform(action, id); return }
        val launch = {
            pendingPermission = permission; pendingAction = action; pendingItem = id
            permissionLauncher.launch(permission)
        }
        if (shouldShowRequestPermissionRationale(permission)) {
            val reason = when (permission) {
                Manifest.permission.READ_PHONE_STATE -> "Phone access lets History record incoming calls after they end."
                Manifest.permission.READ_CALL_LOG -> "Call log access lets History identify the caller. Your installer must allow this permission."
                Manifest.permission.READ_CONTACTS -> "Contacts access lets you choose a person and resolve their saved contact."
                Manifest.permission.WRITE_CONTACTS -> "Allow contact updates to set this person's ringtone."
                Manifest.permission.RECORD_AUDIO -> "Microphone access is used only while recording a voice sample."
                else -> "Storage access is needed on this Android version to publish the ringtone."
            }
            AlertDialog.Builder(this).setMessage(reason).setPositiveButton("Continue") { _, _ -> launch() }
                .setNegativeButton("Cancel", null).show()
        } else launch()
    }

    private fun perform(action: ScreenAction, id: String?, restored: RingtoneItem? = null) {
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
            ScreenAction.CALL_HISTORY -> { model.status.value = "Automatic call ringtones enabled. Incoming calls use the caller’s newest uploaded clip, or a random famous person clip."; currentScreen?.refresh?.invoke() }
            ScreenAction.CONTACTS -> model.loadContacts()
            ScreenAction.RECIPIENT -> generateTab.chooseGenerationContact()
            ScreenAction.GENERATE -> {
                stopAudio()
                if (id == null) model.generate() else item?.let { model.generateAgain(it) }
                navigate(R.id.section_ringtones)
            }
            ScreenAction.RECORD -> model.startRecording()
            ScreenAction.ASSIGNMENT_CONTACT -> item?.let(historyTab::chooseAssignmentContact)
            ScreenAction.ASSIGN -> item?.let { model.assign(it) }
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

    internal fun stopAudio() {
        previewTask?.cancel(); previewTask = null
        audio.stop()
        previewFile?.delete(); previewFile = null
    }

    internal fun statusHeader(header: LinearLayout) {
        status = text(model.status.value.orEmpty(), 13).apply {
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        header.addView(status)
    }
    override fun onResume() {
        super.onResume()
        if (::model.isInitialized && hasPermission(Manifest.permission.READ_CONTACTS)) model.loadContacts()
        currentScreen?.refresh?.invoke()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("section", section); outState.putString("contactQuery", contactsTab.contactQuery)
        outState.putString("voiceQuery", generateTab.voiceQuery)
        outState.putString("permission", pendingPermission); outState.putString("action", pendingAction?.name)
        outState.putString("item", pendingItem)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        stopAudio()
        model.stopRecording()
        super.onStop()
    }
}
