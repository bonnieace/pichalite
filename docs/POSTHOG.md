# PostHog analytics (optional)

PichaLite's compression remains entirely on-device. Analytics is disabled by default, including when a PostHog project is configured. The UI shows an opt-in switch only when a project token is supplied.

## Build configuration

The PichaLite **public project API key** is now configured as the default build value. The integration uses the US ingestion endpoint by default. You can override either value with Gradle properties:

```bash
cd android
./gradlew assembleDebug -PposthogKey=phc_YOUR_PROJECT_TOKEN -PposthogHost=https://us.i.posthog.com
```

Use `https://eu.i.posthog.com` for an EU PostHog project. For GitHub Actions builds, add the project token as a repository secret and explicitly wire the build argument in the workflow; this PR does not modify CI. The configured public project key is safe in an APK; never embed PostHog secret keys (such as `phs_`) or personal API keys. Analytics stays opted out until a user enables it.

## Events

- `photos_imported`: requested, imported, and failed counts (never URIs).
- `compression_completed`: input/output bytes and compression reduction for each newly observed completed job.
- `photo_share_opened`: Android sharesheet launched.
- Sharing opens the system sharesheet, including the preferred Google Photos handoff. It never claims a completed backup.

No image data, filenames, file paths, Google accounts, EXIF, identifiers from content URIs, or arbitrary exception messages are sent. PostHog creates an anonymous SDK-generated ID only when configured; it does not identify people via their accounts.

Compression savings are **not** the same as device storage reclaimed, because only verified local replacement and cleanup reclaim device space. Cloud originals are preserved. Do not label compression reduction as storage reclaimed.

Analytics never runs from the Rust image worker and cannot block its queue. Error autocapture, session replay, deep links and screen autocapture are disabled, and events are only sent after user opt-in. Disable with the switch at any time. SDK opt-out may retain previously sent information in the PostHog project; manage deletion through the PostHog dashboard as necessary.

The `compression_completed` event uses the existing foreground service's per-ID completion marker to avoid duplicate emissions across ordinary polling; it is best-effort, not a billing or accounting ledger.

## Validation checklist

1. Without a PostHog project token in an overridden build, no toggle or network analytics.
2. With token, app launches opted out and the PostHog Live Events page stays empty.
3. Turn on analytics, import and compress a photo; verify only metadata events arrive.
4. Turn analytics off, import another photo; verify no further events.
5. Run offline and reconnect; buffered events can flush when connectivity resumes.
6. Confirm actual image paths/names never appear in PostHog events.

## Current preview

Version0.5.5 integrates this consent-based analytics with the0.5.4 Wrapped, paginated library, local replacement, durable queue and approved animations. It keeps package `ke.co.pichalite.preview04` and the cached preview signing key so it can update the current preview. SDK errors are best-effort and cannot fail a photo operation.
