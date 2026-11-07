package com.example.ringtone_goofer

import kotlin.random.Random

/** Persist this plan with the completed call before handing it to the ringtone pipeline. */
internal data class CallGenerationPlan(val item: RingtoneItem?, val detail: String)

internal fun planCallGeneration(
    call: IncomingCall, contactUri: String, contactName: String,
    uid: String?, voices: List<Voice>, random: Random = Random.Default
): CallGenerationPlan {
    fun skip(reason: String) = CallGenerationPlan(null, "No generation: $reason")
    if (contactUri.isBlank() || contactName.isBlank()) return skip("caller is not a saved contact.")
    if (uid.isNullOrBlank()) return skip("sign in to generate ringtones.")
    // Upload names follow the same contact-name/recording-filename matching as the Generate tab.
    val uploaded = voices.filter { it.category == "Your Uploads" && it.isAvailableForRecipient(contactName) }
        .flatMap { voice -> voice.clips.filter { it.id.isNotBlank() }.map { voice to it } }
        .sortedWith(compareByDescending<Pair<Voice, VoiceClip>> { it.second.createdAt }
            .thenBy { it.first.id }.thenBy { it.second.id })
        .firstOrNull()
    val selected = uploaded ?: voices.filter { it.category == "Public" }
        .flatMap { voice -> voice.clips.filter { it.id.isNotBlank() }.map { voice to it } }
        .randomOrNull(random)
        ?: return skip("no caller upload or public voice clips are available.")
    val (voice, clip) = selected
    return CallGenerationPlan(
        RingtoneItem(call.id, uid, contactUri, contactName, voice.id, voice.name, clip.id, clip.name,
            created = call.received),
        "Automatic ringtone generation queued using ${voice.name} / ${clip.name}. See the ringtone item below for progress."
    )
}
