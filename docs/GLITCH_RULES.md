# GLITCH_RULES — aturan sistem glitch (WAJIB dibaca sebelum menyentuh GlitchText/GlitchDropdown)

Status: **BEKU sejak cy8, diperkuat cy10.2–cy10.3**. Efek glitch adalah
identitas UI yang sudah disetujui user.

## Prinsip

1. **EVENT-DRIVEN, target eksplisit** — "sesuatu yang berubah,
   sesuatu itulah yang glitch". Pemanggil menentukan root sekecil
   mungkin (elemen yang berubah, bukan layar). Contoh: pilih mode IP
   di satu baris = glitch HANYA chip itu (MEDIUM), label baris diam.
2. **Glitch = lapisan visual, TIDAK PERNAH memblokir input/state** —
   listener state jalan lebih dulu, efek menyusul; touch listener
   press mengembalikan `false` (ripple + click tetap normal).
3. **logView 100% bebas glitch** — `walk/collect/burst/fragmen`
   melewatkan `R.id.logView` (kejelasan log > estetika).
4. **Tidak boleh layout shift / artifact kotak**:
   - Tidak pernah `setText` yang mengubah panjang/ukuran pada elemen
     layout-stabil; korupsi teks lewat `setSpan` saja (EditText: cursor/
     IME/selection tak tersentuh).
   - Tidak pernah alpha < 1 pada view ber-elevation (`guardElevation`
     menolkan elevation selama efek; pill nav TIDAK PERNAH di-alpha —
     hanya `glitchJitter` translationX).
   - Semua langkah lewat `step()`/`stepFinish` — rantai berakhir PASTI
     di baseline; `cancelFor/stop/purgeEffects` memulihkan semua state.
5. **Satu fungsi pusat keputusan** — `GlitchText.isGlitchEnabled()`
   dipanggil SAAT EVENT TERJADI (bukan di-cache). Mode:
   `AUTO` (ikuti skala animator sistem), `ALWAYS_ON` (default),
   `OFF` (mati + purge state).
6. **Penggerak waktu = Handler/Choreographer sendiri** — bukan Animator
   sistem, agar `ALWAYS_ON` tetap jalan saat animator scale sistem = 0,
   dan `AUTO` mati rapi saat 0.
7. **PENDING (teks dasar restore) divalidasi** (cy10.3): hanya dipakai
   bila karakter teks masih sama. Bila aplikasi mengganti teks di tengah
   kilatan (mis. label chip setelah pilih mode), PENDING basi dibuang —
   label baru tidak boleh tertimpa teks lama.
8. **Frekuensi/timing/intensitas BEKU**: hierarki MINOR/MEDIUM/MAJOR,
   wander 950–1500ms (2–5 target, jeda 1,5 dtk pasca-event), restore
   120–190ms, pulse dropdown 380–650ms, denyut/parameter span — jangan
   diubah tanpa persetujuan user eksplisit.

## Yang sudah dicoba dan DIBATALKAN (jangan diulang)

| # | Ide | Dibatalkan karena |
|---|-----|-------------------|
| B1 | Filter `isShown()` pada pool ambient TICK (hemat CPU) | Mengubah statistik pemilihan target → frekuensi ambient yang TERLIHAT naik (view tersembunyi tak pernah dipilih lagi). Guard `isShown` dipindahkan ke `burst()` saja (2010.2) — distribusi pick tidak berubah. |
| B2 | Memindahkan `scheduleRestore` ke dalam cabang burst | Mengubah pola pemotongan umur span saat beririsan dengan event → timing restore terlihat beda. Komentar peringatan ada di `GlitchText.TICK`. |
| B3 | `setTextIfChanged` untuk label monitor (K1) | AOSP `TextView.setText` TIDAK punya short-circuit konten identik (terverifikasi) — flash berhenti saat nilai statis = frekuensi visual berubah. Menunggu konfirmasi user (lihat KNOWN_ISSUES K1). |
| B4 | Padding kiri checkbox +2dp (cy10.1) untuk geser box | `CompoundButton.onDraw` (AOSP 14/15/16) menggambar button drawable di x=0 view dan MENGABAIKAN padding — padding hanya menjauhkan TEKS dari box. Dikerjakan ulang di cy10.2: inset 2dp DI DRAWABLE (`cb_cyber.xml`). |
| B5 | Menutup artifact pill dengan warna/stroke | Dilarang user: akar masalah harus dihilangkan. cy10.3: seluruh mesin (FloatingToolbarLayout + MaterialShapeDrawable + elevation 6dp + bg BNV persegi) diganti struktur polos — lihat KNOWN_ISSUES. |
| B6 | Popup platform Spinner (`Spinner.performClick`) | Tidak bisa dianimasikan dengan bahasa glitch; diganti `GlitchDropdown` (cy8). |
| B7 | Toast sistem untuk notifikasi | Tampil sebagai box abu-abu gelap di atas pill (cy3) — diganti HUD in-app. |

## Checklist sebelum mengubah apa pun di glitch

- [ ] Apakah ini bug fix (perilaku salah) atau perubahan visual?
  Perubahan visual = WAJIB persetujuan user.
- [ ] Kalau bug fix: apakah perbaikan mengubah piksel pada jalur NORMAL
  (non-bug)? Bila ya, pecah lagi sampai tidak.
- [ ] `isGlitchEnabled()` dibaca saat event, bukan di-cache di field?
- [ ] Ada view ber-elevation yang kena alpha? (harus `guardElevation`
  atau pindah ke `glitchJitter`).
- [ ] Efek baru pada view dinamis: sudah `registerTree` + `cancelFor`
  saat view mati/di-recycle?
- [ ] logView tetap bersih?
- [ ] Mode `OFF` dan `AUTO + animator 0`: jalur berakhir SEKETIKA di
  state final tanpa sisa alpha/transform/shadow/overlay?
- [ ] Popup/window baru: `setAnimationStyle` guard `isGlitchEnabled`
  (jangan pernah fade platform default)?
