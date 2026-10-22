#!/bin/bash
# GhostIDE standalone Android APK - house manual recipe (aapt2 + javac
# + d8 + zipalign + apksigner; sandbox Gradle has no network).
# Framework-only Java: NO AndroidX, NO Gradle.
#
# The app is fully on-device:
# - Web IDE frontend copied from ~/workspace/ghost-ide/web (built bundle)
# - In-app HTTP server (IdeServer) implementing the same /api contract
# - llama.cpp engine via the prebuilt JNI bridge + libs from the
#   MrGhosty on-device build (~/workspace/ghostbot/phone/native/libs)
# - Model (Qwen2.5-Coder-1.5B Q4_K_M, 986,048,800 bytes) is NOT in the
#   APK; the app downloads it once into private storage.
set -e
BT=~/workspace/android-sdk/build-tools/36.0.0
AJAR=~/workspace/android-sdk/platforms/android-36/android.jar
JDK=~/workspace/jdk17
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SRC=~/workspace/ghostide-apk-build/app/src/main
OUT=~/workspace/ghostide-apk-build/out
APKDIR=~/workspace/ghostide-apk
NATIVE_LIBS=~/workspace/ghostbot/phone/native/libs/arm64-v8a
rm -rf "$OUT"; mkdir -p "$OUT/gen" "$OUT/classes" "$APKDIR" "$OUT/apkroot/lib/arm64-v8a"

echo '[0/8] generating launcher icons'
python3 ~/workspace/ghostide-apk-build/make_icons.py

echo '[0.5/8] copying web IDE bundle into assets/www'
mkdir -p "$SRC/assets/www"
cp ~/workspace/ghost-ide/web/index.html "$SRC/assets/www/"
cp ~/workspace/ghost-ide/web/style.css "$SRC/assets/www/"
cp ~/workspace/ghost-ide/web/app.js "$SRC/assets/www/"
cp ~/workspace/ghost-ide/web/classroom.html "$SRC/assets/www/"
cp ~/workspace/ghost-ide/web/changelog.json "$SRC/assets/www/"
mkdir -p "$SRC/assets/www/packs"
cp ~/workspace/ghost-ide/web/packs/*.json "$SRC/assets/www/packs/"
ls -lh "$SRC/assets/www" "$SRC/assets/www/packs"

echo '[0.6/8] staging bundled Python stdlib asset (Roadmap G9)'
cp ~/workspace/ghostide-apk-build/python-runtime/stage/assets/pyhome.zip "$SRC/assets/pyhome.zip"
ls -lh "$SRC/assets/pyhome.zip"

echo '[0.7/8] staging git templates asset (Roadmap G11)'
# stage/ is produced by python-runtime/assemble.py THEN
# python-runtime/assemble_git.py (order matters).
cp ~/workspace/ghostide-apk-build/python-runtime/stage/assets/git-templates.zip "$SRC/assets/git-templates.zip"
ls -lh "$SRC/assets/git-templates.zip"

echo '[0.8/8] staging offline reference docs (Roadmap G28)'
cp ~/workspace/ghost-ide/refdocs.json "$SRC/assets/refdocs.json"
ls -lh "$SRC/assets/refdocs.json"

echo '[0.9/8] staging ecosystem tools (Roadmap ECO E1+E2)'
# The audited phone-safe bundle (E2): every tool whose suite
# passed the vendoring gate (ghost-ide/tools/eco_bundle_gate.py)
# plus GhostSlice as the shared library. eco.zip holds the
# package trees + eco-manifest.json (version / test counts /
# sizes from the gate state); IdeServer extracts it on first
# use and runs tools with the bundled interpreter.
python3 - <<'PYEOF'
import json, sys, zipfile
from pathlib import Path
sys.path.insert(0, str(Path.home() / "workspace/ghost-ide/tools"))
from eco_bundle_gate import BUNDLED, LIBRARY
state_path = Path.home() / "workspace/ghost-ide/tools/eco-bundle-state.json"
state = json.loads(state_path.read_text())["tools"] if state_path.exists() else {}
out = Path.home() / "workspace/ghostide-apk-build/app/src/main/assets/eco.zip"
count = 0
manifest = {}
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for tool in LIBRARY + BUNDLED:
        src = Path.home() / "workspace" / tool / "src"
        for f in sorted(src.rglob("*")):
            if not f.is_file():
                continue
            rel = f.relative_to(src)
            if any(p.endswith(".egg-info") or p == "__pycache__"
                   for p in rel.parts):
                continue
            if f.suffix in (".pyc", ".pyo"):
                continue
            z.write(f, str(rel))
            count += 1
        info = state.get(tool, {})
        manifest[tool] = {"version": info.get("version", "?"),
                          "tests": info.get("tests_passed", 0),
                          "bytes": info.get("src_bytes", 0),
                          "library": tool in LIBRARY}
    z.writestr("eco-manifest.json", json.dumps(manifest, indent=1))
print(f"eco.zip: {count} python files, {len(manifest)} packages")
missing = [t for t in LIBRARY + BUNDLED if t not in state]
if missing:
    print("WARNING: no gate state for", missing, "- run eco_bundle_gate.py")
PYEOF
ls -lh "$SRC/assets/eco.zip"

echo '[1/8] compiling resources'
"$BT/aapt2" compile --dir "$SRC/res" -o "$OUT/compiled_res.zip"

echo '[2/8] linking'
"$BT/aapt2" link -o "$OUT/base.apk" \
  -I "$AJAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  -A "$SRC/assets" \
  --java "$OUT/gen" \
  --min-sdk-version 24 --target-sdk-version 36 \
  "$OUT/compiled_res.zip"

echo '[3/8] compiling java'
find "$OUT/gen" "$SRC/java" -name '*.java' > "$OUT/sources.txt"
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -bootclasspath "$AJAR" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo '[4/8] dexing'
"$BT/d8" --min-api 24 --output "$OUT" \
  $(find "$OUT/classes" -name '*.class' | tr '\n' ' ')

echo '[5/8] adding dex + native libs'
cp "$NATIVE_LIBS"/*.so "$OUT/apkroot/lib/arm64-v8a/"
echo '[5.5/8] adding bundled Python runtime libs (Roadmap G9)'
cp ~/workspace/ghostide-apk-build/python-runtime/stage/lib/*.so "$OUT/apkroot/lib/arm64-v8a/"
ls -lh "$OUT/apkroot/lib/arm64-v8a" | head -8
ls "$OUT/apkroot/lib/arm64-v8a" | wc -l
cd "$OUT" && "$JDK/bin/jar" uf base.apk classes.dex && cd - >/dev/null
cd "$OUT/apkroot" && "$JDK/bin/jar" uf "$OUT/base.apk" lib && cd - >/dev/null

echo '[6/8] zipalign'
"$BT/zipalign" -f 4 "$OUT/base.apk" "$APKDIR/ghostide-unsigned.apk"

echo '[7/8] signing'
if [ ! -f ~/workspace/ghostide-apk/debug.keystore ]; then
  "$JDK/bin/keytool" -genkeypair -keystore ~/workspace/ghostide-apk/debug.keystore \
    -alias ghostide -keyalg RSA -keysize 2048 -validity 10950 \
    -storepass ghostide -keypass ghostide \
    -dname "CN=GhostIDE Debug, OU=Ghost Developer Studio, O=Ghost Developer Studio, C=US"
fi
"$BT/apksigner" sign --ks ~/workspace/ghostide-apk/debug.keystore \
  --ks-pass pass:ghostide --key-pass pass:ghostide \
  --out "$APKDIR/ghostide.apk" "$APKDIR/ghostide-unsigned.apk"

echo '[8/8] verify'
"$BT/apksigner" verify --print-certs "$APKDIR/ghostide.apk" | head -3
cp "$APKDIR/ghostide.apk" ~/workspace/your_files/ghostide.apk
cp "$APKDIR/ghostide.apk" "$APKDIR/ghostide-v1.0.1.apk"
ls -la "$APKDIR/ghostide.apk" ~/workspace/your_files/ghostide.apk
echo BUILD-OK
