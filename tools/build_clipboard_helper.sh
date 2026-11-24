#!/usr/bin/env bash
set -euo pipefail

ROOT="\${GITHUB_WORKSPACE:-.}"
SDK="\${ANDROID_HOME:-/usr/local/lib/android/sdk}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-35/android.jar"
BUILD="$ROOT/.clipboard-helper-build"

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex" "$BUILD/smali"

if [ ! -f "$ANDROID_JAR" ]; then
  SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
  set +o pipefail
  yes | "$SDKMANAGER" --licenses >/dev/null
  set -o pipefail
  "$SDKMANAGER" "platforms;android-35" "build-tools;35.0.0"
fi

javac -source 7 -target 7 -encoding UTF-8 \
  -classpath "$ANDROID_JAR" \
  -d "$BUILD/classes" \
  tools/java-stubs/com/android/inputmethod/latin/DictionaryEditor.java \
  tools/java/com/android/inputmethod/latin/ClipboardOrganizer.java

# The stub exists only for javac's symbol resolution; the app already contains this class.
rm -f "$BUILD/classes/com/android/inputmethod/latin/DictionaryEditor.class"

"$BT/d8" --min-api 8 --lib "$ANDROID_JAR" \
  --output "$BUILD/dex" \
  $(find "$BUILD/classes" -type f -name '*.class' -print)

java -cp apktool.jar org.jf.baksmali.Main d "$BUILD/dex/classes.dex" -o "$BUILD/smali"

DEST="decoded/smali/com/android/inputmethod/latin"
mkdir -p "$DEST"
find "$BUILD/smali/com/android/inputmethod/latin" -type f -name 'ClipboardOrganizer*.smali' \
  -exec cp {} "$DEST/" \;

test -f "$DEST/ClipboardOrganizer.smali"
echo "CLIPBOARD_HELPER_CLASSES=$(find "$DEST" -maxdepth 1 -name 'ClipboardOrganizer*.smali' | wc -l)"
