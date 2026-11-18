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
  android/content/Context.java \
  android/content/SharedPreferences.java \
  android/content/res/AssetManager.java \
  android/content/pm/ApplicationInfo.java \
  android/app/ActivityManager.java \
  android/system/Os.java \
  "$APP/Json.java" "$APP/BackupCrypto.java" \
  "$APP/ZipGuard.java" "$APP/PathCheck.java" \
  "$APP/PairHub.java" "$APP/BackupService.java" \
  "$APP/GitSupport.java" "$APP/GitTokenStore.java" \
  "$APP/Models.java" "$APP/PhoneGuard.java" \
  "$APP/VoiceSupport.java" "$APP/TaskStore.java" \
  BkTest.java ZgTest.java PcTest.java PhTest.java \
  RsTest.java GtTest.java PgTest.java VcTest.java \
  TkTest.java
java -cp "$OUT" com.ghostide.app.BkTest
java -cp "$OUT" com.ghostide.app.ZgTest
java -cp "$OUT" com.ghostide.app.PcTest
java -cp "$OUT" com.ghostide.app.PhTest
java -cp "$OUT" com.ghostide.app.RsTest
java -cp "$OUT" com.ghostide.app.GtTest
java -cp "$OUT" com.ghostide.app.PgTest
java -cp "$OUT" com.ghostide.app.VcTest
java -cp "$OUT" com.ghostide.app.TkTest
echo "JVM SUITES OK"
