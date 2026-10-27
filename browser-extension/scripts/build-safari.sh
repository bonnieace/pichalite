#!/usr/bin/env bash
set -euo pipefail
extension_root="$(cd "$(dirname "$0")/.." && pwd)"
project_dir="${1:-$extension_root/dist/safari-xcode}"
if [[ "$(uname -s)" != Darwin ]]; then
  echo 'Safari packaging requires macOS with Xcode installed.' >&2
  exit 1
fi
if [[ -e "$project_dir" ]]; then
  echo 'Choose a new output directory; the existing Xcode project will not be overwritten.' >&2
  exit 1
fi
python3 "$extension_root/scripts/package.py" --target safari
xcrun safari-web-extension-converter "$extension_root/dist/safari" \
  --project-location "$project_dir" --app-name PichaLite \
  --bundle-identifier ke.co.pichalite.photos --copy-resources --no-open
echo "Safari Xcode project created at $project_dir. Select your signing team in Xcode to build/distribute it."
