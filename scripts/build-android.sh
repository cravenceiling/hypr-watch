#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/.." && pwd)"
new_key=false
for arg in "$@"; do
 case "$arg" in
  --new-key) new_key=true;;
  *) printf 'Usage: %s [--new-key]\n' "$0" >&2; exit 2;;
 esac
done
state="${XDG_STATE_HOME:-$HOME/.local/state}"
keydir="$state/hypr-watch"
# An APK signed with another key cannot update the installed app. Uninstalling it deletes its
# private data, the watch pairing key included, so a new key is made only when asked for.
if [[ ! -f "$keydir/signing.keystore" ]] && ! $new_key; then
 {
  printf 'No signing key at %s.\n' "$keydir/signing.keystore"
  if [[ -f "$state/redmi-notes/signing.keystore" ]]; then
   printf 'The key from before the rename is at %s. To keep updating the installed app, move it:\n' "$state/redmi-notes/signing.keystore"
   printf '  mkdir -p %q && mv %q %q\n' "$keydir" "$state/redmi-notes/signing.keystore" "$keydir/signing.keystore"
  fi
  printf 'To make a new key, run again with --new-key. Its APK can only be installed after uninstalling the app, which deletes its notes and its watch pairing key.\n'
 } >&2
 exit 1
fi
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
jdk="${JAVA_BUILD_HOME:-${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}}"
bt="$sdk/build-tools/36.0.0"
android_jar="$sdk/platforms/android-36/android.jar"
build="$root/build/android"
source "$root/scripts/kotlin.sh"
rm -rf "$build/dex"
mkdir -p "$build/dex"
# -no-jdk: java.* resolves against android.jar, so APIs missing on Android fail at compile time.
kotlinc -no-jdk -no-reflect -jvm-target 17 -Werror -classpath "$android_jar" -d "$build/classes.jar" "$root"/android/src/local/hyprwatch/*.kt
"$bt/aapt2" link -o "$build/unsigned.apk" -I "$android_jar" --manifest "$root/android/AndroidManifest.xml" -A "$root/android/assets" --proguard "$build/manifest-rules.pro"
# R8 shrinks kotlin-stdlib to what the app uses; manifest components come from aapt2's rules.
"$jdk/bin/java" -cp "$bt/lib/d8.jar" com.android.tools.r8.R8 --release --min-api 26 --no-data-resources --lib "$android_jar" \
 --pg-conf "$root/android/proguard-rules.pro" --pg-conf "$build/manifest-rules.pro" --pg-map-output "$build/mapping.txt" \
 --output "$build/dex" "$build/classes.jar" "$kotlin_stdlib"
python - "$build" <<'PY'
from pathlib import Path
import sys,zipfile
p=Path(sys.argv[1])
with zipfile.ZipFile(p/'unsigned.apk','a',zipfile.ZIP_DEFLATED) as z:
 for dex in (p/'dex').glob('*.dex'):z.write(dex,dex.name)
PY
"$bt/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"
mkdir -p "$keydir"
chmod 700 "$keydir"
if [[ ! -f "$keydir/signing.keystore" ]]; then
 "$jdk/bin/keytool" -genkeypair -keystore "$keydir/signing.keystore" -storepass local-development -keypass local-development -alias local -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Local Hypr Watch'
 chmod 600 "$keydir/signing.keystore"
fi
JAVA_HOME="$jdk" "$bt/apksigner" sign --ks "$keydir/signing.keystore" --ks-pass pass:local-development --key-pass pass:local-development --out "$build/hypr-watch.apk" "$build/aligned.apk"
"$bt/apksigner" verify "$build/hypr-watch.apk"
printf 'Built %s\n' "$build/hypr-watch.apk"
