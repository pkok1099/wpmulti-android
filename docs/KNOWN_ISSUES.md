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

### 9. Navbar pill: indikator aktif "agak miring" (cy10.7 → cy10.9)
**Gejala**: indikator capsule item aktif terlihat miring / melorot ke
bawah — bukan diagonal harfiah (tidak ada kode rotation/skew di seluruh
proyek), melainkan komposisi vertikal yang salah mata: capsule 64×32dp
terlihat seperti "band" yang memotong bagian ATAS ikon dan bagian BAWAH
label, dengan celah asimetris; setelah tap cepat beruntun, ikon+label
item bisa bergeser beberapa piksel dari slotnya (indikator tetap di
slot → tampak miring relatif terhadap isinya).

**Akar (tiga, saling menumpuk — diverifikasi lewat replika aritmetika
layout persis termasuk pembulatan `(int)`/integer-division Android)**:
1. **Salah jangkar vertikal (regresi cy10.7)**: fix cy10.7 untuk "pill
   sedikit lebih rendah" mengubah jangkar indikator dari ikon ke **pusat
   blok ikon+label** (= pusat item; `row.getTop + item.getTop +
   item.getHeight/2`). Ikon ada di ATAS blok → pusat item tertarik
   ±(gap+label)/2 ≈ 8dp di BAWAH pusat ikon → capsule 32dp jatuh terlalu
   rendah: tepi atasnya memotong atas ikon (±2dp keluar), tepi bawahnya
   memotong label. Pra-cy10.7 jangkarnya sudah benar (ikon) tetapi
   dihitung dari ASUMSI `padTop + ikon/2` yang meleset 1–2px saat
   ITEM_MIN clamp/pembulatan — cy10.7 memperbaiki 1–2px itu dengan
   mengorbankan jangkar 8dp-nya.
2. **Bias 1px permanen (slack ganjil)**: kolom ikon+label dipusatkan di
   item lewat `Gravity.CENTER` FrameLayout = **integer division**.
   Slack vertikal = `(int)37dp − (int)20dp − (int)3dp`; pada density
   ganjil-kuarter (2.5/1.5/…) = 35px (GANJIL) → blok duduk 1px lebih
   TINGGI dari pusat → jarak ikon→tepi atas pill ≠ label→tepi bawah.
3. **Drift jitter permanen (`GlitchText.glitchJitter`)**: baseline
   `ox = v.getTranslationX()` ditangkap saat panggilan; jika rantai
   jitter KEDUA dimulai saat rantai pertama masih berjalan (tekan item /
   pindah tab beruntun < ~150ms — persis pola pemakaian navbar),
   `ox` = posisi MID-FLIGHT (bukan 0) dan langkah pemulihan rantai kedua
   menetapkan offset basi itu PERMANEN (tidak pernah dikoreksi siapa
   pun: item tidak pernah kena efek alpha → BASE tidak pernah terisi).
   Ikon+label bergeser 1–3px; indikator (getLeft, tak terpengaruh
   translationX) tetap → tak lagi konsentris.
4. **Tepi sub-piksel**: target translationX/Y menghasilkan koordinat
   pecahan (.5px) pada beberapa density → kedua tepi capsule
   ter-antialias 50% — terbaca "kurang tajam/miring" walau posisinya
   benar.

**Fix (cy10.9 — kontrak cy10.4 tetap: tanpa elevation/layer/bitmap,
indikator tetap SATU view translationX/Y)**:
- Jangkar vertikal = **pusat IKON dari posisi layout NYATA** rantai
  penuh `row.getTop + item.getTop + col.getTop + icon.getTop +
  icon.getHeight/2` — imun clamp/pembulatan (menutup akar lama
  1–2px SEKALIGUS regresi jangkar); ikon 20dp masuk penuh dalam capsule
  32dp (ruang simetris 6dp atas/bawah), label di bawah di luar capsule
  (bahasa M3 asli; kontrak cy10.4 "indikator di belakang ikon").
- `itemH` dipaksa slack GENAP terhadap kolom (+1px bila ganjil — tak
  terlihat) → blok konten terpusat persis di semua density/fontScale.
- `indicatorTargetX`/`translationY` dibulatkan `Math.round` → tepi
  capsule tajam simetris (deviasi ≤0.5px tak terlihat).
- `glitchJitter`: baseline translationX kini disimpan di registry
  `JBASE` (WeakHashMap) saat rantai PERTAMA dimulai dan dipakai ulang
  rantai-rantai tumpang tindih berikutnya; dipulihkan & dibersihkan di
  langkah akhir / `cancelFor` / `restoreAllBase`. API beku cy7/cy8 tidak
  berubah; amplitudo/timing/urutan langkah (visual) identik.

**Pencegahan (aturan permanen)**:
- Indikator nav = jangkar KOMPONEN VISUAL yang dibungkusnya (ikon), bukan
  pusat kontainer item — pusat kontainer hanya kebetulan sama bila item
  berisi SATU hal yang seimbang.
- Posisi turunan dihitung dari RANTAI POSISI NYATA hasil layout
  (`getTop()` berantai), bukan asumsi padding/token — keduanya harus
  dibaca bersama: jangkar benar + asumsi = masih meleset; jangkar salah
  + ukuran nyata = meleset lebih besar (regresi cy10.7).
- Efek transform apa pun yang punya "pemulihan ke awal" WAJIB menyimpan
  baseline di registry saat EFEK PERTAMA (bukan membaca ulang posisi
  saat efek berikutnya mulai) — rantai tumpang tindih adalah normal di
  UI yang responsif.
- Pembagian tengah framework = integer division: slack ganjil = bias
  1px — paksa genap bila simetri terlihat.

**Commit**: cy10.9.

### 10. Mode IP per aplikasi: audit cy10.10 + 4 perbaikan
###    (fail-closed, embedded-v4, UDP/DNS mode paksa, UI nonaktif)

Audit diminta user dgn permintaan eksplisit "laporkan file:baris,
jangan menebak". Jawaban audit (nomor baris = kondisi PASCA-cy10.10;
pra-perbaikan ada di git show cy10.7/cy10.8):

**Q1 — di lapisan mana BLOCK diterapkan?** Bukan di proxy, bukan hanya
DNS. TIGA lapisan di data plane TUN:
- TCP: tolak SYN via RST "dari tujuan" — `handleTcp` VpnEngine.java
  :1812-1823 (v4) & `handleTcp6` :1870-1882 (v6, family EFEKTIF);
  keputusan di `verdictAction` :382-389 / `synAction` :362-370.
- UDP non-DNS (termasuk QUIC): buang + ICMP unreachable — `handleUdp`
  :1136-1152 (v4) & `handleUdp6` :1185-1207 (v6).
- DNS UDP/53: jawab LOKAL NODATA utk qtype versi yang diblok —
  `forwardDns` :1240-1278. Ini lapisan BANTU (mempercepat fallback);
  penegakan sebenarnya tetap di paket — app dgn DNS sendiri (DoH)
  tetap kena RST/unreach saat mencoba connect versi yang diblok.
- Proxy SOCKS (`connectViaSocks` :1929+) TIDAK punya logika blok —
  trafik terblok tidak pernah sampai ke sana (SYN sudah di-RST sebelum
  TcpConn dibuat :1824).

**Q2 — pemilik koneksi & UID tak ditemukan?** Atribusi via
`ConnectivityManager.getConnectionOwnerUid` (conntrack netd, binder) —
`flowOwner` :290-319 + `connOwnerUid` :1757-1768; lalu
`getPackagesForUid` :310. Sesi ini: **fail-closed** — pemilik tak
diketahui + family sedang diblok → paket DIBUANG (`verdictAction`
:382-389; pra-cy10.7 :284 lama: fail-open "lebih baik lolos
daripada putus"). minSdk 34 ≥ API 29 → risiko kegagalan sistematis
kecil; drop tampil sbg "(unknown)" di statistik BLOCK[30s].

**Q3 — arti persis "BYPASS"?** DUA makna berbeda (kini diperjelas di
label + legenda, MainActivity.java :3013-3015 & :1139-1159):
- GLOBAL "IPv6 saja": route TUN hanya ::/0 (VpnEngine :465-475) →
  trafik IPv4 keluar dari tunnel via jaringan langsung — TANPA
  proteksi VPN (bocor by design, itu memang fungsi mode ini).
- Per-app "BYPASS v4": paket IPv4 app itu DIBUANG di dalam tunnel
  (`synAction` mode 2 → aksi 1, :365) — TIDAK keluar VPN.

**Q4 — mode berubah saat VPN aktif:** koneksi BARU langsung kena
verdict baru (swap map volatile + reset cache — `applyLive` :679-706);
koneksi LAMA yang bertentangan DIPUTUS di background (RST/ICMP —
`cutConflictingFlows` :734-759, kini dari PEMILIK yang di-cache saat
admit :1901/:2317 — tanpa lookup conntrack baru, G11 tertutup).
Pengecualian tetap: G10 (family belum ditangkap route → LIVE_PARTIAL,
HUD + log).

**Q5 — kombinasi yang tidak berlaku:** (a) mode per-app v4/v6 + global
non-dual → tidak ditegakkan (`synAction` :364-365 guard `dual`);
(b) app yang di-bypass split-tunnel → semua mode tidak berlaku (G1);
(c) mode global berubah saat VPN jalan → baru berlaku saat start
berikutnya (route+verdict global fix saat establish). Semua kini
DITANDAI di UI (lihat bawah), sebelumnya hanya catatan legenda umum.

**Perbaikan yang diterapkan (cy10.10)**:
1. **BLOCK fail-closed** (Q2): pemilik tak dikenal/error → paket
   family yang diblok DIBUANG: SYN → RST (`verdictAction`), UDP →
   unreach, DNS → NODATA (`forwardDns` killA/killAAAA :1251-1259).
   Kunci: `blockV4Active/blockV6Active` :181-187 (volatile, ikut
   live-swap :690-691).
2. **Tutup celah embedded-v4**: tujuan ::ffff:0:0/96 & 64:ff9b::/96
   dihitung family v4 utk verdict (`isV4EmbeddedV6` :399-411,
   `effFamilyV6` :414-417) — dipakai di TCP/UDP/cut. IP literal
   memang sudah tertutup (blok per-family di data plane, bukan
   per-nama DNS). 6to4/Teredo tetap tidak dibedakan (lihat §8).
3. **Mode paksa v4/v6 kini menyaring UDP+DNS** saat global dual
   (QUIC, DoH-bawaan-app tidak bisa lewat UDP family lawan;
   query A utk app "hanya via IPv6" dijawab NODATA) — konsisten
   dgn label "hanya via X". Perilaku lama (hanya TCP SYN) membuat
   label setengah benar.
4. **UI menandai yang tidak berlaku** (MainActivity): baris v4/v6
   di popup per-app DINONAKTIFKAN + keterangan bila global non-dual
   (:1436-1456, :1461-1484, :1503-1506); app bypass split-tunnel →
   semua baris nonaktif + catatan; item BYPASS di dropdown massal
   tak bisa dipilih (adapter isEnabled :1288-1301); ubah mode global
   saat VPN jalan → HUD "berlaku saat VPN dinyalakan ulang" + dampak
   ke mode per-app (:3039-3051). Label "BYPASS" diperjelas + warna
   legenda BLOCK v4 = status_amber (coral vs red terlalu mirip).

**Verifikasi**: mirror Python logika verdict
(`scripts/sim_ipmode_failclosed.py`) — 40+ kasus: block+embedded,
fail-closed per family, mode paksa UDP/DNS, cut-by-cache, shared-UID
precedence — semua lolos. Build device belum bisa dijalankan di
sandbox (tanpa Android SDK) — checklist C.6 baru di bawah.

**Commit**: cy10.10.

### 11. cy10.11 — verifikasi build/tes nyata + dampak samping
###     fail-closed + celah mode proxy murni

**Build & lint (A1)**: `assembleDebug` + `lintDebug` pertama kali
benar-benar dijalankan (sandbox kini punya Android SDK). Build lolos
sejak 1c92b6b — review manual ulang semua hunk diff cy10.10 tidak
menemukan sisa typo. Lint: 3 error diperbaiki dari akar (2x
WrongViewCast: tag XML `<Button>` → kelas eksplisit MaterialButton;
QueryAllPackagesPermission: `tools:ignore` + justifikasi — izin
memang dibutuhkan `getInstalledApplications(0)`).

**Tes JUnit menggantikan simulasi Python (A2)**: seluruh logika
verdict diekstrak ke `IpModeVerdict.java` (kelas MURNI, tanpa
dependensi Android) — VpnEngine mendelegasikan (modeCode, synAction,
verdictAction, udpVerdict, dnsPolicy, uidVerdictMode/Label,
effFamilyV6/isV4EmbeddedV6; jalur paket handleUdp/handleUdp6/
forwardDns/cutConflictingFlows kini memanggil fungsi yang sama).
Identitas fungsi = by-construction (delegasi), bukan salinan manual.
Tes: `app/src/test/` — IpModeVerdictTest (37) + FailClosedSideEffectsTest
(6) = **43 tes, 0 gagal** (`./gradlew testDebugUnitTest`). Cakupan:
BLOCK v4/v6 TCP/UDP/DNS, mode paksa (guard dual), IPv4-mapped
::ffff:/96, NAT64 64:ff9b::/96 (+varian non-/96 ditolak, 6to4/Teredo
tidak dibedakan — batas dipertegas), pemilik tak dikenal (fail-closed
per family), UDP/QUIC, DNS killA/killAAAA/preferFamily, shared-UID
BLOCK-menang + label deterministik, invarian UDP==TCP verdict utk
pemilik dikenal.

**Dampak samping fail-closed (A3) — DIBUKTIKAN TES, bukan teori**
(`FailClosedSideEffectsTest`, replika rantai
flowOwner→verdict persis engine):
1. App lain yang atribusinya BERHASIL → TIDAK tersentuh (PASS dua
   family) walau ada app BLOCK — normal traffic aman.
2. **Sisi gelap terbukti**: paket IPv4 milik siapa pun (kemungkinan
   app lain) yang pemiliknya GAGAL diatribusikan — race conntrack
   (entri belum terlihat netd), exception binder, atau UID sistem
tanpa paket (`getPackagesForUid` kosong → flowOwner null :314) —
   DIBUANG saat family itu diblok siapa pun (REJECT). Ini memang
   harga kebijakan fail-closed yang diminta eksplisit cy10.10
   ("atribusi gagal → tetap buang"), bukan bug — tapi konsekuensinya
   nyata: aplikasi lain bisa kehilangan konektivitas family-yang-
   diblok secara INTERMITTEN saat lookup conntrack kalah race.
   Mitigasi yang ada: kegagalan lookup TIDAK di-cache (retry tiap
   paket — lookup umumnya berhasil pada paket ke-2), statistik
   "(unknown)" terlihat di BLOCK[30s], dan dampak TERBATAS pada
   family yang diblok saja (v6 tetap lolos saat hanya v4 diblok —
   dibuktikan tes).
3. Tanpa app BLOCK sama sekali → pemilik tak dikenal tetap lolos
   (fail-closed tidak menyala) — dibuktikan tes.
4. minSdk 34 ≥ API 29: `getConnectionOwnerUid` selalu tersedia, tidak
   ada cabang legacy "API terlalu tua" di flowOwner (satu-satunya
   jalur fallback = null → verdict).

**Mode proxy murni (A4) — kondisi NYATA IPv4 literal lewat proxy**:
SOCKS5 server hidup di engine Go (AAR prebuilt, proses `:goengine`):
`Mobile.start(confDir, "127.0.0.1:1080", "127.0.0.1:8080")`
(GoEngineService.java:39,:68). Saat VPN MATI (mode proxy murni),
app yang diarahkan manual ke 127.0.0.1:1080 mengirim SOCKS5 CONNECT
(ATYP 0x01 = IPv4 literal) LANGSUNG ke listener Go — tidak pernah
melewati TUN, jadi TIDAK ada verdictAction/flowOwner/BLOCK/BYPASS/
mode paksa/DNS-filter sama sekali: koneksi IPv4 literal app yang
seharusnya di-BLOCK v4 **BERHASIL** lewat proxy. Catatan tambahan:
listener 1080 di loopback tetap bisa diakses app manapun bahkan saat
VPN hidup (loopback tidak dirutekan ke TUN) — jalur bypass arsitektur
lama. → G12 (dengan usulan perbaikan, BELUM diterapkan sesuai
permintaan).

**Commit**: cy10.11.

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
| G7 | ~~connOwnerUid fail-open~~ **TERTUTUP cy10.10** | Pemilik tak dikenal utk family yang diblok kini DIBUANG (fail-closed): SYN → RST, UDP → ICMP unreach, DNS → NODATA; dihitung sbg "(unknown)" di statistik BLOCK[30s] | Permintaan eksplisit user membalik kebijakan lama ("putus total lebih buruk"→"blok tidak boleh bocor gara-gara atribusi meleset"); minSdk 34 → API selalu tersedia |
| G8 | Auto dual-capture mengubah jalur app lain | Global v6only/v4only + ada app BLOCK → versi "bypass" app lain kini ikut tunnel (bukan direct) | Route per-TUN bersifat global; menangkap versi utk SATU app = menangkap utk semua. Dilaporkan eksplisit di log start BLOCK |
| G9 | UDP one-shot dgn port sumber baru per paket | Cache verdict miss → lookup per paket (beban CPU, bukan bocor) | Perilaku app; lookup tetap benar hanya lebih mahal |
| G10 | Live-apply mode BLOCK saat global non-dual DAN TUN tidak menangkap family yang diblok (BLOCK baru pertama kali utk family itu, tanpa restart) | Verdict/SYN/UDP/DNS baru sudah ditegakkan, tetapi paket versi itu TIDAK PERNAH masuk TUN → tidak bisa ditolak; koneksi lama versi itu sudah diputus | Route VpnService ditetapkan saat establish dan tidak ada API utk mengubahnya pada sesi jalan; re-establish in-place menciptakan jendela gap route (= bocor sesaat — lebih buruk). UI memberi tahu via HUD "restart VPN utk penegakan penuh" + log; restart berikutnya otomatis dual-capture |
| G11 | ~~Atribusi conntrack UDP bisa kedaluwarsa saat live-apply~~ **TERTUTUP cy10.10** | cutConflictingFlows kini memakai PEMILIK yang di-CACHE saat flow di-admit (TcpConn.owner/UdpFlow.owner) — tanpa lookup conntrack baru; flow app yang baru di-BLOCK pasti terpotong; pemilik tak dikenal diputus fail-closed bila family-nya kini diblok | Cache disimpan saat admit (lookup conntrack hampir selalu berhasil di situ); mode dihitung ulang dari daftar paket UID yang di-cache terhadap map terbaru |
| G12 | **Mode proxy murni (VPN mati)**: SOCKS5 127.0.0.1:1080 (engine Go, GoEngineService :39/:68) tidak melewati TUN → BLOCK/BYPASS/mode paksa/DNS-filter TIDAK berlaku; IP literal IPv4 (ATYP 0x01) lewat proxy BERHASIL utk app yang seharusnya di-BLOCK v4. Listener loopback juga bisa diakses app manapun walau VPN hidup (loopback tak pernah dirutekan ke TUN) — jalur bypass arsitektur lama | Penegakan berada di data plane TUN (handleTcp/Udp/DNS) — trafik proxy langsung ke listener Go tidak tersentuh; go-socks5 tidak punya atribusi UID/per-app | **Usulan perbaikan sisi proxy (cy10.11, BELUM diterapkan sesuai permintaan):** pindahkan listener 1080 dari Go ke relay JAVA di proses app: (1) Java accept() klien loopback → atribusi UID via `ConnectivityManager.getConnectionOwnerUid` 4-tuple koneksi loopback itu (API sama dgn flowOwner — catatan: perlu verifikasi device bahwa netd conntrack melacak koneksi loopback; bila tidak, fallback: connect() balik ke 127.0.0.1:peerport tidak mungkin → opsi kedua = listener unix-socket per app yang dikonfigurasi lewat AIDL); (2) verdict family target CONNECT pakai `IpModeVerdict` APA ADANYA (ATYP 0x01 & ATYP 0x04 dgn ::ffff:/96 + 64:ff9b::/96 → v4, mode paksa, fail-closed saat UID tak teratribusi dan family diblok); (3) koneksi diizinkan → forward ke unix socket engine `socksPath` (mekanisme sama dgn relay TUN — connectViaSocks :1861); ditolak → SOCKS5 reply 0x02 "connection not allowed by ruleset" (mirror RST: app dapat error seketika, fallback cepat); (4) UDP ASSOCIATE: cek family target aturan sama. Keuntungan: semua logika tetap di Java (satu sumber, diuji JUnit), tanpa rebuild Go. Alternatif Go-only (lebih murah per-koneksi tapi butuh rebuild AAR + plumbing map mode via AIDL baru): verdict di handler CONNECT go-socks5 + UID dari tag SELinux `getsockopt(SO_PEERSEC)`? — tidak tersedia utk TCP loopback; /proc/net/tcp sejak Android 10 hanya menampilkan socket milik UID sendiri → atribusi dari Go praktis tidak bisa andal |

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
5. **Navbar pill (fix cy10.9)**: indikator aktif terpusat VERTIKAL pada
   IKON (ikon masuk penuh dalam capsule, ruang simetris atas/bawah;
   label di bawah di luar capsule) — bukan lagi "band" yang memotong
   atas ikon & bawah label; jarak konten ke lengkungan kiri = ke kanan;
   tepi capsule tajam (piksel penuh); setelah TAP CEPAT beruntun
   (<150ms antar tap) ikon+label tetap di slotnya (tidak bergeser
   permanen); tetap tanpa kotak/label terpotong.
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

### C.6. Checklist device cy10.10 (fail-closed BLOCK + embedded-v4 +
###     UI nonaktif)

8. **BLOCK fail-closed** (butuh device + jaringan dual-stack):
   - VPN aktif + satu app di-BLOCK (mis. browser) → pastikan app LAIN
     tetap bisa konek normal (lookup berhasil normal — jalur
     fail-closed tidak menyala utk family tanpa blok);
   - kill app uji paksa (adb shell am force-stop) saat koneksi
     setengah → koneksi TUA app itu tetap diputus saat di-BLOCK live
     (verdict dari cache pemilik, bukan conntrack);
   - logView: drop pemilik-tak-dikenal tampil sbg
     `BLOCK[30s] (unknown): drop v4=…` (jarang; hanya saat atribusi
     benar-benar gagal).
9. **Embedded-v4 / NAT64** (jaringan v6-only dgn DNS64, atau uji
   manual dgn URL `http://[64:ff9b:d00:d00::1]/` bila jaringan
   punya NAT64):
   - app BLOCK v4 tidak bisa mencapai IPv4 lewat alamat ter-embed
     (RST/unreach), sementara IPv6 native tetap jalan;
   - app mode "IPv6" (paksa) juga memperlakukan tujuan ter-embed v4
     sbg v4 (tidak lolos sbg "v6").
10. **Mode paksa v4/v6 kini menutup UDP+DNS** (global Dual):
   - app "hanya via IPv6" + situs ber-QUIC v4 → QUIC v4 tidak lolos
     (fallback cepat, bukan menggantung); query A app itu dijawab
     NODATA (logView: `dns NODATA=`);
   - HARUS TIDAK ada regresi: app tanpa mode sama sekali tidak
     tersentuh (tanpa lookup tambahan saat list mode kosong).
11. **UI penanda nonaktif**:
   - global "IPv6 saja" → buka popup mode per-app: baris IPv4/IPv6
     REDUP + desc "— nonaktif: global non-dual" + catatan di atas
     daftar; item BYPASS di dropdown massal tak bisa dipilih;
   - split tunnel "Kecuali yang dipilih" + app di dalam daftar kecuali
     → popup per-app app itu: SEMUA baris redup + catatan "App ini di
     luar tunnel (split tunnel)";
   - ubah mode global saat VPN jalan → HUD "Mode global berlaku saat
     VPN dinyalakan ulang — N mode per-app IPv4/IPv6 akan nonaktif";
   - legenda: titik BLOCK v4 kini kuning-hijau (acid), terbedakan
     jelas dari merah BLOCK v6; ada baris penjelas dua makna
     "bypass" + baris fail-closed.

### C.7. Checklist device cy10.11 (tes JVM vs device)

Tes JUnit (43) menguji logika verdict di JVM — yang TIDAK bisa
dijalankan sandbox dan wajib dicek di perangkat fisik:
1. **Attribution asli conntrack** — flowOwner memakai binder
   `getConnectionOwnerUid` nyata; jalur fail-closed "(unknown)" hanya
   teramati saat conntrack benar-benar gagal (lihat C.6 no. 8).
2. **Dampak samping fail-closed di dunia nyata** (§11 A3): dengan satu
   app BLOCK v4 aktif, monitor `BLOCK[30s] (unknown)` — harus tetap
   ~0 di jaringan normal (race conntrack jarang); bila angkanya
   besar, atribusi conntrack di ROM itu bermasalah → pertimbangkan
   whitelist UID sistem (perubahan kebijakan, diskusikan dulu).
3. **Proxy murni (G12)**: VPN mati + engine jalan + browser proxy
   manual 127.0.0.1:1080 ke IP literal IPv4 dari app yang di-BLOCK v4
   → saat ini BERHASIL (gap terdokumentasi); jangan lapor sbg bug
   baru — sampai usulan perbaikan G12 diterapkan.
4. **Refactor delegasi IpModeVerdict tidak mengubah perilaku**: blok
   2-11 di C.6 tetap lulus tanpa perubahan (fungsi identik, hanya
   pindah rumah).
