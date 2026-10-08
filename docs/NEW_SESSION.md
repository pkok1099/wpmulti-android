# NEW_SESSION — panduan singkat memulai session baru

Branch kerja: **`ui/floating-nav`** (semua pekerjaan UI terbaru ada di
sini; `main` tertinggal jauh). Bahasa kerja: Indonesia.

## Urutan membaca file (±15 menit, WAJIB)

1. `docs/ARCHITECTURE.md` — struktur proyek, modul, alur UI, sistem
   glitch, tema, dialog, dropdown.
2. `docs/GLITCH_RULES.md` — aturan yang BEKU + daftar ide yang sudah
   DIBATALKAN beserta alasannya (jangan diulang).
3. `docs/KNOWN_ISSUES.md` — akar penyebab bug yang sudah diperbaiki +
   item K1–K7 yang menunggu konfirmasi user + checklist test device.
4. `CHANGELOG.md` (root) — riwayat versi terkini ke bawah.
5. Riwayat commit terbaru: `git log --oneline -15`.
6. Laporan teknis mendalam (opsional): `docs/superpowers/plans/*.md`.

## Aturan inti (ringkas — detail di GLITCH_RULES.md)

- Glitch **event-driven, target eksplisit**, BEKU: jangan ubah
  intensitas/durasi/gaya/target/timing/urutan tanpa persetujuan user.
- `logView` selalu bebas glitch. `isGlitchEnabled()` dibaca SAAT EVENT.
- Tidak boleh layout shift / artifact kotak; view ber-elevation tidak
  pernah di-alpha; pill nav hanya `glitchJitter` (translationX).
- Ragu apakah suatu perubahan mengubah visual? → JANGAN ubah; catat di
  laporan sebagai "perlu konfirmasi".

## Build

```bash
cd <repo>
export JAVA_HOME=/home/z/jdk/jdk-17.0.20.1+1   # JDK 17 user-local
./gradlew assembleDebug          # debug APK
./gradlew assembleRelease        # release APK (unsigned)
```

Output: `app/build/outputs/apk/{debug,release}/`.

## Test

Tidak ada test otomatis/instrumented yang berjalan di CI lokal —
verifikasi = build sukses + audit kode (akar penyebab dari SOURCE
framework/AOSP bila perlu) + checklist test manual device di
`docs/KNOWN_ISSUES.md §C`. Pola pembuktian yang dipakai proyek ini:
**ukur/jelaskan dari source, jangan menebak** (contoh: verifikasi
`canTextInput` & `FLAG_ALT_FOCUSABLE_IM` dari source AlertController
AOSP android-15; verifikasi `CompoundButton.onDraw` mengabaikan padding
dari source AOSP 14/15/16).

## Delivery

1. Commit per topik (format pesan mengikuti riwayat:
   `fix(ui) cy10.x: ...` / `feat(ui) ...` / `perf(ui) ...` / `docs: ...`).
2. `git push origin ui/floating-nav`.
   - **PAT user bersifat sekali-pakai** — bila push ditolak (auth):
     jangan simpan token apa pun di remote/config; lakukan fallback:
3. Fallback: commit lokal → ZIP repo (`.git` disertakan) → upload ke
   uploader eksternal. Yang terverifikasi berhasil: **litter.catbox.moe**
   (72 jam). Alternatif yang pernah gagal/blocked: catbox utama,
   0x0.st, transfer.sh, bashupload, pixeldrain, uguu, temp.sh, gofile,
   file.io. Sertakan juga APK debug bila diminta. Laporkan link +
   verifikasi HTTP 200.
4. APK debug untuk user: salin ke folder `download/` workspace agent
   dengan nama versi (mis. `wpmulti-cy10.3-debug.apk`), hapus APK lama.

## Prompt-pola yang dipakai user (kalau lanjut di topik serupa)

- "Lanjutkan dari kode yang ada. Baca docs/ dan riwayat commit dulu.
  Jangan ubah intensitas, durasi, atau gaya glitch." — kontinuitas,
  bukan rewrite.
- Spesifikasi bernomor per area (mis. MODE IP / SEARCH / NAVBAR /
  DOKUMENTASI) + baris ATURAN di akhir (logView bebas glitch, tanpa
  layout shift, keyboard tetap muncul, posisi centang tidak berubah).
- "Setelah selesai: build/test jika memungkinkan, lalu git push. Jika
  push gagal, commit lokal, buat ZIP repo, upload, berikan link."
- Untuk bug visual: user melampirkan screenshot + gejala; akar penyebab
  harus dicari sampai ke source framework, dilarang menambal dengan
  warna/overlay.
