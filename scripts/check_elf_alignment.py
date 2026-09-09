#!/usr/bin/env python3
"""Fail if any native library in an APK/AAB is not 16 KiB page aligned.

Google Play requires 16 KiB ELF alignment for apps targeting Android 15+.
Usage: check_elf_alignment.py <apk-or-aab> [apk...]
Exits 0 when every lib/*/*.so has all PT_LOAD segments 16 KiB aligned,
1 otherwise (lists offenders). Reads straight from the archive; extracts nothing.
"""

import struct
import sys
import zipfile

PAGE = 16384


def check_member(zf: zipfile.ZipFile, name: str) -> list[str]:
    problems: list[str] = []
    with zf.open(name) as fh:
        header = fh.read(64)
    if len(header) < 64 or header[:4] != b"\x7fELF":
        return [f"{name}: not an ELF file"]
    is64 = header[4] == 2
    endian = "<" if header[5] == 1 else ">"
    if is64:
        (phoff,) = struct.unpack(endian + "Q", header[0x20:0x28])
        phentsize, phnum = struct.unpack(endian + "HH", header[0x36:0x3A])
        fmt = endian + "IIQQQQQQ"
    else:
        (phoff,) = struct.unpack(endian + "I", header[0x1C:0x20])
        phentsize, phnum = struct.unpack(endian + "HH", header[0x2A:0x2E])
        fmt = endian + "IIIIIIII"
    with zf.open(name) as fh:
        for i in range(phnum):
            fh.seek(phoff + i * phentsize)
            ent = fh.read(phentsize)
            if len(ent) < phentsize:
                return [f"{name}: truncated program headers"]
            fields = struct.unpack(fmt, ent)
            if is64:
                p_type, _, p_off, p_va = fields[0], fields[1], fields[2], fields[3]
                p_align = fields[7]
            else:
                p_type, p_off, p_va = fields[0], fields[1], fields[2]
                p_align = fields[7]
            if p_type != 1:  # PT_LOAD
                continue
            if p_align % PAGE != 0 or (p_va - p_off) % PAGE != 0:
                problems.append(
                    f"{name}: PT_LOAD #{i} align={p_align:#x} vaddr={p_va:#x} off={p_off:#x}"
                )
    return problems


def check_archive(path: str) -> list[str]:
    problems: list[str] = []
    with zipfile.ZipFile(path) as zf:
        libs = [n for n in zf.namelist() if n.startswith("lib/") and n.endswith(".so")]
        if not libs:
            print(f"{path}: no native libraries (16 KiB vacuously satisfied)")
            return []
        for name in sorted(libs):
            problems.extend(check_member(zf, name))
    return problems


def main(argv: list[str]) -> int:
    if len(argv) < 2:
        print(__doc__)
        return 2
    failed = False
    for path in argv[1:]:
        problems = check_archive(path)
        if problems:
            failed = True
            print(f"{path}: MISALIGNED ({len(problems)} problems)")
            for p in problems:
                print(f"  {p}")
        else:
            print(f"{path}: OK — all native libraries 16 KiB aligned")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
