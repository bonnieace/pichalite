# Validation record — 23 October 2026

Version: 0.1.0. Source and APK are a first challenge implementation; submission
and physical-phone testing are still pending.

| Check | Observed result |
|---|---|
| Rust formatting | Passed |
| Clippy, all targets/features, warnings denied | Passed |
| Rust tests | 3 passed: durable deduplication, root ownership/import boundary, image resizing/alpha/idempotent output |
| Rivium + Apalis process smoke | Passed: 3 photos queued while paused, forced kill, reopen/resume, 3 completed jobs, no duplicate outputs |
| Desktop JVM with `-Xcheck:jni` | Passed: two start/RPC/pause/stop generations and malformed-request handling |
| Android Rust compilation | Passed for arm64-v8a and x86_64 |
| Android app and test APK compilation | Passed |
| Android lint | Passed, 0 errors; nonblocking dependency/style/backup warnings remain |
| APK signing | Verified, APK Signature Scheme v2 |
| APK alignment | 16 KB page alignment check passed; native load segments use 0x4000 alignment |
| Android emulator execution | Not verified: this workspace has no KVM; software-emulated Android stalled during boot |
| Android instrumentation | Test APK compiled; execution pending on a working emulator or phone |
| Physical phone / organiser bonus | Pending; see `PHONE_TEST.md` |
| Challenge PR and demonstration video | Not submitted |

APK: `PichaLite-0.1.0-debug.apk`

SHA-256:

```text
39f082cf483d702ee7739b4f2e4e8b171bcce3402c478703468ab77e83472eba
```

The Android Share Sheet, notification behavior, screen layout and process-kill
recovery still require runtime observation on Android. Desktop and compilation
checks do not establish those results. The included GitHub workflow can run the
instrumented test on an accelerated emulator once a repository is connected.
