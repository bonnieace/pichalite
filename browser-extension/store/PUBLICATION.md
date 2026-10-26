# Store submission package

Version 0.2.1 adds the established PichaLite photo-mark icons required for store
submission. It keeps the runtime behaviour of the 0.2.0 preview.

Build with `python3 scripts/build-icons.py` (Pillow required) and `npm run package`.
Upload the **Chromium** ZIP to Chrome Web Store; the Firefox ZIP is for Mozilla.
Both require their own publisher account and review/signing process. A submitted
item is not necessarily an approved or published item.

- Store fields, permission explanations and reviewer notes: `listing.json`.
- Public privacy page: https://pichalite.co.ke/extensions/privacy/
- Icon: `../icons/icon-128.png` (128 × 128 PNG with transparent padding).
- Screenshots: `screenshot-workspace.png` and `screenshot-results.png`, generated
  at 1280 × 800 from the actual workspace with synthetic fixture data. Never use
  personal Google Photos screenshots or represent the fixture as a live test.
- Support: https://github.com/bonnieace/pichalite/issues

## Release gate and current status

The installed extension has not completed the live Google Photos replacement
acceptance cycle documented in `../README.md`. Finish that check before treating
the extension as ready for general users. The listing retains the Preview name
and explains the current limitation explicitly.

Submission requires a signed-in publisher account. This source package has no
assigned Chrome Store listing or Mozilla submission ID. Registration, fees,
developer agreements, identity checks and signing are separate from packaging;
verify their actual account state before reporting a store publication.

When a real listing is approved/published, add its exact URL to the website's
`dist/extensions/install-config.json`. Do not fill that configuration with a
dashboard URL, a draft item, a search URL, or a fabricated extension ID.
