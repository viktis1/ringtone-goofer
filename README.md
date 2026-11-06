# Ringtone Goofer

An Android app for turning a contact and a voice sample into a personal ringtone.

## Workflow

1. Sign in with Google, open **Contacts**, grant contacts access, and select a person.
2. In **Voices**, select a voice clip and listen to a preview.
3. Press **Generate ringtone**. The complete request is saved before submission.
4. Open the item in **Ringtones** to track queue, script and audio stages.
5. When ready, tap **Download ringtone**. The WAV is saved privately for offline listening.
6. Tap **Listen**, then **Assign**. Assignment publishes the audio to Android's ringtone storage and updates the saved contact.

You can leave or close the app between these steps. The last section, draft, request, status, audio and assignment state are retained on this installation. Selecting a different contact or voice never changes an existing generation.

## Recovery

- WorkManager schedules submission, status polling, downloads, recording uploads and assignment independently of the screen.
- Every generation has a persisted UUID4. Retries reuse it; the backend deduplicates both API and cloud submissions.
- If the API is unavailable during submission, the same request is uploaded to the configured Firebase bucket. A missing status while cloud ingestion is pending is a waiting state.
- Network failures retry with backoff. Downloads write to a partial file and publish the complete private file only after WAV validation.
- Permission and other actionable failures keep a retry checkpoint. A generation that failed on the server offers **Generate again**, with a fresh UUID.
- Assignment stores a contact lookup URI, resolves merged contacts, and reuses already published MediaStore audio on retry.
- Each Google account has a separate draft and library. Signing out pauses that account's work. Sign in again to resume.
- Older submitted-job history is imported. Because old entries lack contact lookup URIs, select an assignment contact explicitly after downloading those items.

Android can defer background work for connectivity, battery restrictions and quotas. Swiping the app away does not discard work. A force-stop prevents Android from running work until the app is opened again. Clearing app data or uninstalling removes the private library and downloads. This is installation persistence, not cross-device synchronization.

There is no backend cancellation endpoint. Closing the app or signing out does not cancel generation already accepted by the server.

## Recordings

**Contacts → Contact audio & voice recording** provides current-ringtone playback, recording, local playback and upload.

Recording requires microphone access and stops/saves when the activity leaves the foreground. The format is mono, 44.1 kHz, 16-bit PCM WAV. A completed recording is written atomically, preserving the previous recording if capture fails. A killed process cannot continue microphone capture.

Uploads use an immutable copy, a stable UUID in metadata and persistent background work. Refresh Voices after the server finishes ingesting a recording. The upload bucket and request bucket are configured in `strings.xml`.

## Code

- `MainActivity.kt`: Contacts, Voices and Ringtones sections, permission prompts and account UI.
- `RingtoneViewModel.kt`: draft selection and user actions; no generation polling tied to the screen.
- `RingtoneLibrary.kt`: SQLite persistence, account isolation, migration and private audio paths.
- `RingtoneWork.kt`: unique, restartable WorkManager operations.
- `GenerationPipeline.kt`: bounded submission/status state machine, testable without Android or Firebase.
- `RingtoneApi.kt`: authenticated HTTP, voice catalog, preview and WAV validation.
- `ContactRingtoneStore.kt`: contacts, MediaStore publication and assignment.
- `AudioPreview.kt`: foreground playback lifecycle.
- `JobHistory.kt`: reader for migration from the old history format.
- `Contacts.kt` and `Ringtones.kt`: unused original demo helpers.

SQLite records and generated private audio live in `noBackupFilesDir`. Published ringtones live under `Ringtones/RingtoneGoofer`. Android 7–9 request storage permission only when publishing for assignment. Changing an individual contact's ringtone requires contacts permissions, not permission to change the system default.

The existing backend receiver name remains `viktor`. Firebase configuration and the Google web client ID must match the installed app's signing certificate.

Backend source: https://github.com/viktis1/homelab-k3s/tree/main/create-ringtone

API docs: https://create-ringtone.viktor-homelab.cloud/docs

## Verification

```sh
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
./gradlew :app:connectedDebugAndroidTest
```

Local tests use fake HTTP and injected pipeline dependencies; they never submit work to the real backend. Recovery tests cover interruption after submission, uncertain responses, cloud-ingestion waits, bounded polling, failure handling and serialized download/assignment state.

The instrumented database test opens a separate SQLite database, closes and reopens it at every workflow checkpoint, and checks draft persistence, account isolation and retained audio. It does not touch real contacts or the user's library.

Device acceptance checks: generate and close the app; return while queued/running; download and close; reopen and listen offline; assign with denied/granted permissions; reopen and verify the contact's ringtone. Test recording uploads and actual incoming-call playback separately on a device.
