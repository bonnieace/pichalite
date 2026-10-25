# PostHog analytics (optional)

PichaLite's compression remains entirely on-device. Analytics is disabled by default, including when a PostHog project is configured. The UI shows an opt-in switch only when a project token is supplied.

## Build configuration

Create a PostHog project and use its **project API key** (not a personal API key). Set Gradle properties for the Android build:

```bash
cd android
./gradlew assembleDebug -PposthogKey=phc_YOUR_PROJECT_TOKEN -PposthogHost=https://us.i.posthog.com
```

Use `https://eu.i.posthog.com` for an EU PostHog project. For GitHub Actions builds, add the project token as a repository secret and explicitly wire the build argument in the workflow; this PR does not modify CI. Without the key the SDK is not initialized, and the consent toggle is not displayed.

## Events

- `photos_imported`: requested, imported, and failed counts (never URIs).
- `compression_completed`: input/output bytes and compression reduction for each newly observed completed job.
- `photo_share_opened`: Android sharesheet launched.
- `google_photos_handoff_opened`: handoff launched; **not** proof of a completed cloud backup.

No image data, filenames, file paths, Google accounts, EXIF, identifiers from content URIs, or arbitrary exception messages are sent. PostHog creates an anonymous SDK-generated ID only when configured; it does not identify people via their accounts.

Compression savings are **not** the same as device storage reclaimed, because originals are preserved. Do not label compression reduction as storage reclaimed.

Analytics never runs from the Rust image worker and cannot block its queue. Error autocapture, session replay, deep links and screen autocapture are disabled, and events are only sent after user opt-in. Disable with the switch at any time. SDK opt-out may retain previously sent information in the PostHog project; manage deletion through the PostHog dashboard as necessary.

The `compression_completed` event uses the existing foreground service's per-ID completion marker to avoid duplicate emissions across ordinary polling; it is best-effort, not a billing or accounting ledger.

## Validation checklist

1. Without PostHog project token, no toggle or network analytics.
2. With token, app launches opted out and the PostHog Live Events page stays empty.
3. Turn on analytics, import and compress a photo; verify only metadata events arrive.
4. Turn analytics off, import another photo; verify no further events.
5. Run offline and reconnect; buffered events can flush when connectivity resumes.
6. Confirm actual image paths/names never appear in PostHog events.
