# Validation record — 23 October 2026

Version: 0.1.0. The desktop and Android JNI queue checks pass. Physical-phone
observations and demonstration media are still pending; the competition PR is a draft.

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
| Physical phone / organiser bonus | Pending; see `PHONE_TEST.md` |
| Challenge PR | Draft PR #2 opened; subsequent fixes use the same branch |
| Demonstration video/GIF | Pending |

## Fixes found through Android execution

1. Rivium 0.2.0 logger file locking returns Unsupported on Android with Rust
   1.94. The narrow vendored patch uses fs2's exclusive lock on Android;
   see `../vendor/README.md`.
2. SQLite access is serialized and connections close before a new runtime
   generation starts.
3. Photo pause uses the persisted processing gate. Apalis continues polling so
   pending database transactions can finish; pausing the entire worker could
   park a fetch holding a SQLite connection.

## Tested APK

`PichaLite-0.1.0-debug.apk`, 30,389,987 bytes. Built by the passing CI run above.

SHA-256:

```text
742c65365ad4dc2c8d9dbf012c988f83eceb4799999553280d2d7bd3c6c9839f
```

Earlier local-build signing and 16 KB alignment checks passed. The Android
instrumentation result above verifies the native queue path, not the full
Share Sheet, notification or Compose UI walkthrough. Those behaviors, a real
process force-stop/reopen on Android, ARM64 execution and the phone bonus still
need the phone observations and demonstration media in `PHONE_TEST.md`.
