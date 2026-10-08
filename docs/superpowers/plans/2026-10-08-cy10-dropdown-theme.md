# cy10 — Dropdown fix akar + audit UI total + theme dialog cybercore

Tanggal: 2026-10-08 · Cabang: `ui/floating-nav`

## Masuk 1: Bug dropdown "popup setinggi satu baris"

### Akar penyebab (3 lapis, semua di GlitchDropdown.show())

1. **Pengukuran salah mode**: `lv.measure(width EXACTLY, height
   UNSPECIFIED)` — pada AOSP `ListView.onMeasure`, mode tinggi
   UNSPECIFIED **hanya mengukur satu anak** (tinggi = listPadding +
   childHeight + fading edge × 2). Nilai itulah yang diteruskan ke
   `pw.setHeight(h)` → popup muncul setinggi ±1 baris. Tinggi view
   pemicu memang tidak dipakai — tapi "jumlah item" juga tidak pernah
   benar-benar diukur.
2. **Baris popup memakai layout yang salah**: ListView memanggil
   `adapter.getView()` (bukan `getDropDownView()`), jadi baris popup
   selama ini memakai `simple_spinner_item` (layout tampilan
   tertutup, tanpa padding) — baris sempit, teks rapat.
3. **Tepi "tembus"**: fading edge vertikal ListView = gradien
   transparan di tepi atas/bawah popup — dengan tinggi yang salah,
   teks konten di belakang popup terlihat menimpa teks popup
   ("opsi terpotong atas-bawah, teks menimpa teks di belakang").

### Perbaikan (GlitchDropdown.java)

- Ukur SEMUA item: `measure(w EXACTLY, cap AT_MOST)` — AT_MOST membuat
  ListView mengukur anak sampai cap; tinggi = jumlah item, di-cap
  `min(280dp, ruang layar terbesar di bawah/atas trigger − 24dp)`
  (lantai 120dp). Tidak memakai tinggi view pemicu.
- Adapter jembatan tipis: `getView → getDropDownView` (semua metode
  delegasi + DataSetObserver) supaya baris popup = layout dropdown
  (padding lega + tipografi cybercore). State tetap milik adapter
  yang sama.
- `setVerticalFadingEdgeEnabled(false)` — tepi popup bersih.
- Item terpilih ditandai `CHOICE_MODE_SINGLE` + `setItemChecked` →
  state_activated → teks cyan (selector `spinner_item_text`).
- `GlitchPopupWindowOff` (anim 0-duration) saat efek MATI — popup
  muncul/tutup seketika, bukan fade platform default.
- Cleanup sudah bebas callback Animator sistem (semua via Handler +
  `cancelFor` di `setOnDismissListener`) — aman saat animator scale 0.
  Tidak ada clipBounds/setClipChildren/setLayerType yang diubah;
  baseline alpha/translationX/scaleX/elevation dipulihkan
  deterministik oleh stepFinish/cancelFor.

## Masuk 2: Audit UI total — hasil

### 1) UNUSED — TIDAK ADA yang aman dihapus
Script audit (scripts/audit_cy10.py) memeriksa semua layout, drawable,
anim, color, dimen, style, kelas, dan API publik GlitchText/SelfAnim:

- Semua file resource (19 drawable, 4 layout, 2 anim) dirujuk.
- Semua color/dimen dipakai (via XML atau `R.color.*`/`getColor()`).
- Semua style dipakai (Snackbar = defensif, lihat bagian 2).
- Semua kelas dirujuk (VpnTileService via manifest+binding,
  ProxyKeepaliveService via startService, dst.).
- Semua API publik GlitchText punya call-site (glitchStateChange ×5,
  glitchMajor, pageTransition, registerCustom, vanishStaggered, dst.).
- SelfAnim: spring/pulse/ofDuration semuanya dipakai MainActivity.

Kesimpulan: tidak ada resource/kode yang bisa dihapus aman. Tidak ada
pula yang meragukan — semuanya terhubung.

### 2) TERPASANG TAPI BELUM TERHUBUNG — 3 ditemukan, disambungkan
1. **Guard window-animasi dialog masih `animScale()`** (2 tempat) —
   tidak terhubung ke mode efek cy9. Dengan "Selalu aktif" + animator
   scale 0, dialog kehilangan window-anim glitch (dan justru dapat
   fade platform default karena window anim mengikuti
   window_animation_scale, bukan animator scale). → Digratis ke
   `GlitchText.isGlitchEnabled()` + varian Off 0-duration.
2. **glitchFlash (kilat warna status) masih `animScale()`** — gap
   sama. → isGlitchEnabled().
3. **Dropdown glitch memakai `adapter.getView()`** — helper dropdown
   (setDropDownViewResource) "terpasang" tapi tidak pernah dipakai
   popup. → jembatan getDropDownView.

Defensif (dibiarkan, bukan bug):
- `Widget.Wpmulti.Snackbar` — style tersedia, Snackbar memang tak
  pernah dipakai (notifikasi via HUD in-app). Jika suatu saat
  dipakai, otomatis cyber.
- Fallback `Toast` di `hud()` — hanya terjadi sebelum binding layout
  (praktis tak tercapai; onCreate mem-bind dulu).

### 3) BELUM MASUK THEME — ditemukan & ditangani via SATU DialogTheme
- AlertDialog (3 dialog: loading, Pilih aplikasi, Mode IP per app):
  abu-abu platform tanpa border → **Theme.WpmultiTest.Dialog**
  (`android:alertDialogTheme`): panel opaque #1B2637 + stroke neon
  cyan 1dp + sudut 8dp + judul monospace bold + tombol aksi borderless
  cyan mono + divider scanline.
- Widget programatis dalam dialog dibuat dengan `dialogCtx()`
  (ContextThemeWrapper satu sumber): EditText search → terminal/HUD
  (kotak near-black + garis neon + cursor cyan 2dp); CheckBox baris →
  drawable cb_cyber (kotak tegas + check cyan) — glitch
  checked/unchecked tetap; Button mode → chip neon flat tanpa shadow;
  ListView → divider scanline + scrollbar cyan + ripple cyan.
- Chip mode IP: GLOBAL slate / IPv4 cyan / IPv6 magenta
  (applyModeChipStyle; token palet sama, tanpa hue baru). Glitch tetap
  hanya pada tombol yang berubah.
- Spinner (5 adapter: DNS, Aplikasi, IP, Log level, Mode glitch):
  `simple_spinner_item` platform → `spinner_item.xml` /
  `spinner_dropdown_item.xml` (monospace, glitch_white, item
  terpilih cyan, minHeight 48dp).
- Ripple & scrollbar app-wide: colorControlHighlight cyan 15%,
  scrollbarThumbVertical cyan 40%.
- Status bar/nav bar: targetSdk 36 → edge-to-edge sistem; inset sudah
  ditangani listener rootMain — tidak diubah.
- PopupMenu/Switch/RadioButton: tidak ada di aplikasi (N/A).
- Ikon & progress indicator: sudah bertema (M3 + tint cyan).

## Aturan yang dijaga
- Tidak ada layout shift: item spinner tertutup tanpa padding
  vertikal baru; efek hanya transform/alpha dengan baseline restore.
- logView tetap 100% bebas glitch (walk() skip + fragments() skip).
- Kontras list panjang: label glitch_white 14sp satu baris ellipsize
  (nama/package super panjang tidak lagi saling menimpa).
- Glitch hanya menarget elemen yang berubah (listener per-elemen).
- Input tidak diblokir; state UI berubah seketika (popup polos saat
  efek mati, tombol chip langsung ganti label+warna).
- Tidak ada alpha/transform/shadow/overlay tertinggal (stepFinish +
  cancelFor + clearScanline di semua jalur keluar).

## File yang berubah
- `GlitchDropdown.java` — pengukuran AT_MOST + jembatan dropdown-view
  + fading edge off + choice mode + anim Off.
- `MainActivity.java` — dialogCtx(), applyModeChipStyle(), kedua
  dialog pakai context/tema baru + divider, guard isGlitchEnabled
  (2 dialog + glitchFlash), 5 adapter layout spinner baru.
- `themes.xml` — Theme.WpmultiTest.Dialog + 6 style widget + attr
  tema utama (alertDialogTheme, ripple, scrollbar) + style Off.
- `colors.xml` — token dialog/chip/field/scrollbar/ripple.
- Baru: bg_dialog, bg_search_terminal, cursor_cyber, cb_cyber,
  scrollbar_cyber, bg_chip_mode_{global,v4,v6}, anim/none,
  spinner_item.xml, spinner_dropdown_item.xml,
  color/{spinner_item_text,dialog_btn_text}.xml.

## Build
- `JAVA_HOME=/home/z/jdk/jdk-17.0.20.1+1 ./gradlew assembleDebug` ✓
  (21.3 MB) · `assembleRelease` ✓ (6.27 MB unsigned).
