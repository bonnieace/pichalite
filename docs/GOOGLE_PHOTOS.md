# Google Photos handoff (0.2.0)

PichaLite imports user-selected photos, compresses a local copy, and hands a
finished JPEG to the installed Google Photos app. Google Photos handles account
selection, upload and backup. This version does not call Photos REST APIs,
request Google OAuth credentials, verify cloud backup, or delete cloud originals.

The downloadable debug APK is **PichaLite Preview**, version **0.2.0-preview**,
application ID `ke.co.pichalite.preview`. It installs beside the earlier 0.1.0
challenge APK, preserving that app's private queue and outputs. Import photos
into Preview separately; the two apps do not share their histories. CI debug
signing keys are disposable, so no uninstall of the old app is required.

## Use it

1. Tap **Choose photos**. Android's Photo Picker includes cloud media when an
   eligible provider is available and enabled on that device. On older devices
   it falls back to document selection. Alternatively, share from Google Photos
   into PichaLite.
2. Select JPEG, PNG or WebP images (the platform picker determines its maximum). Cloud downloads require internet.
   PichaLite copies complete bytes into its private inbox before queueing;
   subsequent Rust compression works offline. HEIC is not supported yet.
3. On a finished result, tap **Save to Google Photos**. Compare both images and
   their sizes. The review warns when JPEG conversion provides no size saving.
4. Tap **Send lighter copy**. Google Photos receives a read-only URI for the
   finished JPEG. Choose the correct account and finish saving/uploading there.
   If Google Photos cannot handle the intent, PichaLite shows an error and leaves
   the review unstarted. Ordinary Share remains available for other apps.
5. Return to the review. Open Google Photos and confirm that the smaller copy
   has finished cloud backup in the intended account. Only then select the
   checkbox and tap **Record my check**. This is a user assertion, not an API
   verification. Cancelling or returning from Google Photos never counts as
   cloud success.
6. Manual original-cleanup guidance now appears. Identify the larger original
   in Google Photos by its image, filename and size. Keep the verified smaller
   copy; move only the original to Trash yourself if desired.

Sending a smaller copy alone does not free cloud storage. The Photos / cloud import flow never deletes
an original, empties Trash, or requests write access to a shared source. Deleting
through Google Photos can also remove synced device copies and album entries.
The Android picker does not provide a Google Photos cloud media-item ID, so this
flow cannot link directly to or automatically identify the original cloud item.

## Batch actions and local files (0.3.1)

**Compress local photos** opens local folder browsing. Local compression now
saves a verified smaller image to Gallery and deletes the unchanged original
in one flow. Android may ask for replacement access. Declining keeps the
original and shows Retry replacement. This local flow does not delete cloud
originals. See [local compression and replacement](LOCAL_FILES.md).

Finished photos can be selected individually or with **Select all**, then shared
or sent together to Google Photos. **Export another copy** reveals folder and
Gallery exports. A Google Photos batch handoff is still not proof of cloud
backup. Review each copy in Photos.

## Review persistence and tests

Handoff and user-review states persist per photo/output generation in private
SharedPreferences. Opening or returning from Google Photos records no cloud
success. Changed, missing or reprocessed outputs must be reviewed again.
The queue and processing remain the existing Rivium/Apalis/SQLite engine.

`PhotoBackupTest` checks dispatch remains unverified, review survives recreation,
confirmation cannot precede dispatch, changed/missing output resets review,
originals remain unchanged, and the handoff exposes only a finished output with
read-only FileProvider access. It launches no external app or Google account.

Before claiming real Google Photos compatibility, test on a physical phone:

- Import a cloud-only photo; interrupt the download and retry.
- Cancel the picker and Google Photos save flow: neither marks backup done.
- Save to the intended account; verify the copy's cloud backup in Google Photos.
- Reopen PichaLite; check review state, previews, sizes and cleanup guidance.
- Test without Google Photos installed: show an error and retain the source.
- Check both cloud copies remain until the user manually removes one.

All six Android instrumentation tests (five backup tests plus the native queue
test), Android lint, native builds and Rust checks passed in
https://github.com/bonnieace/pichalite/actions/runs/40078425569.
No real-account upload or deletion is performed by automated tests.

## References

- https://developer.android.com/training/data-storage/shared/photo-picker
- https://developer.android.com/training/sharing/send
- https://developers.google.com/photos/library/reference/rest/v1/mediaItems
- https://developers.google.com/photos/library/guides/upload-media
- https://support.google.com/photos/answer/6128858
