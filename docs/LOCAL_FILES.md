# Local compression and replacement (0.4.0)

**Compress local photos** is the primary action. Choose a batch of JPEG, PNG or
WebP files from Android's local file browser. Compression, saving the smaller
image to Gallery, verification and original deletion form one flow. There is
no separate save prerequisite, cleanup selection or disabled delete button.

1. Import and compress locally, using the durable Rust queue.
2. Save the finished JPEG to Gallery (`Pictures/PichaLite` on Android 10+).
3. Flush and reopen the saved image, verify its SHA-256 against the finished
   output, and check that the original still matches the imported bytes.
4. Delete only the exact original document retained at import, then remove this
   completed import's private full-size cache. Keep the smaller working output
   for sharing.

The service performs replacement after compression even if the screen is closed.
If Android requires permission, it saves progress and asks through the system
permission prompt when the app is foregrounded. Android 11+ uses a write-access
request for the exact MediaStore items (one prompt for a finished batch), then
rechecks all bytes before deleting.
Android 10 uses its recoverable permission action. Cancelling keeps both files
and shows **original kept** with a **Retry replacement** action. Errors also
show an actionable status instead of a disabled control. Non-smaller outputs
keep the original without adding a Gallery copy.

Saved-copy records and replacement states survive restart. The service and
screen serialize replacements to prevent duplicate saves/deletes. A recorded
export is reused, never silently recreated if missing or modified. An interrupted
save before its export record commits may leave an extra Gallery copy; it can
never authorize original deletion. A crash between deletion and its progress
record may leave the original reported as kept; no other file is substituted.

Only known local document providers support deletion. An equivalent MediaStore
URI is resolved through Android's API; filenames and guessed paths are never
used to locate originals. Read-only or unsupported sources keep the original
and explain the issue. Android 8/9 asks for storage permission before local
import so the smaller image can be saved to Gallery.

**Google Photos / cloud** remains an import-copy flow using Android Photo Picker.
PichaLite cannot delete cloud originals. Existing imports without a retained
local source need to be selected again through **Compress local photos**.
Existing 0.3 local imports with source references continue through replacement.

Finished photos can be selected for Share or Google Photos handoff. Optional
**Export another copy** reveals folder/Gallery export actions. Exporting another
copy is separate from the automatic save that is already part of compression.
There is no app-imposed 30-photo selection truncation; platform picker limits,
the 40 MB per-image limit and the queue view's existing 500-entry window remain.

The dashboard reports size reduction, not measured net device free space: the
smaller working output remains in private storage for sharing.

## Preview update

Version 0.4.0 uses package `ke.co.pichalite.preview04`, installing alongside
0.3.0 to preserve its private queue. The older preview's signing cache did not
save a key; an in-place update cannot be signed with that lost key. Select local
originals through Compress local photos in the new preview. Future 0.4 builds
use an explicitly configured and cached preview keystore. Android build/lint and
Android 13 instrumentation run in CI. Physical-phone provider behavior remains
a device test.
