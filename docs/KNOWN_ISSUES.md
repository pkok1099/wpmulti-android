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

### 4. Artifact kotak hitam di area navbar (cy3 → cy8 → cy10.3 → cy10.4
###    ROMBAK TOTAL — akar terverifikasi forensik piksel + bytecode)
**Gejala**: kotak hitam persegi (solid, sudut 90°) menutupi bagian navbar
dari tengah ikon sampai bawah label; screenshot sebelumnya juga menampilkan
sudut persegi menonjol di kiri-kanan ujung pill; label tampak terpotong.
Muncul/diperparah perpindahan tab berulang; bertahan setelah fix cy3 (HUD),
cy8 (alpha+elevation), cy10.3 (ganti FloatingToolbarLayout→FrameLayout +
`background="@null"` + `elevation="0dp"` pada BNV).

**Investigasi (cy10.4)**:
1. **Forensik piksel screenshot** (Samsung A15, 400dpi, resize SmartSelect
   0.84): kotak hitam murni (<8/255) dengan tepi 1px tajam terpetakan ke
   **PERSIS bounds BottomNavigationView** — 300×56dp, offset 4dp dari atas
   & bawah pill (padding pill), x = pill+6dp … pill+306dp. Ikon + label
   (konten BNV) digambar UTUH DI ATAS kotak; fill capsule pill hanya
   terlihat DI LUAR rentang x BNV. Kesimpulan: view BNV sendiri yang
   terkomposit menjadi kotak hitam opaque.
2. **Bytecode material-1.14.0** (decompile CFR): TIGA fakta berantai:
   - `NavigationBarView` ctor baris 148-157: saat `android:background`
     == `@null`, lib justru **membuat & memasang MaterialShapeDrawable
     baru** sebagai background — `@null` TIDAK mematikan mesin background,
     hanya mengganti mesin mana yang menggambar.
   - ctor baris 171-172: `attributes.hasValue(app:elevation)` TERISI dari
     style chain (`Base.Widget.Material3.BottomNavigationView` →
     `elevation = m3_sys_elevation_level2 = 3dp`) → `setElevation(3dp)`
     dipanggil SETELAH constructor View memproses XML → **menimpa
     `android:elevation="0dp"` di layout kita**. BNV sebenarnya ber-elevation
     3dp selama ini.
   - `setElevation()` dan `onAttachedToWindow()` meneruskan elevasi ke MSD
     via `MaterialShapeUtils.setElevation/setParentAbsoluteElevation` →
     MSD tanpa fill tapi dengan shapeAppearance + elevation-overlay aktif
     masuk jalur **RenderNode/shadow/compositing-layer** framework.
3. **Mekanisme**: view ber-elevation + background shape-drawable + invalidasi
   berulang (pindah tab: menu presenter rebind) = jalur compositing layer
   yang pada pipeline Samsung One UI/Android 16 dapat membeku jadi KOTAK
   HITAM OPAQUE seukuran view — keluarga bug yang sama dengan cy8 "alpha
   pada view ber-elevation → layer ter-clip persegi" (beda manifestasi OEM).
   Ini menjelaskan juga gejala lama: kotak lebih lebar dari lengkungan
   capsule → sudut persegi menonjol di ujung pill (bounds BNV 300dp vs
   pill 312dp melengkung).

**Fix (cy10.4) — by construction, bukan ditutup warna**: BNV DIHAPUS TOTAL,
diganti **`CyberNavBar`** (kelas custom, ±415 baris): semua view framework
polos (FrameLayout/LinearLayout/ImageView/TextView/View) — nol import
material, nol MaterialShapeDrawable, nol menu presenter/badge/ripple
foreground/lazy inflater. Bentuk pill SATU sumber (`bg_nav_pill.xml`
capsule) + `clipToOutline` (outline capsule sendiri — konten terpotong
mengikuti lengkungan pill, sudut persegi mustahil menonjol). Elevation
selalu 0, tanpa hardware layer, tanpa bitmap. Indikator aktif = SATU view
terpisah (`bg_nav_indicator.xml` capsule 64×32dp) yang bergeser via
translationX (ValueAnimator 240ms; instan saat skala animator sistem 0 —
cache + ContentObserver settings). Item dibangun dari `R.menu.bottom_nav`
(parser XmlPullParser TYPED `AttributeSet.getAttributeResourceValue` —
referensi biner hex `@0x7f...`; id tidak berubah). Efek glitch hanya pada
item yang berubah (lama MINOR, baru MEDIUM + jitter — API beku cy7/cy8).
Ukuran: lebar item = label terlebar + 2×10dp (pill ~260dp, dari 312dp);
tinggi item = icon 20dp + gap 3dp + font terukur + padding 7/7 (~51dp;
label tidak terpotong — dihitung dari font, bukan fixed 56dp); area sentuh
≥48dp; pill ~61dp tinggi.

**Pencegahan (aturan permanen)**:
- JANGAN pernah memasang komponen navigasi Material (BNV/NavigationRail/
  FloatingToolbar) di dalam pill melayang — mesin background/elevation
  tersembunyinya tidak bisa dimatikan dari XML (`@null` + `elevation=0`
  DUA KALI terbukti tidak cukup).
- `android:background="@null"` pada widget lib ≠ tanpa background — selalu
  cek constructor widget di bytecode/AOSP apakah ada fallback drawable.
- Elevation style lib menimpa XML lewat `app:elevation` — verifikasi dengan
  `View.getElevation()` runtime, jangan percaya XML saja.
- Area navbar: TANPA elevation, TANPA layerType, TANPA bitmap snapshot,
  TANPA alpha pada container (kontrak cy8 + cy10.4).
**Commit**: cy10.4.

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

## C. Perlu test manual di device (checklist cy10.4 — navbar baru)

Checklist cy10.3 no.1/2/4/5/6 (mode IP, search sticky, checkbox, mode efek,
logView) masih berlaku. Checklist navbar DIGANTI:

3. **Navbar CyberNavBar (cy10.4)** — uji BERULANG dan bergantian:
   - pindah tab CEPAT berulang (10-20x bolak-balik, juga acak) → TIDAK ADA
     kotak hitam, sudut persegi di ujung kiri/kanan pill, ghost shadow, atau
     sisa alpha/transform; ikon/label tetap utuh & terbaca;
   - pill compact terpusat (~260dp lebar, ~61dp tinggi), indikator cyan
     64×32dp berpindah mulus ke item aktif (instan bila skala animator
     sistem = 0);
   - label TIDAK terpotong bawah (tinggi dihitung dari font); area sentuh
     tiap item ≥48dp (tap di tepi item tetap jalan);
   - buka/tutup keyboard (search dialog), rotasi layar, background lalu
     kembali, dialog buka/tutup di atas navbar → tetap benar;
   - mode glitch Auto / Selalu aktif / Mati: pindah tab tetap jalan di
     semua mode; Mati = tanpa efek korupsi tapi indikator tetap berpindah;
   - tekan item = kilat korupsi label item itu + micro-jitter item
     (bukan seluruh pill); tap item yang sudah aktif = no-op;
   - glitch saat pindah tab hanya pada 2 item yang berubah (lama korupsi
     singkat, baru rekonstruksi) — bukan seluruh navbar.
