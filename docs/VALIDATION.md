# Validation record

## 0.2.0-preview — 24 October 2026

- Passing CI: https://github.com/bonnieace/pichalite/actions/runs/40078425569
- Tested source: `0fa4a11a58037e082ba2f1a5047f6531a83e5020`
- Application ID: `ke.co.pichalite.preview`; installs beside the 0.1.0 APK.
- APK: `PichaLite-0.2.0-preview.apk`, 30,406,775 bytes.
- APK SHA-256: `58952110c3ba8f995c0546aa9214b1a51815c27a29e87953127d66b37df73fb1`.

| Check | Observed result |
|---|---|
| Rust formatting, Clippy, tests, desktop recovery and JVM JNI lifecycle | Passed |
| ARM64 and x86_64 native compilation, Android packaging and lint | Passed |
| Android 11 / API 30 x86_64 emulator instrumentation | 6 tests passed |
| Existing native queue test | Pause, enqueue, runtime restart, resume, JPEG output, no duplicate row |
| New Google Photos handoff tests | 5 passed: dispatch stays unverified, review survives recreation, confirmation requires dispatch, changed/missing output resets review, read-only output sharing and rejected input/unfinished sharing |
| APK inspection | ZIP integrity and artifact digest checked; both native ABIs, package, versionCode 2 and versionName 0.2.0-preview confirmed |
| Real Google Photos upload/cloud picker/account backup | Not tested; requires the physical-phone checklist in `GOOGLE_PHOTOS.md` |
| Cloud deletion | No automatic deletion implemented or performed |

The tests exercise our state and file-sharing contracts; they launch no Google
Photos activity and do not prove that a particular version/account completes
an upload. Confirmation is explicitly recorded by the user, not claimed from
an API response. The following docs-only commit does not change the tested APK.

# Historical validation — 0.1.0 (23 October 2026)

Version 0.1.0 passed the desktop and Android JNI queue checks below. A later
user-supplied phone recording confirmed photo selection, compression and opening
the Share Sheet. The PR includes that demo and is ready for review. Device
details and a physical-phone force-stop/reopen check remain unrecorded.

- Passing CI: https://github.com/bonnieace/pichalite/actions/runs/39964221602
- Tested source: `7c24ca4f2e2fbdd5b4221cab60f78fc08672f3c4`
- Ongoing competition PR: https://github.com/apalis-dev/rivium-challenge/pull/2

| Check | Observed result |
|---|---|
| Rust formatting | Passed |
| Clippy, all targets/features, warnings denied | Passed |
| Rust tests | 3 passed: durable deduplication, root ownership/import boundary, image resizing/alpha/idempotent output |
| Rivium + Apalis process smoke | Passed: 3 photos queued while paused, forced kill, reopen/resume, 3 completed jobs, no duplicate outputs |
| Desktop JVM with `-Xcheck:jni` | Passed: 5 immediate pause/start/stop generations with `TOKIO_WORKER_THREADS=1`, snapshot and malformed-request handling |
| Android Rust compilation | Passed for arm64-v8a and x86_64 |
| Android app and test APK compilation | Passed |
| Android lint | Passed; nonblocking dependency/style/backup warnings remain |
| Android emulator execution | Passed on accelerated Android 11 / API 30, x86_64 |
| Android instrumentation | Passed: pause, enqueue, stop/restart, persisted pause and pending job, resume, 1600x800 JPEG output, second restart with one row for the job |
| Physical phone / organiser bonus | Basic compression and Share Sheet recorded; device details and recovery checks remain pending |
| Challenge PR | PR #2 ready for review; subsequent fixes use the same branch |
| Demonstration video/GIF | 25-second real-phone motion demo attached to PR #2 |

### Fixes found through Android execution

1. Rivium 0.2.0 logger file locking returns Unsupported on Android with Rust
   1.94. The narrow vendored patch uses fs2's exclusive lock on Android;
   see `../vendor/README.md`.
2. SQLite access is serialized and connections close before a new runtime
   generation starts.
3. Photo pause uses the persisted processing gate. Apalis continues polling so
   pending database transactions can finish; pausing the entire worker could
   park a fetch holding a SQLite connection.

### Tested APK

`PichaLite-0.1.0-debug.apk`, 30,389,987 bytes. Built by the passing CI run above.

SHA-256:

```text
742c65365ad4dc2c8d9dbf012c988f83eceb4799999553280d2d7bd3c6c9839f
```

Earlier local-build signing and 16 KB alignment checks passed. The Android
instrumentation result above verifies the native queue path. The later phone
recording demonstrates compression and opening the Share Sheet. Notification
behavior, device details and physical-phone force-stop/reopen recovery remain
unrecorded. No Google Photos account flow was tested with 0.1.0.

