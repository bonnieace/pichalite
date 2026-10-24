# Local files, batches and cleanup (0.3.0)

## Import and select

- **Local files** opens Android's file browser with local-only, multiple-document
  selection. JPEG, PNG and WebP remain supported, with a 40 MB per-file limit.
- **Photos / cloud** keeps the Android Photo Picker. Android or the installed
  picker may impose its own selection maximum. PichaLite no longer truncates an
  incoming selection to 30; shared batches also use the full selection.
- Imports are streamed one file at a time, preserve a 16 MB free-space reserve,
  and begin queued compression while the rest of the batch is being imported.
- Finished rows have checkboxes. **Select all** selects every finished photo in
  the queue view. **Clear** resets selection. The existing queue snapshot displays
  the newest 500 entries; this change does not introduce pagination.
- Batch actions apply to the selected finished photos: **Save to folder**,
  **Gallery** (Android 10+), **Share**, and **Google Photos**. Android 8/9 users
  save through the folder picker without a broad storage permission.

## Save before cleanup

Save to folder creates new JPEG documents, never overwriting originals. Gallery
saves go to `Pictures/PichaLite` and are published only after the complete bytes
have been written and verified. Each saved copy is reopened and its SHA-256 is
compared with the finished output. Save records survive restarting the app.

The dashboard says **Size reduction**, not storage freed. Private imports and
working outputs occupy storage; saving/sharing a second copy alone does not
reclaim space. After successful original deletion, the completed photo's private
full-size import is removed. PichaLite retains its smaller working output for
sharing, so the reduction figure is not a measurement of net device free space.

## Delete local originals

1. Import through **Local files** in version 0.3.0 or later.
2. Select finished photos and save them to a local folder or Gallery.
3. Select only eligible local imports and choose **Delete local originals**.
4. Review the permanent-deletion confirmation and choose **Delete originals**.

For every file, PichaLite checks again that:

- The output is a readable image, exists at the expected private output path,
  has the recorded size, and is smaller than the original.
- The externally saved local copy still exists and exactly matches the output's
  SHA-256. Changes to either copy block deletion.
- The original still matches the imported source SHA-256.
- The original is a document from Android's standard local external-storage,
  media or downloads provider, with a writable grant and explicit delete support.

PichaLite deletes only the retained source URI. It does not search by filename,
image similarity, or guessed paths. Unsupported or read-only providers show an
error and retain the original. A cloud folder export does not unlock cleanup.
If an individual deletion fails, other files can succeed; the result reports
how many completed and the first failure. Cancelling the app confirmation does
nothing. There is no background or automatic original deletion.

Cloud-picker imports, shared imports, and imports made before source tracking
was added remain ineligible. Re-import a local original through **Local files**
if cleanup is needed. No Google Photos cloud item is deleted or verified by this
local-file feature. Google Photos cloud cleanup remains manual.

## Preview installation

The 0.3.0 debug APK is **PichaLite 0.3 Preview**, package
`ke.co.pichalite.preview03`. It installs beside the prior 0.1/0.2 builds, avoiding
CI debug-signature conflicts and preserving their private data. Import photos
into the new preview separately. Release application ID remains unchanged.

## Validation

Instrumentation covers actual import of 35 photos without truncation, picker
cancellation, batch URI grants, a complete local save-and-delete cycle, save
record persistence, missing/truncated exports, same-size source/output changes,
non-smaller outputs and cloud/unknown-source exclusions. Fixtures are provided
by a private debug-only documents provider; tests touch only generated images.
Real provider/device compatibility still requires a physical-phone check.
