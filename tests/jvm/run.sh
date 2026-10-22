#!/bin/bash
# JVM unit suites (no Android runtime needed):
# BkTest - backup crypto (v1 bounds + v2 AES-GCM),
# ZgTest - restore ZIP guard, PcTest - containment. Compiles the real app
# sources against an android.util.Base64 stub.
set -e
cd "$(dirname "$0")"
APP=../../app/src/main/java/com/ghostide/app
OUT=$(mktemp -d)
javac -d "$OUT" \
  android/util/Base64.java \
  "$APP/Json.java" "$APP/BackupCrypto.java" \
  "$APP/ZipGuard.java" "$APP/PathCheck.java" \
  "$APP/PairHub.java" "$APP/BackupService.java" \
  BkTest.java ZgTest.java PcTest.java PhTest.java \
  RsTest.java
java -cp "$OUT" com.ghostide.app.BkTest
java -cp "$OUT" com.ghostide.app.ZgTest
java -cp "$OUT" com.ghostide.app.PcTest
java -cp "$OUT" com.ghostide.app.PhTest
java -cp "$OUT" com.ghostide.app.RsTest
echo "JVM SUITES OK"
