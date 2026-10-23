# PichaLite

**Your photos. Lighter.** An offline Android photo studio for the
[Rivium + Apalis pocket backend challenge](https://github.com/apalis-dev/rivium-challenge).

Select photos, or share a batch from Gallery to PichaLite. A Rust worker resizes
them to a maximum edge of 1600 pixels and encodes JPEG at quality 82. See the
before/after sizes, pause your queue, and share finished photos to another app.
Originals remain untouched. There is no account, cloud backend or paid API.

## Challenge requirements

| Requirement | Implementation |
|---|---|
| Rivium + Apalis | Rivium owns the service; Apalis executes `PhotoJob` tasks |
| Desktop first | `cargo run` launches the same app and worker |
| Android JNI | Rivium exports lifecycle methods; JSON JNI calls import/list/control jobs |
| Durable jobs | SQLite queue and visible photo row are committed in one transaction |
| Android feature | Gallery Share Sheet receiver, document picker, progress/results notifications |
| Submission media | Record the phone walkthrough in `docs/PHONE_TEST.md` before submitting |

## Run on a laptop

Install Rust via [rustup](https://rustup.rs). The checked-in toolchain file selects
**1.94.0**. The current SQLite dependency needs 1.94 even though Rivium's own
minimum is 1.92. The vendored Rivium utility patch keeps logger file locking
working on Android with this toolchain; see [patch details](vendor/README.md).

```bash
cargo run --locked -- --root ./demo-data
```

Leave it running. Copy JPEG, PNG or WebP images into `demo-data/inbox/` in another
terminal. The scanner imports them, the worker reports completion, and the
results appear in `demo-data/output/`. Copy each file completely before moving
it into the inbox; filenames ending in `.part` are ignored. Press Ctrl-C to stop.
Run the same command again to continue the saved queue.

## Build Android

Requirements: Linux, Bash, JDK 17, Android SDK platform/build tools 35, NDK
**27.2.12479018**, and Rust. The script also supports macOS with Bash 4+.

```bash
export ANDROID_HOME=/path/to/android-sdk
# One-time SDK setup, using the SDK's sdkmanager:
sdkmanager 'platforms;android-35' 'build-tools;35.0.0' 'ndk;27.2.12479018'
bash scripts/build-android.sh arm64-v8a x86_64
```

Install `android/app/build/outputs/apk/debug/app-debug.apk`. This is a signed
debug APK for the challenge, not a Play Store release. ARM64 is for most phones;
x86_64 supports the emulator. Android 8.0 / API 26 or newer is required.
32-bit ARM and x86 devices are not supported by this APK.

Open PichaLite and choose up to 30 photos, each up to 40 MB. JPEG, PNG and WebP
are supported; HEIC, video and animated-image preservation are outside this
first version. Output is JPEG, transparency is flattened onto white, and EXIF
orientation is applied before resizing. Very large decoded images are rejected
to bound memory use. Some already-small inputs may grow after conversion; the
app displays actual sizes and does not claim savings for those files.

## How it works

- Kotlin copies incoming content URIs into private storage through a `.part`
  file, syncs it, and renames it before enqueuing. Temporary Gallery permissions
  are no longer needed after import.
- Rivium starts an Apalis worker using SQLite with WAL and FULL synchronous
  durability. No HTTP server or external queue runs on the phone.
- An exclusive root lock permits one local engine. Startup recovers interrupted
  leases and clears stale worker registrations for this dedicated queue.
- One photo runs at a time. Apalis retries failures up to three attempts. Failed
  jobs remain visible and can be retried manually.
- Every job has a stable output filename. Output is written through a synced
  temporary file and an atomic rename; completed work is safe to execute again.
- Pause state persists. Pausing blocks new work; an image already being processed
  can finish. Android uses a foreground service while work is pending. Closing
  or force-stopping the process does not guarantee continued execution: reopening
  the app resumes the durable queue. Uninstalling or clearing app data removes it.
- Notifications need permission on Android 13+. Processing still works if that
  permission is declined. Finished files are shared through a restricted
  FileProvider, not public filesystem paths.

## Versions

Rust 1.94.0; Rivium / rivium-host / rivium-jni 0.2.0; Apalis / apalis-sqlite
1.0.0; SQLx 0.9.0; image 0.25.9; JNI RPC binding 0.21.1; Kotlin 2.1.20;
Compose BOM 2025.04.01; Android Gradle Plugin 8.9.2; Gradle 8.11.1;
NDK 27.2.12479018. `Cargo.lock` pins the transitive Rust dependencies; the Gradle
wrapper pins and verifies its distribution.

## Validation

```bash
cargo fmt --check
cargo clippy --locked --all-targets --all-features -- -D warnings
cargo test --locked --all-features
cargo build --locked --all-features
python3 -m pip install Pillow
python3 scripts/smoke.py

# Desktop JVM: use a full JDK 17, not only a JRE.
mkdir -p target/jvm
javac -d target/jvm android/app/src/main/java/ke/co/pichalite/{PichaRuntime,NativeJobs}.java scripts/JniSmoke.java
java -Xcheck:jni -Djava.library.path=target/debug -cp target/jvm JniSmoke target/jni-smoke

# With an emulator/phone connected, after building native libraries:
cd android
./gradlew connectedDebugAndroidTest
```

The process smoke test queues three photos while paused, kills the process,
reopens and finishes all three, then reopens again to detect duplicate outputs.
The instrumented Android test exercises the real JNI/Rivium/Apalis image worker
and checks that pause state and jobs survive a runtime restart. A desktop JVM
check alone does not establish Android device compatibility.

`.github/workflows/build.yml` prepares Rust checks, Android lint, a downloadable
debug APK (three-day retention), and an emulator instrumentation check when this
source is placed in a GitHub repository. Local test results and the physical
phone checklist belong in `docs/PHONE_TEST.md`.

## Scope

This first version focuses on offline batch compression and durable execution.
It does not include watermarking, AI processing, automatic cloud sync or a
permanent Android background daemon. Snapshot history currently returns the
latest 500 photos. A production version should add history cleanup, broader
device testing and richer photo controls.

## Submission

Create a repository or fork, include this source and README, attach a short
video/GIF of it working, and open a PR to the challenge repository. A PR has not
been opened automatically. Record physical-phone results before claiming the
real-device bonus; an emulator does not qualify for that bonus.

MIT licensed. See `LICENSE`.
