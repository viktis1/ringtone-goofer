# Ringtone Goofer

A small Android app using standard Views. No account or backend configuration is needed.

## Use

1. Load contacts and grant Contacts access. The overview shows every contact and their current ringtone.
2. Tap a contact, then use **Play selected contact's ringtone** to listen.
3. Choose a voice and its clip; **Preview clip** downloads the server's reference audio into a temporary app cache file and plays it locally. Stop audio, changing the selection, or leaving the screen cancels preview loading.
4. **Generate ringtone** sends only the selected contact's name as `caller`, `receiver: "viktor"`, the voice/clip IDs, and a fresh UUID4.
5. The app checks the returned status URL every four seconds, then downloads the WAV only after `done`.
6. **Assign ringtone to [name]** requests contact write access and updates that contact. Select that contact and press Play again to listen.

Downloads are saved in shared storage under `Ringtones/RingtoneGoofer` using MediaStore, so Android's phone app can access them. Android 7–9 additionally require storage permission. No permission to change the phone's default ringtone is needed.

## Code

- `MainActivity.kt`: a scrollable screen, permission prompts, and audio playback.
- `RingtoneViewModel.kt`: screen selections and the submit/poll/download flow.
- `RingtoneApi.kt`: HTTP and the actual OpenAPI request fields.
- `ContactRingtoneStore.kt`: contact queries, shared WAV storage, and assignment.
- `Contacts.kt` and `Ringtones.kt` are the original, unused demo helpers.

API reference: https://create-ringtone.viktor-homelab.cloud/docs  
Schema: https://create-ringtone.viktor-homelab.cloud/openapi.json

The response schemas are unconstrained JSON in OpenAPI; response fields follow the descriptions in that document and the live `/voices` response.

## Job behavior and limits

Rotation preserves the active job and selections through a ViewModel. Network requests have a 10-second connection timeout and 30-second read timeout. HTTP/server errors appear on screen. Each press of Generate creates a new UUID; there is no retry/resume job action.

Cancel stops local monitoring and downloading; the API has no endpoint to cancel server work. An in-flight HTTP request may take until its timeout to release resources. Finishing the activity or process termination stops monitoring; job state is not persisted across app restarts. Completed WAV files remain available in ringtone storage.

## Verification

`./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`

API tests use a local HTTP server to verify exact submission fields, UUID reuse, returned job URLs, voice/clip parsing, HTTP errors, and WAV downloads. Contact assignment and actual call playback should also be verified on a device with contacts.
