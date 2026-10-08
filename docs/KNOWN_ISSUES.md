# KNOWN_ISSUES — bug yang sudah diperbaiki (dengan akar penyebab) + item menunggu konfirmasi

## A. Sudah diperbaiki — akar penyebab terverifikasi

### 1. Dropdown mini / opsi terpotong (cy10)
**Gejala**: popup setinggi satu baris, teks menimpa konten di belakang.
**Akar**: (1) ListView popup diukur UNSPECIFIED — AOSP `ListView.onMeasure`
mode UNSPECIFIED hanya mengukur SATU anak; (2) popup memanggil `getView`
(bukan `getDropDownView`) sehingga baris memakai layout "closed" tanpa
padding; (3) fading edge vertikal = jendela transparan.
**Fix**: ukur AT_MOST semua item (cap 280dp/ruang layar), jembatan adapter
`getView → getDropDownView`, fading edge off.
**Commit**: 6271ddd.

### 2. Keyboard tidak muncul di search field dialog (cy10.1)
**Gejala**: tap search "Pilih aplikasi"/"Mode IP" tidak memunculkan IME;
paste long-press tetap jalan.
**Akar**: framework `AlertController.setupCustomContent` memasang
`FLAG_ALT_FOCUSABLE_IM` saat `canTextInput(customView)=false` — custom
view berupa ListView yang saat `show()` belum punya anak (search dulu
dipasang sebagai HEADER ListView), sehingga window dikecualikan dari IME
dan `showSoftInput` ditolak senyap. UI clipboard bukan IME → paste tetap
jalan (cocok persis gejala).
**Fix**: `fixDialogIme()` — clear flag + `SOFT_INPUT_STATE_VISIBLE |
ADJUST_RESIZE` + click→requestFocus→showSoftInput. (cy10.3: search kini
anak langsung custom view → `canTextInput` benar dari awal; fix dipertahankan
sebagai belt-and-braces.)
**Commit**: 0eb1732.

### 3. Checkbox tepi kiri (cy10.1 → dikerjakan ulang cy10.2)
**Gejala (awal)**: permintaan geser checkbox +2dp; fix pertama
(`setPadding` 16→18dp) malah MENJAUHKAN teks dari box.
**Akar**: `CompoundButton.onDraw` (AOSP 14/15/16) menggambar button
drawable di x=0 view dan MENGABAIKAN padding; teks mulai di
`paddingLeft + intrinsicWidth`. Jadi padding hanya menggeser teks.
**Fix (benar)**: padding kiri kembali 16dp; box digeser +2dp DI DRAWABLE
`cb_cyber.xml` (inset kiri 2dp, kedua state layer-list 20dp, intrinsic
22dp). Box +2dp dari tepi dialog, teks ikut +2dp, gap box→teks tetap.
**Commit**: 1a243c2.

### 4. Artifact kotak di tepi kiri/kanan pill nav (cy3 → cy8 → cy10.3)
**Gejala**: bentuk persegi menonjol di ujung kiri/kanan pill; muncul
terutama terlihat di screenshot, dipicu/diperparah perpindahan tab.
**Riwayat**: cy3 memperbaiki "box abu-abu di atas pill" (HUD); cy8
memperbaiki alpha-pada-view-ber-elevation (shadow offscreen layer
ter-clip bounds persegi) — tapi artifact ujung pill masih muncul.
**Akar (analisis AAR material-1.14.0, bytecode)**: tiga lapisan mesin
bekerja di pill lama:
1. `FloatingToolbarLayout` membuat `MaterialShapeDrawable` capsule +
   **elevation 6dp** pada view **90% translucent** — kombinasi
   shadow/outline/layer paling rapuh di render pipeline;
2. `BottomNavigationView` membawa background `MaterialShapeDrawable`
   **persegi** sendiri (dari style lib) yang hanya di-TINT transparan —
   drawable persegi ukuran penuh tetap hidup di atas ujung capsule;
3. mesin inset/margin lib (`updateMargins`) yang anyway di-nonaktifkan
   listener rootMain (CONSUMED).
**Fix (cy10.3)**: struktur diganti total — BUKAN ditutup warna:
`navPill` = FrameLayout polos + `bg_nav_pill.xml` (GradientDrawable
capsule XML, radius 32dp = ½ tinggi 64dp) + **elevation 0** (tanpa
shadow framework); BNV `android:background="@null"` (tidak ada drawable
persegi sama sekali). Mesin yang menghasilkan kotak tidak ada lagi, maka
tidak bisa muncul lagi setelah pindah tab berulang (efek pada area nav
memang tidak pernah menyentuh background/alpha pill — hanya
`glitchJitter` translationX + span label per item).

### 5. Tombol mode IP per aplikasi: "harus menekan 5x, yang berubah hanya
warna" (cy10.3)
**Gejala**: menekan chip mode berkali-kali; label GLOBAL/IPV4/IPV6 tidak
pernah berubah, hanya warna chip — user menebak-nebak mode.
**Akar (dari kode, bukan tebakan)**: `GlitchText.burst()` memakai
`PENDING.get(tv)` sebagai teks dasar tanpa validasi. Siklus per tekan:
`ACTION_DOWN` (press feedback MINOR) menangkap base lama → `onClick`
men-set label BARU → `glitchNow(MEDIUM)` membaca PENDING **basi** dan
men-`setText` **teks lama** kembali → label terkunci di teks saat row
di-bind; pref sebenarnya berubah (state tersimpan), tapi label bohong.
**Fix**: PENDING divalidasi `TextUtils.equals` — karakter sama = pakai
base (perilaku lama, strip span menumpuk); karakter beda = PENDING basi
dibuang, teks sekarang jadi base. Visual kilatan tidak berubah; yang
berubah hanya teks yang dikorupsi/dipulihkan kini teks yang benar.
Ditambah: pemilihan mode kini via POPUP 1-tap (semua mode berlabel +
tertanda aktif), chip 48dp (area sentuh), legenda di bawah search bar.

### 6. Lain-lama (ringkas)
- Semua dropdown tidak muncul (cy8.1): `PopupWindow` tanpa
  `setContentView` = no-op senyap. bf45d42.
- Dropdown masih "UI Android biasa" (cy8): cabang Spinner di
  `installTouch` dead code (tertutup cabang ViewGroup). a018414.
- Crash bounce spring (pra-cy1): `SpringForce()` tanpa finalPosition.
  6883b3e.
- Crash lambda effectively-final: a8bdd61.

## B. Item menunggu konfirmasi user (K1–K7, dari audit cy10.2)

| # | Temuan | Mengapa belum diubah | Kalau dijalankan |
|---|--------|----------------------|------------------|
| K1 | Monitor men-`setText` 9 label tiap 2 dtk tanpa `setTextIfChanged` → label tetap kilat walau nilai statis (AOSP `setText` tidak short-circuit konten identik) | Mengubah ke `setTextIfChanged` = kilat berhenti saat nilai statis → frekuensi visual berubah | Flash berhenti saat nilai statis (hemat ±8 burst/2dtk), sejalan prinsip "teks tidak berubah = tidak ada glitch" tapi mengurangi kesan hidup |
| K2 | Efek press + checked pada satu tap checkbox (2 momen, 4 panggilan API) terasa bertumpuk | Menyatukan/menghapus satu = mengubah feel feedback yang beku sejak cy8 | Feedback tap checkbox berubah |
| K3 | Ambient tetap berjalan di belakang dialog yang menutupi layar | Deteksi "tertutup dialog" tidak deterministik; dialog tidak full-screen | Ambient berhenti/berlanjut di balik dialog |
| K4 | Span korupsi dialokasikan per event (≤3 objek per keystroke) | Pooling span berisiko interaksi dgn watcher/restore | Alokasi GC turun; visual identik secara teori tapi tidak terbukti |
| K5 | `morphEngineButton` membangun `ShapeAppearanceModel` baru per frame (±16 objek/260ms) | API lib tidak punya setter sudut tanpa objek baru; volumenya kecil | Alokasi turun; risiko beda versi material |
| K6 | `detail.setText` chip sesi tetap dijalankan walau GONE | Skip saat GONE = teks basi sampai tick berikut saat expand | Hemat churn kecil; risiko fitur expand |
| K7 | Idle TICK tetap mem-post 1 runnable/950–1500ms saat foreground idle | TICK = penggerak ambient itu sendiri; RESTORE tak-bersyarat memotong umur span yang beririsan — timing harus identik | (Sudah dianalisis cy10.2 dan sengaja dibatalkan) |

## C. Perlu test manual di device (checklist cy10.3)

1. **Mode IP per aplikasi**: buka dialog → tap chip → popup 3 mode →
   pilih tiap mode → label chip BERUBAH seketika + warna sesuai; pilih
   ulang mode sama = popup tutup tanpa perubahan; tap luar/back = tutup;
   tutup dialog saat popup terbuka = popup ikut tertutup; glitch hanya
   pada chip yang berubah.
2. **Search sticky**: scroll list di kedua dialog → search (dan legenda
   di dialog Mode IP) tetap terlihat; ketik → keyboard muncul, list
   menyusut (ADJUST_RESIZE), search tidak tertutup keyboard; tanpa
   layout shift.
3. **Navbar**: pill compact (±222dp, 64dp tinggi) terpusat; tekan tiap
   item (area ≥48dp); pindah tab BERULANG kali → tidak ada kotak/persegi
   di ujung kiri/kanan pill; pill aktif tetap bertema; label tidak
   "melompat" saat pindah tab.
4. **Checkbox**: posisi centang tidak berubah dari cy10.2 (box +2dp dari
   tepi dialog, gap box→teks tetap).
5. **Mode efek**: Auto / Selalu aktif / Mati — semua jalur di atas tetap
   benar; Mati = tanpa efek, state tetap berubah.
6. **logView**: halaman Log tetap 100% bersih dari glitch.
