#!/bin/bash
# Installerar Android SDK i /opt/android-sdk för en fjärrsession (CLAUDE.md → Bygg & test).
# Körs vid behov, inte av session-start-hooken (≈450 MB). Idempotent.
set -euo pipefail
sdk=/opt/android-sdk
repo="$(cd "$(dirname "$0")/../.." && pwd)"
# Gradle hittar SDK:n via local.properties (gitignorerad).
write_sdk_dir() {
  if ! grep -qs '^sdk.dir=' "$repo/local.properties"; then echo "sdk.dir=$sdk" >> "$repo/local.properties"; fi
}
if [ -x "$sdk/cmdline-tools/latest/bin/sdkmanager" ] && [ -d "$sdk/licenses" ] && [ -d "$sdk/platforms/android-37.0" ]; then
  write_sdk_dir
  echo "Android SDK finns redan: $sdk"
  exit 0
fi
mkdir -p "$sdk/cmdline-tools"
tmp=$(mktemp -d)
curl -sSfo "$tmp/clt.zip" https://dl.google.com/android/repository/commandlinetools-linux-9862592_latest.zip
unzip -q "$tmp/clt.zip" -d "$tmp"
rm -rf "$sdk/cmdline-tools/latest" && mv "$tmp/cmdline-tools" "$sdk/cmdline-tools/latest" && rm -rf "$tmp"
yes | "$sdk/cmdline-tools/latest/bin/sdkmanager" --licenses >/dev/null 2>&1 || true
"$sdk/cmdline-tools/latest/bin/sdkmanager" "platform-tools" "platforms;android-37.0" "build-tools;36.0.0" >/dev/null
grep -q 'ANDROID_HOME=/opt/android-sdk' ~/.bashrc 2>/dev/null || echo 'export ANDROID_HOME=/opt/android-sdk ANDROID_SDK_ROOT=/opt/android-sdk' >> ~/.bashrc
write_sdk_dir
echo "Android SDK installerad: $sdk"
