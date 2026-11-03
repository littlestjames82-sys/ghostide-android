#!/usr/bin/env python3
"""I20 build gate: the four version spots, VERSION.json and
the changelog's top entry must all agree, or the build
refuses to start. Catches the classic half-bumped release.

Spots:
  ghost-ide/server.py            APP_VERSION = "X"
  ghost-ide/VERSION.json         "latest": "X"
  ghost-ide/web/changelog.json   entries[0].version == X
  ghostide-apk-build AndroidManifest.xml  versionName + code
  ghostide-apk-build IdeServer.java       VERSION = "X"

versionCode has no second source to agree with; it is
only required to be present and numeric.
"""
import json
import re
import sys
from pathlib import Path

WS = Path.home() / "workspace"


def fail(msg):
    print("VERSION GATE: REFUSING TO BUILD - " + msg)
    sys.exit(1)


server_py = (WS / "ghost-ide" / "server.py").read_text()
m = re.search(r'APP_VERSION = "([^"]+)"', server_py)
if not m:
    fail("APP_VERSION not found in server.py")
version = m.group(1)

vjson = json.loads((WS / "ghost-ide" / "VERSION.json")
                   .read_text())
if vjson.get("latest") != version:
    fail("VERSION.json latest is %r, server.py says %r"
         % (vjson.get("latest"), version))

changelog = json.loads(
    (WS / "ghost-ide" / "web" / "changelog.json").read_text())
top = (changelog.get("entries") or [{}])[0].get("version")
if top != version:
    fail("changelog top entry is %r, server.py says %r"
         % (top, version))

manifest = (WS / "ghostide-apk-build" / "app" / "src"
            / "main" / "AndroidManifest.xml").read_text()
mv = re.search(r'android:versionName="([^"]+)"', manifest)
mc = re.search(r'android:versionCode="(\d+)"', manifest)
if not mv or mv.group(1) != version:
    fail("AndroidManifest versionName is %r, server.py "
         "says %r" % (mv.group(1) if mv else None, version))
if not mc:
    fail("AndroidManifest versionCode missing/not numeric")

java = (WS / "ghostide-apk-build" / "app" / "src" / "main"
        / "java" / "com" / "ghostide" / "app"
        / "IdeServer.java").read_text()
mj = re.search(r'static final String VERSION = "([^"]+)"',
               java)
if not mj or mj.group(1) != version:
    fail("IdeServer VERSION is %r, server.py says %r"
         % (mj.group(1) if mj else None, version))

print("VERSION GATE: all five sources agree on %s "
      "(versionCode %s)" % (version, mc.group(1)))
