#!/usr/bin/env python3
"""Stage the Termux git tree for the GhostIDE APK (G11).

Run AFTER assemble.py (which rebuilds stage/ from scratch
for Python). This adds, into the same stage dirs:
  lib/libgitbin.so         the git multicall binary
  lib/libgitremotehttp.so  the curl-linked remote helper
  lib/<closure>.so         git's DT_NEEDED closure (names
                           already staged by assemble.py are
                           verified byte-identical and kept)
  assets/git-templates.zip usr/share/git-core/templates for
                           GIT_TEMPLATE_DIR on-device

On-device, IdeServer points GIT_EXEC_PATH at a directory of
symlinks (git, git-upload-pack, ... -> libgitbin.so;
git-remote-https/http -> libgitremotehttp.so), which is how
the multicall binaries get their proper argv[0] names.
"""
import pathlib
import sys
import zipfile

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from assemble import SYSTEM, USR, STAGE, elf_info, patch_dynstr


def main():
    libexec = USR / "libexec/git-core"
    seeds = [USR / "bin/git", libexec / "git-remote-http"]

    by_soname = {}
    for p in sorted((USR / "lib").glob("*.so*")):
        target = p.resolve()
        if not target.is_file():
            continue
        _, soname = elf_info(target)
        key = soname or p.name
        if key not in by_soname:
            by_soname[key] = target

    queue = []
    for s in seeds:
        needed, _ = elf_info(s)
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

    rename = {}
    for name in reachable:
        if not name.endswith(".so"):
            rename[name] = name[:name.index(".so") + 3]

    def stage_elf(src, dest_name):
        data = patch_dynstr(pathlib.Path(src).read_bytes(), rename)
        dest = STAGE / "lib" / dest_name
        if dest.exists():
            if dest.read_bytes() != data:
                print(f"WARNING: {dest_name} already staged with "
                      "different bytes - keeping the existing one")
            return
        dest.write_bytes(data)

    stage_elf(USR / "bin/git", "libgitbin.so")
    stage_elf(libexec / "git-remote-http", "libgitremotehttp.so")
    for soname in sorted(reachable):
        target = by_soname.get(soname)
        if target is not None:
            stage_elf(target, rename.get(soname, soname))

    templates = USR / "share/git-core/templates"
    zpath = STAGE / "assets/git-templates.zip"
    n = 0
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(templates.rglob("*")):
            if p.is_file() and not p.is_symlink():
                z.write(p, str(p.relative_to(templates)))
                n += 1

    staged_names = {p.name for p in (STAGE / "lib").glob("*")}
    missing = set()
    for p in sorted((STAGE / "lib").glob("*")):
        needed, _ = elf_info(p)
        for name in needed:
            if name not in SYSTEM and name not in staged_names:
                missing.add(name)
    print(f"git staged: templates zip {n} files; "
          f"stage/lib now {len(staged_names)} files")
    if missing:
        print("UNRESOLVED DT_NEEDED:", sorted(missing))
        return 1
    print("closure OK over the union staged set")
    return 0


if __name__ == "__main__":
    sys.exit(main())
