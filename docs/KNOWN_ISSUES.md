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

### 7. Crash startup: NPE `TextView.checkForRelayout` di
###    `CyberNavBar.setMenu` (cy10.5)
**Gejala**: app langsung force-close saat dibuka (device Android 16 /
One UI): `NullPointerException: Attempt to read from field
'int android.view.ViewGroup$LayoutParams.width' on a null object
reference in TextView.checkForRelayout()` dari `CyberNavBar.setMenu` ←
`MainActivity.onCreate`. Nomor baris di trace (`:169`/`:902`) TIDAK cocok
dengan source karena build release me-remap posisi lewat R8
(`r8-map-id-…`); posisi asli: blok probe ukur label di `setMenu`, dipanggil
dari setup navbar `onCreate` (aktual: `MainActivity.java:2646`).

**Akar (kode + source framework, bukan tebakan)**: `setMenu` mengukur
label dengan SATU `probe` TextView yang DIPAKAI ULANG antar judul.
`buildLabel()` membuat view TANPA parent dan TANPA `setLayoutParams` —
`mLayoutParams` HANYA terisi lewat `setLayoutParams()`/`addView()`.
Rantai framework (diverifikasi langsung ke source AOSP):
1. `TextView.setText` hanya memanggil `checkForRelayout()` **jika
   `mLayout != null`** (android-14.0.0_r1:7178). Iterasi 1 aman — view
   masih segar, `mLayout` null.
2. `probe.measure()` → `onMeasure` → `makeNewLayout` → **`mLayout`
   terbentuk**.
3. Iterasi 2 (`R.menu.bottom_nav` = 4 judul → SELALU terjadi):
   `setText("Sesi")` → `mLayout != null` → `checkForRelayout()` —
   **pernyataan PERTAMANYA membaca `mLayoutParams.width` TANPA null-guard**
   (android-14.0.0_r1:11246; AOSP main/16:11679) → `mLayoutParams == null`
   → **NPE deterministik di setiap cold start**. Bukan spesifik Samsung:
   guard null tidak ada sejak AOSP 14 (minSdk proyek) — cy10.4
   as-pushed mustahil start di API level mana pun.

**Fix (by construction, hasil ukur identik)**:
- Probe BARU per judul + `setLayoutParams(WRAP_CONTENT)` eksplisit
  sebelum `setText`/`measure` — dua lapis: `setText` selalu pada view
  segar (`mLayout` null → jalur `checkForRelayout` tak tersentuh), dan
  `mLayoutParams` tidak pernah null.
- Label item: `addView` (LP terisi) SEBELUM `setText` — kontrak sama.
- `measure(UNSPECIFIED, UNSPECIFIED)` membaca spec yang dilempar, bukan
  LP → dimensi item/pill tidak berubah sedikitpun.

**Pencegahan (aturan permanen)**:
- TextView probe ukur TANPA parent WAJIB: LP eksplisit + `setText` hanya
  selama view segar (sebelum measure pertama) — atau ukur via
  `TextPaint.getFontMetricsInt`/`measureText` tanpa view.
- Aturan "LP dulu, teks kemudian" untuk SEMUA view programatik: pasang ke
  parent (atau set LP) sebelum `setText`.
- Rombak UI besar = cold-start smoke test (buka app SEKALI) sebelum
  push/APK dibagikan — bug ini deterministik, tertangkap <1 detik.

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
| K8 | Keluarga K1 yang belum di-guard `setTextIfChanged`: `vpnStatsView` (updateVpnUi tiap tick saat VPN on), `headerStats` (updateHeader tiap tick), `title`/`detail` chip sesi (String.format + setText per chip per tick walau string identik) | Sama seperti K1: AOSP `setText` tidak short-circuit konten identik → watcher menyala → KILAT terjadi walau nilai statis. Meng-guard = frekuensi kilat saat idle BERKURANG (perubahan behavior visual) — sesuai spesifikasi user harus konfirmasi dulu | Kilat berhenti saat nilai statis (hemat layout pass + burst per 2 dtk); label tetap kilat normal saat nilai benar-benar berubah |
| K9 | Saat background, monitor thread tetap `fetchStatus(true)` + parse JSON sessionStats tiap 2 dtk (jika terakhir di halaman Beranda) untuk menjaga baseline rate | Melewati fetch = rate pertama setelah resume dihitung dari window panjang (rata-rata selama background, bukan instan) — nilai yang DITAMPILKAN bisa beda satu tick | Background = nol binder IPC + nol parse JSON (CPU background turun signifikan); biaya = satu nilai rate "rata-rata" pada tick pertama pasca-resume (kelas perilaku yang sama sudah ada pada pindah halaman via guard `currentPage`) |

## C. Perlu test manual di device (checklist cy10.4 — navbar baru)

Checklist cy10.3 no.1/2/4/5/6 (mode IP, search sticky, checkbox, mode efek,
logView) masih berlaku. Checklist navbar DIGANTI:

3. **Navbar CyberNavBar (cy10.4 + fix crash cy10.5)** — uji BERULANG dan
   bergantian:
   - cold start: app terbuka TANPA crash (fix cy10.5 NPE §7 — cy10.4
     as-pushed pasti crash; jika masih crash, kirim trace);
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

### 8. Mode IP per aplikasi — celah yang TIDAK bisa ditutup dari dalam
###    VpnService (audit cy10.7, dilaporkan apa adanya)

Fitur BLOCK v4/v6 menegakkan blok di data plane TUN (lihat ARCHITECTURE
§9). Audit kebocoran menemukan batas-batas berikut — semuanya dilaporkan
jujur, TIDAK diklaim "tidak ada kebocoran":

| # | Celah | Dampak nyata | Kenapa tidak bisa ditutup |
|---|-------|--------------|---------------------------|
| G1 | App yang di-bypass split tunnel (mode Tolak, atau di luar allowlist) | BLOCK (dan mode IP lain) tidak berlaku sama sekali utk app itu | Paket tidak pernah masuk TUN; routing per-UID dilakukan sistem, VpnService hanya bisa memilih siapa yang masuk |
| G2 | ICMP echo tidak bisa diatribusikan ke app | Ping dari app BLOCK versi diblok tetap di-relay via WireGuard | `getConnectionOwnerUid` hanya mendukung TCP/UDP; conntrack ICMP tidak diekspos API publik. Dampak terbatas: echo membocorkan liveness/RTT, bukan data |
| G3 | App dengan DoH/DoT bawaan (mis. browser) & record HTTPS/SVCB (type 65) | App tetap BISA MENDAPAT record A lewat jalur versi yang diizinkan (TCP 443/QUIC) → mencoba v4 → langsung RST | Konten TLS tidak bisa diperiksa tanpa MITM; blok dijalankan di level paket, bukan konten. Tidak ada egress versi diblok — hanya percobaan sia-sia yang cepat gagal |
| G4 | Mode DNS DoH/DoQ: family upstream mengikuti dnsTarget | Query versi-diizinkan milik app BLOCK bisa menumpang versi-diblok di socket MILIK PROSES VPN | DoH ber-URL/hostname: memaksa family = koneksi terpisah per family + kontrol resolusi di luar jangkauan HttpsURLConnection; remap hanya diimplementasikan utk plain & DoT literal |
| G5 | Resolver custom v4/v6-only tanpa counterpart di tabel | Sama seperti G4: fallback ke upstream family asli | Tidak ada cara mengetahui alamat v6 resolver pilihan user; menebak resolver lain = mengubah jawaban DNS |
| G6 | Jendela VPN mati (stop biasa, revocation, gap sebelum auto-reconnect) | SEMUA trafik kedua versi lewat jaringan langsung | Sifat VPN off; kill switch yang ada (broadcast, notifikasi, reconnect 3x) meminimalkan durasi |
| G7 | connOwnerUid fail-open | Paket versi diblok lolos bila uid tidak dikenal (race conntrack yang sangat jarang, kegagalan binder) | Prinsip lama proyek: putus total lebih buruk daripada lolos sesaat; volume normal: lookup berhasil deterministik |
| G8 | Auto dual-capture mengubah jalur app lain | Global v6only/v4only + ada app BLOCK → versi "bypass" app lain kini ikut tunnel (bukan direct) | Route per-TUN bersifat global; menangkap versi utk SATU app = menangkap utk semua. Dilaporkan eksplisit di log start BLOCK |
| G9 | UDP one-shot dgn port sumber baru per paket | Cache verdict miss → lookup per paket (beban CPU, bukan bocor) | Perilaku app; lookup tetap benar hanya lebih mahal |
| G10 | Live-apply mode BLOCK saat global non-dual DAN TUN tidak menangkap family yang diblok (BLOCK baru pertama kali utk family itu, tanpa restart) | Verdict/SYN/UDP/DNS baru sudah ditegakkan, tetapi paket versi itu TIDAK PERNAH masuk TUN → tidak bisa ditolak; koneksi lama versi itu sudah diputus | Route VpnService ditetapkan saat establish dan tidak ada API utk mengubahnya pada sesi jalan; re-establish in-place menciptakan jendela gap route (= bocor sesaat — lebih buruk). UI memberi tahu via HUD "restart VPN utk penegakan penuh" + log; restart berikutnya otomatis dual-capture |
| G11 | Live-apply memutus koneksi lama yang bertentangan; atribusi conntrack UDP bisa kedaluwarsa utk flow one-shot | Flow UDP lama app yang baru di-BLOCK bisa tetap hidup sampai idle-timeout (<=60 dtk) atau paket berikutnya | getConnectionOwnerUid hanya melihat entri conntrack yang masih ada; fail-open dipilih (memutus flow app lain yang salah = lebih buruk). TCP tidak terkena (conntrack ESTABLISHED stabil) |

Desain yang DELIBERAT (bukan celah, dicatat agar tidak dianggap bug):
- Query DNS app BLOCK ke server versi yang diblok TETAP dijawab lokal
  (NODATA utk versi diblok): "koneksi v4 ke DNS" hanyalah fiksi di dalam
  device (app ↔ TUN) — tidak ada paket versi diblok keluar device atas
  nama app itu. Men-drop-nya justru mematikan DNS app sepenuhnya saat
  resolver hanya mencoba server versi itu.
- Mode v4/v6 lama TIDAK difungsikan di global non-dual (perilaku cy10.3
  dipertahankan); hanya BLOCK yang memicu dual-capture.
- Aksi massal "Terapkan ke semua aplikasi" menulis key utk SEMUA app
  terinstal (termasuk app sistem) — konsisten dgn isi list dialog itu
  sendiri; konsekuensinya counter "N aplikasi diatur" = jumlah seluruh
  app. GLOBAL (massal) menghapus semua key.
- Perubahan daftar app split-tunnel (allow/deny) TIDAK live — tetap
  berlaku saat connect berikutnya (aturan per-UID VpnService fix saat
  establish); tombol Pilih aplikasi hanya disabled saat mode "Semua
  aplikasi" agar UI tidak ambigu (cy10.8).

### C-tambahan. Checklist device cy10.7–cy10.8 (BLOCK + navbar + live apply)

4. **BLOCK v4/v6** (butuh jaringan dual-stack atau tunnel dual):
   - Set app uji (mis. browser) ke BLOCK v4 → situs cek IP: kolom v4
     kosong/gagal (fallback v6 terjadi, bukan error halaman), kolom v6
     normal; logView menampilkan `BLOCK[30s] <pkg>: drop v4=… (tot …)`
     setiap ±30 dtk saat app aktif mencoba v4;
   - BLOCK v6 → kebalikan;
   - ganti mode saat VPN jalan → HUD "… diterapkan ke sesi aktif"
     (cy10.8: LIVE, tanpa restart); mode baru langsung efektif — uji:
     ubah app yang sedang loading ke BLOCK versi yang dipakainya →
     koneksi terputus cepat (RST/ICMP) lalu app fallback/reconnect
     versi yang diizinkan, BUKAN menggantung sampai timeout;
   - uji reconnect: matikan VPN tiba-tiba ( airplane mode ) → auto-
     reconnect membawa mode yang sama (mode dibaca ulang dari prefs);
   - global mode Dual → BLOCK langsung efektif; global v6only/v4only →
     log start menulis "TUN dinaikkan ke dual-capture";
   - DNS mode plain 1.1.1.1 + BLOCK v4: buka situs baru (bukan cache) —
     halaman tetap termuat via v6 (AAAA) tanpa delay panjang;
   - app dengan DoH bawaan (Firefox) + BLOCK v4: browsing tetap jalan
     via v6; percobaan v4 gagal cepat (tidak menggantung).
5. **Navbar pill (fix cy10.7)**: indikator aktif terpusat vertikal
   terhadap blok ikon+label (bukan miring ke bawah); jarak konten ke
   lengkungan kiri = ke kanan; tetap tanpa kotak/label terpotong.
6. **Dropdown massal + live apply (cy10.8)** — dialog "Mode IP per
   aplikasi" saat VPN AKTIF:
   - dropdown "Terapkan ke semua aplikasi..." di paling atas (di atas
     search), membuka popup cybercore (GlitchDropdown); pilih
     "BLOCK v4 — tolak IPv4, hanya v6" → SEMUA chip row berubah jadi
     BLOCK v4 seketika + glitch per chip; counter Setelan ikut berubah;
     label dropdown kembali ke "Terapkan ke semua aplikasi...";
   - pilih "BYPASS v4 — hanya via IPv6" → semua chip jadi "IPv6"
     (BYPASS v4 = v4 dilewati = mode IPv6 — lihat legenda); BYPASS v6 →
     chip "IPv4"; "GLOBAL — ikut mode global" → semua chip Global +
     counter "Belum ada aplikasi diatur";
   - verifikasi live: pilih BYPASS v4 massal → app yang punya koneksi
     v4 aktif terputus & fallback ke v6 TANPA restart VPN; logView
     muncul baris `BLOCK/live: aturan per-app diperbarui (N app…`;
   - global v6only + TUN belum menangkap v4 + pilih BLOCK v4 (massal /
     per-app) → HUD "… restart VPN utk penegakan penuh" (LIVE_PARTIAL);
     setelah restart, log start menulis dual-capture;
   - ubah mode per-app SETELAH aksi massal (fine-tune) → hanya chip
     itu yang berubah (aksi massal tidak "menjebak" state).
7. **Split tunnel (cy10.8)** — halaman Setelan:
   - mode "Semua aplikasi" → tombol "Pilih aplikasi..." DISABLED
     (redup, tak bisa ditekan, TIDAK hilang — tidak ada layout shift)
     + teks counter "Semua aplikasi lewat VPN";
   - pindah ke "Hanya yang dipilih"/"Kecuali yang dipilih" → tombol
     aktif kembali + counter jumlah app; perubahan daftar tetap
     berlaku saat connect berikutnya (bukan live — by design);
   - search/keyboard/checkbox/glitch di kedua dialog tak berubah.
