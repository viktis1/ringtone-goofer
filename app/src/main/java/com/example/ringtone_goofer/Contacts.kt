package com.example.ringtone_goofer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.ContactsContract.Contacts
import androidx.core.content.ContextCompat

data class Contact(val id: Long, val name: String, val ringtoneUri: String? = null)

/** Returns device contacts sorted by name. Call off the main thread; requires READ_CONTACTS. */
fun list_contacts(context: Context): List<Contact> {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS)
        != PackageManager.PERMISSION_GRANTED) {
        throw SecurityException("Contacts permission is required.")
    }
    return context.contentResolver.query(
        Contacts.CONTENT_URI, arrayOf(Contacts._ID, Contacts.DISPLAY_NAME_PRIMARY, Contacts.CUSTOM_RINGTONE),
        null, null, Contacts.SORT_KEY_PRIMARY
    )?.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(Contact(cursor.getLong(0), cursor.getString(1).orEmpty(), cursor.getString(2)))
            }
        }
    } ?: error("Contacts provider is unavailable.")
}
