# PichaLite desktop and VS Code previews

These clients reuse `pichalite::optimise`, the Rust image encoder used by the Android app. Windows x64 is the first packaged desktop target. Neither client uploads images or adds telemetry.

## Architecture and scope

- `native/`: shared batch adapter and `pichalite-cli`, JSON stdin → one JSON result per image on stdout.
- `desktop/`: Tauri 2 desktop interface. Native file/folder dialogs, local thumbnails, adjustable JPEG quality and maximum edge, stop after the current image, per-image results and destination reveal.
- `vscode/`: Explorer/context-menu and Command Palette commands; multi-selection/folders, a bundled Windows x64 CLI, progress/cancellation and results in an Output channel.

This initial desktop/extension batch adapter calls the Rust encoder directly. It **does not start the Rivium/Apalis durable Android service**, and batches do not survive closing the desktop window or extension host. Completed files remain saved. The existing Android runtime and its branches are unchanged.

Inputs: JPEG, PNG, WebP, up to 40 MB each, with the native decoder memory limit. Output: JPEG, flattened on white, EXIF orientation applied, metadata removed. Default quality 82 and max edge 4096 px. Set max edge 40000 to preserve dimensions within supported input limits.

## Safe output behavior

Choose a destination folder. Copies go in its `PichaLite` subfolder. Original inputs are never modified, replaced or deleted. If a JPEG result is not smaller, the adapter copies the original bytes with their original extension. Output names include `-pichalite` or `-original`; collisions get a numeric suffix. Final writes use an exclusive no-clobber commit. Temporary files are cleaned on normal success/failure. An abrupt process termination can leave hidden `.pichalite-*` temporary files, but never intentionally replaces an original.

Savings are the difference between input and output sizes, not disk space reclaimed from originals. The first desktop preview processes one image at a time. Extension cancellation stops between files. Desktop cancellation stops after the current image.

## Build / run

Requires Rust 1.94.0 and Node 22+. Windows desktop builds also require the standard Tauri Windows prerequisites (MSVC C++ build tools, WebView2).

```sh
cargo test --manifest-path clients/native/Cargo.toml --locked
cargo build --manifest-path clients/native/Cargo.toml --release --locked
```

From `clients/desktop`: `npm ci && npm run dev`, or `npm run build -- --locked` for the Windows NSIS installer and desktop executable. From `clients/vscode`, copy the Windows CLI to `bin/pichalite-cli.exe`, then run `npm ci && npm test && npm run package`.

The **PichaLite Windows desktop and VS Code** GitHub workflow runs native lint/tests, the CLI against a real PNG and corrupt JPEG, desktop compilation/installer packaging, extension tests and VSIX packaging. The resulting `PichaLite-Windows-and-VSCode` artifact contains the CLI, desktop executable, setup executable and VSIX. It is a preview build, not a Marketplace publication. Artifacts expire after three days.

The installer/executables are not Authenticode signed. Signing and Marketplace publication are separate release steps. Windows GUI behavior must be tested on a real Windows session; Rust/CLI tests alone do not establish that GUI dialogs work correctly.
