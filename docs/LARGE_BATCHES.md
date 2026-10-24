# Large batches and phone installation

The previous snapshot selected the newest 500 rows and summed only their
currently existing output files. A batch larger than that could push old results
out of the window as new ones completed, making the displayed total rise and
fall. Clearing or replacing a cached file could also lower the total.

Snapshots now include the full queue. Compression reduction and completed counts
use committed output sizes, independent of the visible rows or cached files.
Actual space reclaimed is persisted separately, after a verified local save,
original deletion and release of both private working files. Retries overwrite
the same accounting record, so the same completion is never added twice.

Saved local results retain their external URI and remain ready, previewable,
shareable and exportable after private caches are released. Additional exports
do not replace the tracked primary saved copy. User deletion or edits outside
PichaLite do not rewrite historical compression totals.

Regression tests cover 620 completed results plus new work, runtime restart,
cache removal, idempotent cache release and the real Android native worker's
save/verify/replace/share flow.

The phone download includes only ARM64 native libraries. Emulator checks use a
separate x86_64 APK; a universal APK is also built internally. The preview uses
an explicit cached signing key. It installs alongside the older 0.3 preview,
whose key was not retained. No existing app data is uninstalled or cleared.
Android's package installer and manufacturer security scans can still add time.
