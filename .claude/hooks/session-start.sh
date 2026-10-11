#!/bin/bash
set -euo pipefail

# Körs bara i fjärrsessioner (Claude på webben/telefonen) – lokala Android Studio-miljöer
# lämnas orörda.
if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

echo '{"async": true, "asyncTimeout": 300000}'

# Repots rot utifrån skriptets plats – fungerar även när sessionens projektrot är en
# föräldrakatalog med flera repon.
cd "$(dirname "$0")/../.."

# Gradle i sessionen: bara med Android SDK (CLAUDE.md → Bygg & test). Annars får CI testa.
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-/opt/android-sdk}}"
if [ -d "$sdk/platforms" ]; then
  echo "Android SDK: $sdk (Gradle kan köras i sessionen)"
else
  echo "Android SDK: saknas – installera vid behov med .claude/hooks/install-android-sdk.sh"
fi
