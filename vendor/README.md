# Rivium Android file-lock compatibility

`rivium-util/` is the published `rivium-util` 0.2.0 crate from
https://static.crates.io/crates/rivium-util/rivium-util-0.2.0.crate
(SHA-256 `ada9f4aa85b4b82472118ebbc9e9b8dc708440bf21258d68c7f402954db6117d`).
Its MIT and Apache-2.0 licenses are retained. Cargo uses it through
`[patch.crates-io]`; all Rivium versions remain pinned at 0.2.0.

The only source change is in `src/log/files.rs`: on Android, use the existing
`fs2` 0.4.3 dependency to acquire and release the logger's exclusive file lock.
Rust 1.94's standard `File::try_lock` returns Unsupported on Android, causing
Rivium startup to return `CantCreate` before application services run.
Other platforms retain the upstream implementation. Locking is never skipped.

The upstream issue is https://github.com/rust-lang/rust/issues/148325.
The Android instrumentation test exercises actual Rivium startup, processing
and repeated stop/start, including release and reacquisition of these locks.
