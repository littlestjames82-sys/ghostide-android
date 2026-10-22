#!/bin/bash
# JVM unit suites (no Android runtime needed):
# BkTest - backup crypto (v1 bounds + v2 AES-GCM),
# ZgTest - restore ZIP guard. Compiles the real app
# sources against an android.util.Base64 stub.
set -e
cd "$(dirname "$0")"
APP=../../app/src/main/java/com/ghostide/app
OUT=$(mktemp -d)
javac -d "$OUT" \
  android/util/Base64.java \
  "$APP/Json.java" "$APP/BackupCrypto.java" \
  "$APP/ZipGuard.java" \
  BkTest.java ZgTest.java
java -cp "$OUT" com.ghostide.app.BkTest
java -cp "$OUT" com.ghostide.app.ZgTest
echo "JVM SUITES OK"
