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
if [ -d "$sdk/platforms" ] && [ -d "$sdk/licenses" ]; then
  # Gradle hittar SDK:n via local.properties (gitignorerad).
  f=local.properties
  grep -qs '^sdk.dir=' "$f" || { [ -s "$f" ] && [ -n "$(tail -c1 "$f")" ] && echo >> "$f"; echo "sdk.dir=$sdk" >> "$f"; }
  echo "Android SDK: $sdk (Gradle kan köras i sessionen)"
else
  echo "Android SDK: saknas – installera vid behov med .claude/hooks/install-android-sdk.sh"
fi

# android-ui från GitHub Packages kräver inloggning även för läsning (ARKITEKTUR.md → Byggkonfiguration).
# Visa bara om token finns, aldrig dess innehåll.
if [ -n "${GPR_TOKEN:-}" ]; then
  echo "GPR_TOKEN: satt (android-ui kan hämtas)"
else
  echo "GPR_TOKEN: saknas – lägg GPR_USER och GPR_TOKEN (read:packages) i Claude-miljöns inställningar, annars går Gradle inte att köra"
fi

# Maven Central via Googles spegel (429 från repo.maven.apache.org i sessionen).
mkdir -p "$HOME/.gradle/init.d"
cp .claude/hooks/maven-central-mirror.init.gradle.kts "$HOME/.gradle/init.d/"
echo "Gradle: Maven Central via Googles spegel (~/.gradle/init.d/maven-central-mirror.init.gradle.kts)"
