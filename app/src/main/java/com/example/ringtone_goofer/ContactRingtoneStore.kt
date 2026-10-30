package com.example.ringtone_goofer

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.ContactsContract.Contacts
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.File

data class AddressBookContact(val lookupUri: Uri, val name: String, val ringtone: Uri?, val ringtoneName: String)

/** Independent implementation; the original demo helpers are deliberately unused. */
class ContactRingtoneStore(private val context: Context) {
    private val resolver get() = context.contentResolver

    private fun requirePermission(permission: String) {
        check(ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            "Permission required: $permission"
        }
    }

    fun contacts(): List<AddressBookContact> {
        requirePermission(Manifest.permission.READ_CONTACTS)
        return resolver.query(Contacts.CONTENT_URI,
            arrayOf(Contacts._ID, Contacts.LOOKUP_KEY, Contacts.DISPLAY_NAME_PRIMARY, Contacts.CUSTOM_RINGTONE),
            null, null, Contacts.SORT_KEY_PRIMARY)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val uri = cursor.getString(3)?.takeIf(String::isNotBlank)?.let(Uri::parse)
                    val title = if (uri == null) "Default ringtone" else runCatching {
                        RingtoneManager.getRingtone(context, uri)?.getTitle(context) ?: "Unavailable ringtone"
                    }.getOrDefault("Unavailable ringtone")
                    add(AddressBookContact(Contacts.getLookupUri(cursor.getLong(0), cursor.getString(1)),
                        cursor.getString(2).orEmpty(), uri, title))
                }
            }
        } ?: error("Contacts provider unavailable")
    }

    @Suppress("DEPRECATION")
    fun saveWav(file: File, id: String, name: String): Uri {
        if (Build.VERSION.SDK_INT < 29) requirePermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        val filename = "ringtone-$id.wav"
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, filename)
            put(MediaStore.Audio.Media.TITLE, "Ringtone for $name")
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
            put(MediaStore.Audio.Media.IS_RINGTONE, true)
            put(MediaStore.Audio.Media.IS_MUSIC, false)
            if (Build.VERSION.SDK_INT >= 29) {
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_RINGTONES + "/RingtoneGoofer")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            } else {
                val directory = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_RINGTONES),
                    "RingtoneGoofer")
                check(directory.isDirectory || directory.mkdirs()) { "Cannot create ringtone directory" }
                put(MediaStore.Audio.Media.DATA, File(directory, filename).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("Cannot create ringtone")
        try {
            resolver.openOutputStream(uri, "w")?.use { output ->
                file.inputStream().use { it.copyTo(output) }
            } ?: error("Cannot write ringtone")
            if (Build.VERSION.SDK_INT >= 29) {
                check(resolver.update(uri, ContentValues().apply {
                    put(MediaStore.Audio.Media.IS_PENDING, 0)
                }, null, null) > 0) { "Cannot publish ringtone" }
            }
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    fun assign(contact: AddressBookContact, ringtone: Uri) {
        requirePermission(Manifest.permission.WRITE_CONTACTS)
        // Resolve the lookup key again in case contacts were merged while the job ran.
        val uri = Contacts.lookupContact(resolver, contact.lookupUri) ?: error("Contact no longer exists")
        check(resolver.update(uri, ContentValues().apply {
            put(Contacts.CUSTOM_RINGTONE, ringtone.toString())
        }, null, null) > 0) { "Contact could not be updated (it may be read-only)" }
    }
}
