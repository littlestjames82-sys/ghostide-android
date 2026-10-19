#!/usr/bin/env python3
"""Stage the Termux CPython tree for the GhostIDE APK (G9).

Layout produced under stage/:
  lib/    flat files for lib/arm64-v8a in the APK:
          - libpythonbin.so      the interpreter executable
          - one file per SONAME  every library the interpreter,
            its libs, or any extension module needs
          - lib-dynload modules  under their literal filenames
            (the import system finds them via PYTHONPATH =
            nativeLibraryDir)
  assets/pyhome.zip   the pure-Python stdlib (lib-dynload
          excluded), extracted on-device to filesDir/pyhome

Run after (re)downloading debs into rootfs/. Verifies the
DT_NEEDED closure over the staged set before declaring OK.
"""
import pathlib
import re
import shutil
import subprocess
import sys
import zipfile

HERE = pathlib.Path(__file__).resolve().parent
USR = HERE / "rootfs/data/data/com.termux/files/usr"
STAGE = HERE / "stage"
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so",
          "libandroid.so", "libc++.so", "libstdc++.so"}


def elf_info(path):
    out = subprocess.run(["readelf", "-d", str(path)],
                         capture_output=True, text=True).stdout
    needed = re.findall(r"NEEDED.*\[([^\]]+)\]", out)
    m = re.search(r"SONAME.*\[([^\]]+)\]", out)
    return needed, (m.group(1) if m else None)


def patch_dynstr(data, rename):
    """Rewrite DT_NEEDED/SONAME strings inside .dynstr only.

    Android's installer only extracts lib/<abi>/ entries whose
    names end in .so, so versioned SONAMEs (libcrypto.so.3)
    are renamed to end in .so and every reference in the
    staged set is patched to match. Replacements are never
    longer than the originals; the tail is NUL-padded inside
    the same string slot, so all offsets stay put."""
    import struct
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return data
    e_shoff, = struct.unpack_from("<Q", data, 0x28)
    e_shentsize, = struct.unpack_from("<H", data, 0x3A)
    e_shnum, = struct.unpack_from("<H", data, 0x3C)
    e_shstrndx, = struct.unpack_from("<H", data, 0x3E)
    sections = []
    for i in range(e_shnum):
        off = e_shoff + i * e_shentsize
        name, _typ, _flags, _addr, offset, size = struct.unpack_from(
            "<IIQQQQ", data, off)
        sections.append((name, offset, size))
    shstr_off = sections[e_shstrndx][1]
    out = bytearray(data)
    for name, offset, size in sections:
        end = shstr_off + name
        nul = data.index(b"\x00", end)
        sname = data[end:nul].decode("utf-8", "replace")
        if sname != ".dynstr":
            continue
        seg = bytes(data[offset:offset + size])
        for old, new in rename.items():
            needle = old.encode() + b"\x00"
            start = 0
            while True:
                j = seg.find(needle, start)
                if j < 0:
                    break
                rep = new.encode() + b"\x00"
                rep += b"\x00" * (len(needle) - len(rep))
                out[offset + j:offset + j + len(needle)] = rep
                start = j + len(needle)
    return bytes(out)


def main():
    if STAGE.exists():
        shutil.rmtree(STAGE)
    (STAGE / "lib").mkdir(parents=True)
    (STAGE / "assets").mkdir(parents=True)

    # Rename map: any library name that does not end in .so
    # gets truncated at ".so" (libcrypto.so.3 -> libcrypto.so).
    # All references are patched below with patch_dynstr.
    rename = {}

    def note_name(name):
        if not name.endswith(".so"):
            new = name[:name.index(".so") + 3]
            rename[name] = new

    def stage_elf(src, dest_name):
        data = pathlib.Path(src).read_bytes()
        data = patch_dynstr(data, rename)
        (STAGE / "lib" / dest_name).write_bytes(data)

    # 1. The interpreter executable, renamed as a "library" so
    # the Android installer extracts it to nativeLibraryDir.
    bin_needed_pre, _ = elf_info(USR / "bin/python3.14")
    for n in bin_needed_pre:
        note_name(n)
    stage_elf(USR / "bin/python3.14", "libpythonbin.so")

    # 2. Libraries: the transitive DT_NEEDED closure from the
    # interpreter and every extension module - one real file
    # per distinct SONAME, nothing unreachable.
    by_soname = {}
    for p in sorted((USR / "lib").glob("*.so*")):
        target = p.resolve()
        if not target.is_file():
            continue
        _, soname = elf_info(target)
        key = soname or p.name
        if key not in by_soname:
            by_soname[key] = target
    dynload = USR / "lib/python3.14/lib-dynload"
    queue = []
    bin_needed, _ = elf_info(USR / "bin/python3.14")
    queue.extend(bin_needed)
    for p in sorted(dynload.glob("*.so")):
        needed, _ = elf_info(p)
        queue.extend(needed)
    reachable = set()
    while queue:
        name = queue.pop(0)
        if name in reachable or name in SYSTEM:
            continue
        reachable.add(name)
        target = by_soname.get(name)
        if target is not None:
            needed, _ = elf_info(target)
            queue.extend(needed)
    for name in sorted(reachable):
        note_name(name)
    # Second pass now that every reachable name is known, so
    # the rename map is complete before any file is patched.
    for soname in sorted(reachable):
        target = by_soname.get(soname)
        if target is not None:
            stage_elf(target, rename.get(soname, soname))

    # 3. Extension modules under their literal filenames.
    n_ext = 0
    for p in sorted(dynload.glob("*.so")):
        stage_elf(p, p.name)
        n_ext += 1

    # 4. Pure-Python stdlib as a zip asset.
    stdlib = USR / "lib/python3.14"
    zpath = STAGE / "assets/pyhome.zip"
    n_py = 0
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(stdlib.rglob("*")):
            if not p.is_file() or p.is_symlink():
                continue
            rel = p.relative_to(stdlib)
            if rel.parts[0] == "lib-dynload":
                continue
            if "__pycache__" in rel.parts:
                continue
            z.write(p, "lib/python3.14/" + str(rel))
            n_py += 1

    # 5. Closure check over the staged set.
    staged_names = {p.name for p in (STAGE / "lib").glob("*")}
    missing = set()
    for p in sorted((STAGE / "lib").glob("*")):
        needed, _ = elf_info(p)
        for n in needed:
            if n not in SYSTEM and n not in staged_names:
                missing.add(n)
    lib_bytes = sum(p.stat().st_size
                    for p in (STAGE / "lib").glob("*"))
    print(f"staged libs: {len(staged_names)} files, "
          f"{lib_bytes / 1e6:.1f} MB (incl. {n_ext} ext modules)")
    print(f"stdlib zip: {n_py} files, "
          f"{zpath.stat().st_size / 1e6:.1f} MB")
    if missing:
        print("UNRESOLVED DT_NEEDED:", sorted(missing))
        return 1
    print("closure OK: every non-system dependency is staged")
    return 0


if __name__ == "__main__":
    sys.exit(main())
