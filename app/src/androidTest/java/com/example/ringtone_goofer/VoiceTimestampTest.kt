package com.example.ringtone_goofer

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Runs the actual Android/desugared parser, rather than the host JDK implementation. */
@RunWith(AndroidJUnit4::class)
class VoiceTimestampTest {
    @Test fun parsesBackendOffsetTimestampsOnAndroid() {
        val api = RingtoneApi(idToken = { error("No network needed") })
        for (date in listOf("2026-11-05T12:17:24.268545+00:00", "2026-11-05T14:17:24.268545+02:00",
            "2026-11-05T12:17:24.268545Z")) {
            val json = JSONObject("""{"Your Uploads":{"Alex":{"clips":[
                {"id":"clip","name":"Alex.wav","created_at":"$date"}
            ]}}}""")
            assertEquals(Instant.parse("2026-11-05T12:17:24.268545Z"),
                api.parseVoices(json).single().clips.single().createdAt)
        }
    }
}
