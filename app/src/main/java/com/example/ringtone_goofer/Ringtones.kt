package com.example.ringtone_goofer

import android.content.Context
import android.media.RingtoneManager
import android.net.Uri

data class RingtoneEntry(val name: String, val uri: Uri)

/** Lists ringtones exposed by Android's ringtone provider. Call off the main thread. */
fun list_ringtones(context: Context): List<RingtoneEntry> {
    val manager = RingtoneManager(context).apply { setType(RingtoneManager.TYPE_RINGTONE) }
    return manager.cursor.use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                add(RingtoneEntry(
                    cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX).orEmpty(),
                    manager.getRingtoneUri(cursor.position)
                ))
            }
        }
    }
}
