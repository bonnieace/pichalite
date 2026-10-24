# PichaLite 0.5 preview

## 0.5.1 interaction update

Wrapped cards support horizontal swipes, arrow controls and tappable card
indicators. The bottom rail uses 48 dp destination touch targets in a 64 dp
container, down from 96 dp, before system navigation insets.

An automatic three-scene first-launch introduction demonstrates compression,
verified Gallery saving and local original replacement, including upgrades
that have never seen it. It pauses in the background, honors reduced motion,
supports Skip/Next/Back and is replayable from Help. Completion is persisted.
The repeated local-flow explanation and privacy footer are moved into this
walkthrough. Google Photos stays as the import label, and sending is offered
inside the regular Share sheet rather than through separate buttons.

The custom bottom bar has Studio, Ready and Wrapped. Ready keeps its selection
tools and finished-photo count; selections span pages. Lists use 50 photos per
page without truncating imports, history or Select all. Tap a photo to open a
full-screen preview, pinch to zoom, move between photos across page boundaries,
share a finished image or review its Google Photos handoff.

Studio shows a bundled, offline Lottie animation during imports and active
processing, instead of inserting rows as results arrive. Ready remains available
during that work. Paused queues show their photos so they can be inspected.
Checkbox gutters stay fixed. Import success uses an informational notice;
errors retain a distinct attention treatment. Local replacement statuses use
Compressed and Retained; the cloud action reads Google Photos.

Wrapped is an all-time, four-card story derived from the complete durable
history. It includes actual reclaimed space, compression reduction, completed
and smaller photo counts, local replacements and retained originals, imported,
queued and failed counts, original/output totals, weighted reduction across
smaller photos, best single-photo reduction and largest file reduction.
Reclaimed space is counted only after verified local replacement and cache
cleanup. Compression reduction includes smaller copies not yet replaced.
Neither is an estimate based on a visible page or surviving private files.

The recap takes a snapshot when opened so cards and shares agree while another
batch runs. Reopen it for updated totals. The empty state requires a completed
photo and does not invent wins. Users can share one card or the four-card set
through Android's share sheet. The exact preview artwork is exported at
1080 × 1920 as PNG. Only counts and custom graphics are included, with no photos,
filenames, account information, tracking, network dependency or new permissions.
FileProvider exposes only the finished-photo and recap directories with read
grants. Recap exports have unique paths and old exports expire after one day.

The 0.5 preview retains the 0.4 application ID and cached signing identity so it
can update that preview without clearing its queue. Tests exercise a 623-item
history, all four exported graphics, read grants, pagination, steady streaming
layout, preview navigation, informational notices, bundled animation and custom
navigation. CI retains UI screenshots and rendered recap cards for review.

## Local verification

Android production Kotlin, Java and instrumentation Kotlin compilation and
`lintDebug` passed. Executed the compiled recap calculations on the JVM against
623 records, including 620 smaller outputs, a larger output, a failure and a
pending job. Counts, byte totals, weighted/best reductions, large-unit formatting,
missing-cache behavior and independence from row order passed.

The emulator scenarios, actual share-sheet handoff, visual screenshot review and
signed phone APK remain pending the GitHub CI run. No device test is claimed by
the local JVM check.

## Distributable preview signing

GitHub Actions caches are scoped to branches and PR refs. A new branch can
generate a different debug key even when it uses the same cache key string.
CI test APKs must therefore not be assumed to update an installed preview.

The downloadable phone APK is signed with the recovered 0.4 preview identity,
kept privately as `PichaLite-preview04-signing.keystore`. Future phone releases
must reuse that identity, retain `ke.co.pichalite.preview04`, and increase the
version code. Its SHA-256 certificate fingerprint is
`aaaf22daec44f38d126a32675bede3196a85c5ad4e05189f456beceea2aa066a`.
Verify the signature and unchanged APK payload after signing; never suggest
uninstalling the previous preview to work around a signing mismatch.
