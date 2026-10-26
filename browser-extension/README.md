# PichaLite for Google Photos — 0.2.0 preview

A desktop WebExtension for selecting photos in Google Photos,
compressing them locally, uploading smaller copies, independently verifying the
copies, and reviewing originals for recoverable cleanup.

**Status:** implementation preview. Twenty automated tests and a real Chromium
end-to-end fixture pass. Current live Google Photos account, photo-link, download
menu and upload-menu semantics were inspected on 26 October 2026. The extension
has **not** completed an installed-extension replacement cycle against live Google
Photos. Validate it with disposable copies before using personal originals.

## Browser builds and installation

| Browser | Package / interface | Release status |
| --- | --- | --- |
| Chrome / Edge / Brave desktop | `0.2.0-chromium-preview.zip`; side panel when available, otherwise a workspace tab | Unpacked developer preview; store publication pending |
| Firefox 142+ desktop | `0.2.0-firefox-preview.zip`; native sidebar | Temporary developer preview; Mozilla signing/publication pending |
| Safari on Mac | `0.2.0-safari-source.zip`; workspace tab | Developer source only; macOS/Xcode packaging and Apple signing required |

The website's browser-aware `/extensions/` page chooses the appropriate preview
and shows its installation steps. Official store links can replace the preview
flow once genuine listings are available. Mobile installation is not advertised.

### Chrome, Edge and Brave

1. Extract the Chromium preview ZIP.
2. Open `chrome://extensions`, `edge://extensions` or `brave://extensions`.
3. Enable Developer mode, choose **Load unpacked**, and select the directory
   containing `manifest.json`.
4. Reload Google Photos and click PichaLite's toolbar icon. A supported native
   side panel opens; otherwise PichaLite opens its workspace tab.

### Firefox

1. Extract the Firefox preview ZIP.
2. Open `about:debugging#/runtime/this-firefox` in Firefox 142 or later.
3. Choose **Load Temporary Add-on** and select the extracted `manifest.json`.
4. Grant the requested Google Photos/media-host access and click PichaLite's
   toolbar icon to open its native sidebar.

Firefox removes temporary extensions when it restarts. A signed Mozilla build
is required for normal permanent installation. The Firefox manifest discloses
website-content/location transmission because replacement images, including
embedded EXIF GPS metadata when present, are uploaded to Google Photos. Account
identifiers and batch records stay local; there is no PichaLite telemetry.

### Safari developer build

On a Mac with Xcode installed:

```sh
cd browser-extension
bash scripts/build-safari.sh
```

The script generates a new Xcode project from `dist/safari/` without overwriting
an existing project. Open the generated project, select a signing team, and build
for your Mac. Apple signing/distribution is separate. The Safari source ZIP is
not an installable Safari app, and no App Store listing is claimed.

## Use

1. In Google Photos, click **Select photos** on PichaLite's bottom toolbar and
   click the photos you want. Click one again to deselect it. Alternatively open
   a single photo and choose **Add open photo**. Selection survives scrolling.
2. In the side panel choose **Connect tab**, then **Add to batch**.
3. Review the replacement notice, choose quality, and click **Compress & upload**.
4. Keep the panel open while running. You can instead open a dedicated PichaLite
   workspace tab before starting. A separate Photos work tab performs page
   operations; avoid interacting with it while the batch runs.
5. Review each result. Only a byte-for-byte matching replacement downloaded
   separately from Google Photos becomes **verified**.
6. Choose **Review cleanup** and approve the listed originals. Both original and
   replacement are downloaded and hashed again immediately before cleanup.

There is no automatic permanent deletion or emptying of trash. Cloud deletion
can propagate to synced devices; Google's recovery and quota behaviour still
applies. PichaLite does not promise that file-size savings equal Google storage
quota reclaimed. In particular, legacy storage-exempt photos can consume quota
when reuploaded, and an already-full account can prevent replacement uploads.

## Recovery

- **Selected / compressed / uploaded:** resume using **Compress & upload**.
- **Upload uncertain:** an upload may already exist. Choose **Reconcile**; this
  never submits the file again. If necessary, use **Link replacement** and paste
  its individual Google Photos photo URL. Exact bytes must match.
- **Review:** a cleanup precondition failed. The original was kept; reconcile
  and review again only after investigating the reason.
- **Trash uncertain:** the browser closed or the UI did not confirm cleanup.
  Inspect Google Photos' bin and the original manually. PichaLite will not retry
  the deletion automatically.
- **Failed:** inspect the error and choose **Retry** for safe pre-upload errors.
- **Forget locally:** deletes this item's local record and temporary files only.
  It does not delete, restore or otherwise modify anything in Google Photos.

Pausing waits for the current operation. Closing/reloading a processing panel
recovers its durable records next time; it cannot promise that a server-side
upload stopped. An interrupted upload or verification is always reconciled.
Multiple panels/tabs share a Web Lock so only one batch runner can operate.

## Scope and limits

- JPEG and opaque PNG inputs, at most 40 MB and 40 million pixels each.
- Browser-native local JPEG encoding; PNG outputs become JPEG. Original pixel
  dimensions stay intact. Files that do not shrink are skipped.
- Embedded EXIF is retained when present, with orientation/dimensions corrected
  and the stale thumbnail directory disconnected. Output pixels use sRGB.
- Transparent/animated PNGs, detected motion/multi-picture/HDR gain-map JPEGs,
  videos, RAW, HEIC and shared/album-specific source URLs are excluded. Detection
  cannot identify every proprietary special-photo format.
- Google-only dates, albums, favourites, comments, edit history, sharing and
  links are not transferred. Replacements are new media items.
- Verification scans at most 12 new links visible on Recently added. Heavy
  concurrent uploads or different UI rendering can require **Link replacement**.
- If Google Photos recompresses an upload, the exact-byte check fails safely:
  the original stays. There is no perceptual-only deletion fallback.
- Processing is serial to keep memory use and UI actions predictable. Larger
  batches retain temporary originals and outputs on local disk until forgotten;
  available disk space remains a practical limit.
- The normal Google Photos Download action may also save a duplicate in your
  browser's Downloads folder. The extension fetches the captured original URL
  separately for processing and does not silently cancel unrelated downloads.
- This preview supports the English Google Photos controls inspected above.
  UI changes produce a review/error, not a guessed deletion action.

## Architecture and permissions

- `content.js`: isolated selection toolbar and guarded Google Photos DOM adapter.
- `background.js`: opens the side panel and observes an explicitly armed original
  download's attachment response in its exact work tab. No blocking webRequest.
- `transport.js`: navigates the work tab, fetches original bytes, sends bounded
  upload chunks, and bridges a one-shot file input in the page's main world.
  Detached upload inputs are temporarily attached to the DOM; native picker
  methods are restored immediately after capture or after 15 seconds.
- `runner.js`: durable per-item workflow, exact-byte verification, restart
  reconciliation, and final cleanup preconditions.
- `image.js`: validated image headers, browser encoder and EXIF handling.
- `db.js`: IndexedDB records and local original/output blobs.
- `app.*`: side panel and optional workspace tab.

The packaged code uses the native Promise API namespace on Firefox/Safari, and
falls back to Chrome’s namespace on Chromium. Browser-specific manifests select
service workers, event scripts, side panels or sidebars without broadening photo
access. `browser-ui.js` uses a normal extension workspace tab when a native sidebar
is unavailable.

Permissions are restricted to `photos.google.com` and its `googleusercontent.com`
media hosts, with storage, sidePanel, scripting and observational webRequest.
On Chromium/Firefox, `unlimitedStorage` prevents normal extension storage quota/eviction from losing
working batches; it does not create physical disk space. Safari source omits this
unsupported permission and remains subject to its storage limits. No Google API keys,
OAuth application, debugger permission, compression companion, PichaLite backend,
analytics, or external encoder downloads are required. Photos are sent only to
Google Photos when uploading their replacements.

## Development and verification

Node 24+, Python 3, and a desktop Chromium browser are required.

```sh
cd browser-extension
npm ci
npm run check
npm test
npx playwright install chromium
npm run test:browser
npm run package
```

For an existing Chromium executable, set `PICHALITE_CHROMIUM=/absolute/path` when
running `npm run test:browser`. The fixture uses simulated Photos pages and
mocked Chrome extension APIs with the real shipped DOM adapter, IndexedDB,
encoder, runner and UI. It never contacts a real account. It proves the
application workflow, not installation, live authenticated download capture,
or Google Photos' server behaviour. Unit tests separately exercise the actual
background download observer with API event doubles.

The GitHub workflow runs the same fixture in Chromium, Firefox and WebKit, and
generates a Safari Xcode source project on macOS. Engine fixtures still mock
extension APIs and do not prove native extension installation or live Photos
behaviour in each browser.

The GitHub workflow validates and packages only this extension. The existing
Android/Rust workflow remains unchanged and may also run on repository pushes.

### Remaining live acceptance checks

Use five explicitly disposable, ordinary JPEG/PNG copies:

1. Load the unpacked extension and verify account detection and selection across
   scrolling/reloading, including duplicate filenames and `/u/N/` account routes.
2. Confirm the original Download response is observable in the exact work tab
   and fetchable with the existing signed-in session. Reject thumbnails/ZIPs.
3. Confirm the current web uploader accepts the bridged detached input and
   produces a fresh upload-completion notification.
4. Confirm the replacement appears in Recently added and its independently
   downloaded bytes match. Check orientation, dates and EXIF on representative
   photos; test both Google Photos upload-quality settings.
5. Approve cleanup only for these disposable originals. Confirm the actual bin
   dialog/toast matches and that each replacement remains accessible.
6. Interrupt download, upload and cleanup independently; confirm reconciliation
   and no blind retry. Repeat with an account change and insufficient storage.

Do not mark the preview production-ready until these checks pass. The fixture
screenshot is generated under `test-results/` and intentionally excluded from git.
