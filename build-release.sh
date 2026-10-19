#!/bin/bash
# GhostIDE production release build (Roadmap G19).
# Produces, in ~/workspace/ghostide-apk/:
#   ghostide-release.aab          - Play upload bundle,
#                                   signed with the upload key
#   ghostide-universal.apk        - universal APK generated
#                                   FROM the AAB by bundletool
#                                   (what a closed-track tester
#                                   effectively installs)
# Signing material (NEVER in git):
#   ~/workspace/ghostide-apk/ghostide-upload.keystore
#   ~/workspace/ghostide-apk/release-password.txt  (chmod 600)
set -e
BT=~/workspace/android-sdk/build-tools/34.0.0
AJAR=~/workspace/android-sdk/platforms/android-34/android.jar
JDK=~/workspace/jdk17
export JAVA_HOME="$JDK"
export PATH="$JDK/bin:$PATH"
SRC=~/workspace/ghostide-apk-build/app/src/main
OUT=~/workspace/ghostide-apk-build/out
APKDIR=~/workspace/ghostide-apk
BUNDLETOOL=~/workspace/tools/bundletool/bundletool.jar
KS="$APKDIR/ghostide-upload.keystore"
PW="$(cat "$APKDIR/release-password.txt")"

./build.sh >/dev/null
echo '[release 1/5] proto-format link for the bundle module'
"$BT/aapt2" link --proto-format -o "$OUT/module-base.zip" \
  -I "$AJAR" \
  --manifest "$SRC/AndroidManifest.xml" \
  -A "$SRC/assets" \
  --min-sdk-version 24 --target-sdk-version 34 \
  "$OUT/compiled_res.zip"

echo '[release 2/5] assembling the base module zip'
python3 - "$OUT" <<'PYEOF'
import pathlib, sys, zipfile
out = pathlib.Path(sys.argv[1])
src = zipfile.ZipFile(out / "module-base.zip")
mod = zipfile.ZipFile(out / "module.zip", "w")
for item in src.infolist():
    data = src.read(item.filename)
    if item.filename == "AndroidManifest.xml":
        name = "manifest/AndroidManifest.xml"
    else:
        name = item.filename
    mod.writestr(name, data, zipfile.ZIP_DEFLATED)
mod.writestr("dex/classes.dex", (out / "classes.dex").read_bytes(),
             zipfile.ZIP_DEFLATED)
apkroot = out / "apkroot"
for so in sorted(apkroot.rglob("*.so")):
    rel = so.relative_to(apkroot)
    # AAB native libraries must be stored uncompressed.
    mod.writestr(str(rel), so.read_bytes(), zipfile.ZIP_STORED)
mod.close()
print("module.zip assembled")
PYEOF

echo '[release 3/5] bundletool build-bundle'
"$JDK/bin/java" -jar "$BUNDLETOOL" build-bundle \
  --modules="$OUT/module.zip" \
  --output="$APKDIR/ghostide-release.aab" --overwrite

echo '[release 4/5] signing the AAB with the upload key'
"$JDK/bin/jarsigner" -keystore "$KS" -storepass "$PW" \
  -keypass "$PW" -signedjar "$APKDIR/ghostide-release-signed.aab" \
  "$APKDIR/ghostide-release.aab" ghostide-upload
mv "$APKDIR/ghostide-release-signed.aab" "$APKDIR/ghostide-release.aab"

echo '[release 5/5] universal APK from the bundle (tester build)'
"$JDK/bin/java" -jar "$BUNDLETOOL" build-apks \
  --bundle="$APKDIR/ghostide-release.aab" \
  --output="$APKDIR/ghostide-universal.apks" \
  --mode=universal \
  --ks="$KS" --ks-pass="pass:$PW" \
  --ks-key-alias=ghostide-upload --key-pass="pass:$PW" \
  --overwrite
cd "$APKDIR" && rm -rf universal-extract && mkdir universal-extract \
  && cd universal-extract && "$JDK/bin/jar" xf ../ghostide-universal.apks \
  && cp universal.apk "$APKDIR/ghostide-universal.apk" && cd - >/dev/null
"$BT/apksigner" verify --print-certs "$APKDIR/ghostide-universal.apk" | head -3
ls -la "$APKDIR/ghostide-release.aab" "$APKDIR/ghostide-universal.apk"
echo RELEASE-OK
