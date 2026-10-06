#!/usr/bin/env python3
"""
patch-aar-bufferpool.py — TAHAP 1 (Task 10)

Turunkan ukuran buffer go-socks5 bufferpool di libgojni.so (AAR wptest)
dari 256 KB -> 32 KB, TANPA rebuild Go (source asli /dev/shm/wptest-lib
tidak tersedia lagi).

Target (diverifikasi via .gopclntab + llvm-objdump):
  github.com/pkok1099/wpmulti.(*MultiTun).newSocks5Server
    0x6b8a54: orr x1, xzr, #0x40000   (b26e03e1) -> field `size` struct pool
    0x6b8a80: orr x2, xzr, #0x40000   (b26e03e2) -> captured size closure
              NewPool.func2 (make([]byte, 0, size) -> buffer io.CopyBuffer)
  Pengganti: movz x1/x2, #0x8000 (0xD2900001 / 0xD2900002) — 32 KB,
  semantik identik (konstanta positif, zero-extended).

File offset = vaddr - 0x1000 (PT_LOAD teks: p_vaddr 0x40c810, p_offset 0x40b810).
Instruksi lain di seluruh .so yang memuat 0x40000 TIDAK disentuh (3 total,
1 milik fungsi lain).
"""
import shutil
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

AAR_IN = Path("/home/z/my-project/repo-v2/wptest/app/libs/wpmulti.aar")
WORK = Path("/home/z/my-project/repo-v2/wpmulti-patch")
OBJDUMP = ("/home/z/my-project/tools/android-sdk/ndk/27.0.12077973/"
           "toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-objdump")

# (vaddr, expected_insn_le, new_insn_le, keterangan)
PATCHES = [
    (0x6B8A54, 0xB26E03E1, 0xD2900001, "pool.size field -> 32KB"),
    (0x6B8A80, 0xB26E03E2, 0xD2900002, "closure NewPool.func2 capture -> 32KB"),
]
DELTA = 0x1000  # file_offset = vaddr - 0x1000 (segmen LOAD teks)


def disasm_range(so: Path, start: int, end: int) -> str:
    out = subprocess.run(
        [OBJDUMP, "-d", f"--start-address={start:#x}", f"--stop-address={end:#x}",
         str(so)], capture_output=True, text=True, check=True)
    return out.stdout


def main() -> int:
    WORK.mkdir(parents=True, exist_ok=True)
    aar_backup = WORK / "wpmulti-original.aar"
    if not aar_backup.exists():
        shutil.copy2(AAR_IN, aar_backup)
        print(f"[+] backup AAR asli -> {aar_backup}")

    src = aar_backup  # sumber selalu AAR asli (idempoten)
    so_dir = WORK / "aar-extract"
    if so_dir.exists():
        shutil.rmtree(so_dir)
    so_dir.mkdir()
    with zipfile.ZipFile(src) as z:
        z.extractall(so_dir)
    so = so_dir / "jni" / "arm64-v8a" / "libgojni.so"
    data = bytearray(so.read_bytes())
    print(f"[+] libgojni.so: {len(data)} bytes")

    # 1. Verifikasi instruksi sebelum patch
    for vaddr, expect, _new, desc in PATCHES:
        off = vaddr - DELTA
        cur = struct.unpack_from("<I", data, off)[0]
        if cur != expect:
            print(f"[!] {desc}: {vaddr:#x} berisi {cur:#010x}, "
                  f"diharapkan {expect:#010x} -> ABORT")
            return 1
        print(f"[✓] {desc}: {vaddr:#x} = {cur:#010x} (cocok)")

    # 2. Patch
    for vaddr, _expect, new, desc in PATCHES:
        off = vaddr - DELTA
        struct.pack_into("<I", data, off, new)
        print(f"[+] patch {vaddr:#x} (file {off:#x}) -> {new:#010x} ({desc})")
    so.write_bytes(data)

    # 3. Verifikasi pasca-patch (disassembly ulang)
    out = disasm_range(so, 0x6B8A50, 0x6B8A88)
    for line in out.splitlines():
        if "6b8a54" in line or "6b8a80" in line or "movz" in line or "orr" in line:
            print("    " + line.strip())
    ok = ("mov\tx1, #0x8000" in out) and ("mov\tx2, #0x8000" in out)
    if not ok:
        print("[!] verifikasi disassembly GAGAL")
        print(out)
        return 1
    print("[✓] verifikasi disassembly: kedua instruksi kini movz #0x8000")

    # 4. Sanity: pclntab tetap terbaca (tidak terganggu)
    r = subprocess.run(["/tmp/pclntab-find", str(so), "newSocks5Server"],
                       capture_output=True, text=True)
    if "NewPool.func2" not in r.stdout:
        print("[!] pclntab rusak pasca-patch:\n" + r.stdout + r.stderr)
        return 1
    print("[✓] pclntab utuh (simbol Go tetap terbaca)")

    # 5. Repack AAR (deflate, sama seperti aslinya)
    aar_out = WORK / "wpmulti-patched.aar"
    if aar_out.exists():
        aar_out.unlink()
    with zipfile.ZipFile(aar_out, "w", zipfile.ZIP_DEFLATED) as z:
        for p in sorted(so_dir.rglob("*")):
            if p.is_file():
                z.write(p, p.relative_to(so_dir).as_posix())
    print(f"[+] AAR baru: {aar_out} ({aar_out.stat().st_size} bytes)")

    # 6. Pasang ke proyek
    shutil.copy2(aar_out, AAR_IN)
    print(f"[+] terpasang -> {AAR_IN}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
