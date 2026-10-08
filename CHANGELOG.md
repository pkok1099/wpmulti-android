# Changelog

Semua perubahan penting pada proyek ini didokumentasikan di sini.
Format mengikuti [Keep a Changelog](https://keepachangelog.com/id/1.1.0/);
versi = fase kerja yang dilacak dari git log (cy1–cy10.x), terbaru di atas.

## [cy10.8] — 2026-10-09

### Added
- **Dropdown aksi massal "Terapkan ke semua aplikasi"** di dialog Mode IP
  per aplikasi (di atas search bar / list; SATU kontrol tambahan, tanpa
  tombol bulk lain; dropdown Mode IP global TIDAK disentuh):
  - Isi: placeholder aksi + GLOBAL / BYPASS v4 / BYPASS v6 / BLOCK v4 /
    BLOCK v6. Nama BYPASS mengikuti konvensi dropdown global ("IPv6 saja
    (bypass IPv4)"): **BYPASS v4 → mode IPv6** (v4 dilewati, app hanya
    via IPv6), **BYPASS v6 → mode IPv4**; GLOBAL menghapus semua key
    per-app (kembali ikut global). Desc singkat per item + nama ganda di
    legenda ("IPv6 / BYPASS v4") menjembatani penamaan dgn label chip.
  - Pilih = terapkan ke SEMUA aplikasi dalam SATU edit prefs, label/state
    tiap row ter-update seketika (chip row terlihat diset langsung +
    rebind list), glitch hanya pada chip yang berubah (MEDIUM, level
    sama dgn pick per-app), counter Setelan ikut, selection kembali ke
    placeholder (menu AKSI, bukan state yang bisa basi).
- **Live apply tanpa restart VPN** (per-app maupun massal; alur
  "tap → update config → apply ke sesi/TUN aktif"):
  - `VpnEngine.applyAppIpModes()`: tukar map mode (volatile) + kosongkan
    cache verdict SEKETIKA → semua paket baru (SYN TCP / UDP / DNS)
    langsung memakai verdict baru; bagian berat (binder conntrack per
    flow) dijalankan di background thread (UI tidak pernah diblok).
  - **Koneksi lama yang bertentangan diputus aman**: TCP → RST dari
    "tujuan" dgn seq/ack benar utk state kini (SYN-SENT: ack=ISS+1;
    ESTABLISHED: seq=snd.nxt — syarat penerimaan RST RFC 9293) → app
    dapat ECONNRESET lalu fallback/reconnect; UDP → flow ditutup +
    ICMP/ICMPv6 unreachable dgn kutipan header IP+UDP SINTETIS dari
    4-tuple flow (socket app mendapat EHOSTUNREACH). Verdict pemotongan
    = fungsi verdict jalur paket yang sama; flow app lain tak tersentuh
    (anti "bypass yang tidak disengaja": justru koneksi tua pelanggar
    yang ditutup).
  - Hasil dilaporkan: HUD "diterapkan ke sesi aktif" / counter / logView
    (`BLOCK/live: aturan per-app diperbarui (N app; …)[; X koneksi lama
    diputus]`); petunjuk reconnect lama DIHAPUS (tidak lagi relevan).
  - **LIVE_PARTIAL** (dilaporkan jujur, G10): bila mode BLOCK baru
    menargetkan versi IP yang belum ditangkap route TUN sesi ini (global
    non-dual tanpa upgrade start), verdict diterapkan tapi paket versi
    itu tidak pernah masuk TUN → HUD meminta restart utk penegakan
    penuh (route VpnService tidak bisa diubah setelah establish;
    re-establish in-place ditolak karena membuka jendela gap = bocor).

### Changed
- **Split tunnel tidak ambigu**: saat mode "Semua aplikasi", tombol
  "Pilih aplikasi..." DISABLED + alpha redup + teks counter "Semua
  aplikasi lewat VPN" (bukan jumlah pilihan yang tidak dipakai); mode
  "Hanya/Kecuali yang dipilih" mengaktifkan kembali tombol + counter
  jumlah app. Tombol tidak disembunyikan (tanpa layout shift);
  `glitchStateChange` sebelum `setEnabled` (pola updateAddBtn).
- Legenda Mode IP: baris prioritas kini "…Saat VPN aktif, perubahan
  langsung diterapkan." + baris baru batas mode lembut ("IPv4/IPv6
  (BYPASS) hanya aktif saat Mode IP global Dual-stack; BLOCK aktif di
  semua mode global."). Hint Setelan yang basi ("Hanya berlaku saat
  Mode IP global = Dual-stack.") diganti teks akurat (BLOCK + live).
- Prioritas aturan tetap **per-app > global** (perilaku cy10.7,
  tertulis di legenda; tidak ada perubahan verdict).

### Verifikasi
- Harness wire-format diperluas 40 → **53 assertion** (kutipan sintetis
  IP+UDP v4/v6: layout, arah, 4-tuple, pembungkusan ICMP) — menangkap
  satu bug nyata (byte versi IPv6 kutipan tidak terisi) SEBELUM build
  dirilis; SEMUA LULUS. Build debug (20MB) + release/R8 (6.2MB) lolos;
  simbol (debug) & string konstanta (debug+release) terverifikasi di
  dex. Celah baru yang tidak bisa ditutup dari VpnService didokumentasi
  (KNOWN_ISSUES §8 G10/G11) — TIDAK diklaim bebas bocor; validasi nyata
  menunggu checklist device C-tambahan no.6–7.

## [cy10.7] — 2026-10-09

### Added
- **BLOCK v4 / BLOCK v6 — mode IP per aplikasi** (dropdown Mode IP global
  TIDAK disentuh; hanya popup dialog "Mode IP per aplikasi"):
  - Popup 1-tap kini 5 baris: GLOBAL / IPv4 / IPv6 / **BLOCK v4** /
    **BLOCK v6**, label jelas, warna baru keluarga merah/oranye
    (BLOCK v4 = status_coral, BLOCK v6 = status_red; wash 15% seperti
    chip lain) + legenda di bawah search bar diperluas (arti kedua mode,
    baris prioritas "per-app menang atas global", catatan berlaku saat
    VPN dinyalakan ulang; HUD petunjuk reconnect muncul bila mengubah
    mode saat VPN jalan).
  - Arti: BLOCK v4 = app itu tidak boleh punya koneksi IPv4 sama sekali
    (hanya v6); BLOCK v6 kebalikannya. BUKAN bypass: tidak ada paket
    versi yang diblok keluar lewat jaringan asli atas nama app itu.
  - Penegakan di TUN (data plane Java VpnEngine, tanpa menyentuh engine
    Go): TCP versi diblok dijawab **RST** seketika (fallback instan,
    bukan timeout); UDP non-DNS di-drop + **ICMP host-unreachable (v4
    type 3 code 1) / ICMPv6 no-route (v6 type 1 code 0)** dengan kutipan
    paket asli; **auto dual-capture** — bila ada app BLOCK dan global
    bukan dual, versi yang diblok ikut ditangkap ke TUN (dilaporka di
    log start; konsekuensinya versi "bypass" app lain kini lewat tunnel).
  - **DNS (req 2c)**: query A (BLOCK v4) / AAAA (BLOCK v6) dijawab lokal
    **NOERROR + answer kosong (NODATA) — bukan NXDOMAIN**; versi yang
    diizinkan diteruskan, upstream-nya diarahkan menghindari versi yang
    diblok via tabel counterpart resolver yang sama (plain + DoT literal).
  - Atribusi per-app: `getConnectionOwnerUid` (TCP/UDP) + cache verdict
    per 4-tuple seumur sesi (SYN retransmit & query DNS berikutnya tak
    mengulang binder call); fail-open bila uid tak dikenal.
  - **Log ringkas (req g)**: jumlah paket di-drop + jawaban DNS NODATA
    diringkas per interval 30 dtk per app ke logView (bebas glitch),
    bukan per paket; ringkasan parsial terakhir saat VPN berhenti.
  - Verifikasi statis: harness Java murni 40 assertion wire-format
    (parser qname/qtype + fail-open, header NODATA, ICMP 3/1 & 1/0 arah/
    kutipan/cap, checksum IP/ICMP/pseudo-v6/RST) — SEMUA LULUS. Build
    debug + release/R8 lolos; simbol & string BLOCK terverifikasi di dex.
  - Audit kebocoran (req d) dilaporkan jujur di docs/KNOWN_ISSUES.md §8
    (G1–G9): split-tunnel bypass, ICMP tak teratribusi, DoH bawaan app,
    family upstream DoH/DoQ, resolver tanpa counterpart, jendela VPN
    mati, fail-open, konsekuensi dual-capture. TIDAK diklaim bebas bocor.

### Fixed
- **Navbar pill (cy10.7)**: indikator aktif kini dipusatkan vertikal
  terhadap BLOK ikon+label dihitung dari ukuran nyata hasil layout
  (row.getTop + item center; rumus lama memusatkan ke ikon dengan offset
  padTop+ikon/2 yang bisa meleset saat ITEM_MIN clamp/pembulatan) —
  hilangkan "pill sedikit lebih rendah". Asimetri pill luar (kiri lebih
  rapat) dihilangkan dengan kompensasi padding row dari lebar terukur
  label item tepi ("Beranda" vs "Setelan" proporsional) → inset kiri =
  inset kanan; item tetap seragam, indikator/sentuhan/glitch tak berubah.
- Popup mode IP: cap ukur tinggi kini mengikuti jumlah baris (5×48dp,
  bukan 200dp utk 3 baris) — posisi "di atas chip" tidak salah hitung.

## [cy10.6] — 2026-10-08

### Changed
- **Optimasi mekanisme rendering UI — visual 100% identik** (audit
  menyeluruh onDraw/Canvas/Paint/Bitmap/Shader/Path/invalidate/
  requestLayout/Choreographer/frame callback/animasi/overlay/alokasi;
  akar diverifikasi ke source AOSP; lihat docs/ARCHITECTURE.md §8):
  - **Background = nol kerja UI**: flag `resumed` (onPause/onResume) —
    thread monitor tetap mengambil data tiap 2 dtk (kontinuitas baseline
    rate & timeline grafik tidak berubah) tetapi berhenti men-dispatch
    `updateHeader` + seluruh blok UI dashboard (setText/layout/chips/
    invalidate pada view yang tidak tergambar = kerja sia-sia).
    Kontinuitas data grafik dijaga lewat `TrafficGraphView.insert()`
    (sisip data tanpa invalidate).
  - **Tint chip sesi hanya saat warna berubah**: `VectorDrawable
    .setTintList` membandingkan identitas objek & `Drawable.setTint(int)`
    selalu alokasi `ColorStateList` baru (AOSP 14: VectorDrawable:484,
    GradientDrawable:1229) → dulu `invalidateSelf()` + alokasi CSL +
    color-filter PER CHIP PER TICK walau warna sama; kini di-guard int
    (`ChipVh.lastTint`).
  - **`findViewById` chip sekali seumur chip** (holder `ChipVh`), bukan
    3× per chip per tick 2 dtk (hingga 60 traversal hierarki/tick).
  - **Grafik trafik datar tidak digambar ulang**: `addSample` melewatkan
    `postInvalidate` bila buffer penuh + seluruh sampel homogen + nilai
    baru = terakhir (output piksel identik; verifikasi homogenitas
    O(CAP) hanya saat kandidat skip).
  - **TICK ambient tanpa alokasi**: scratch list statis menggantikan
    2 `ArrayList` baru per tick (950–1500ms selamanya).
- Diverifikasi-optimal (tidak diubah): `VectorDrawable.setAlpha` no-op
  bila nilai sama (denyut dot hanya redraw saat alpha kuantisasi berubah),
  `TextView.setTextColor`/`View.setAlpha` no-op bila nilai sama, tidak
  ada `setLayerType`/hardware layer/animated drawable, scanline bitmap
  sudah sekali-alokasi (cy10.2). Glitch: frekuensi/intensitas/timing/
  warna/posisi/ukuran TIDAK disentuh; ambient & event tetap seperti
  cy8–cy10.3.

### Pending (perlu konfirmasi user — docs/KNOWN_ISSUES.md §B K8/K9)
- K8: guard `setTextIfChanged` utk `vpnStatsView`/`headerStats`/teks chip
  sesi (menghentikan kilat saat nilai statis = perubahan frekuensi visual).
- K9: lewati `fetchStatus`+parse JSON saat background (biaya: satu nilai
  rate rata-rata pada tick pertama pasca-resume).

## [cy10.5] — 2026-10-08

### Fixed
- **Crash startup (NPE) `CyberNavBar.setMenu`** — cy10.4 as-pushed crash
  DETERMINISTIK di setiap cold start (terlihat di device Android 16/One
  UI; berlaku di semua API 34+). Akar (docs/KNOWN_ISSUES.md §7, diverifikasi
  ke source AOSP): probe ukur label dipakai ulang TANPA parent →
  `measure` iterasi 1 membentuk `mLayout` → `setText` iterasi 2 masuk
  `TextView.checkForRelayout()` yang pernyataan pertamanya membaca
  `mLayoutParams.width` TANPA null-guard (android-14:11246, main:11679) →
  probe tak pernah di-addView/setLayoutParams → NPE. Fix by construction:
  (1) probe BARU per judul + `setLayoutParams(WRAP_CONTENT)` sebelum
  `setText`/`measure`; (2) label item dipasang (`addView`, LP terisi)
  sebelum `setText`. `measure(UNSPECIFIED)` membaca spec, bukan LP →
  dimensi item/pill/glitch identik, murni fix crash. Catatan trace: nomor
  baris `:169`/`:902` dari user = remap R8 build release, bukan baris
  source sebenarnya.

## [cy10.4] — 2026-10-08

### Added
- `CyberNavBar` — kelas navbar pill melayang buatan sendiri (view
  framework polos: FrameLayout/LinearLayout/ImageView/TextView/View),
  pengganti TOTAL `BottomNavigationView` material. Bentuk pill dari SATU
  sumber (`bg_nav_pill.xml` capsule) + `clipToOutline`; elevation selalu
  0; TANPA hardware layer / bitmap snapshot / alpha pada container.
- `bg_nav_indicator.xml` — indikator item aktif sebagai SATU view
  terpisah (capsule 64×32dp, `m3_primary_container`) yang bergeser
  `translationX` ke item terpilih (ValueAnimator 240ms decelerate;
  instan saat skala animator sistem = 0 — cache + ContentObserver
  settings).
- `res/color/nav_item_icon_tint.xml` + `nav_item_text_tint.xml` —
  selector warna item (state_selected; nilai identik era BNV via
  ThemeOverlay).
- Item navbar dibangun dari `R.menu.bottom_nav` (satu sumber id/ikon/
  judul; parser XmlPullParser TYPED `AttributeSet` — referensi biner
  hex `@0x7f...`).

### Changed
- **Navbar ROMBAK (akar artifact kotak hitam terverifikasi)**:
  `BottomNavigationView` + `Widget.Wpmulti.Nav` +
  `ThemeOverlay.Wpmulti.NavPill` DIHAPUS dari layout/tema/kode. Bukti
  akar (lihat docs/KNOWN_ISSUES.md §4): (1) forensik piksel screenshot
  — kotak hitam opaque = PERSIS bounds BNV 300×56dp; (2) bytecode
  material-1.14.0 — `background="@null"` justru membuat lib memasang
  MaterialShapeDrawable baru (NavigationBarView ctor 148-157), dan
  `app:elevation` style lib 3dp MENIMPA `android:elevation="0dp"` XML
  (ctor 171-172) → jalur compositing layer yang membeku jadi kotak
  hitam pada pipeline Samsung One UI/Android 16 saat pindah tab.
- Ukuran navbar compact dari isi: lebar item = label terlebar + 2×10dp
  (pill ±260dp, dari 312dp); tinggi item = ikon 20dp + gap 3dp + tinggi
  font terukur + padding 7/7dp (label tidak terpotong — dari font,
  bukan fixed 56dp; pill ±61dp); area sentuh item ≥48dp.
- Efek glitch navbar kini HANYA pada item yang kehilangan/mendapat
  active state (item lama MINOR, item baru MEDIUM + jitter — API beku
  cy7/cy8), dipicu internal `CyberNavBar.select()`; tekan item =
  feedback tepat sasaran (korupsi label + micro-jitter item, pola
  tombol cy8) — jitter seluruh pill saat tekan DIHAPUS (spesifikasi
  baru: efek hanya pada item yang berubah).
- Gerakan indikator = perubahan STATE (bukan efek): selalu bergerak
  walau mode glitch Mati / animator sistem 0; snap tanpa animasi pada
  layout pertama/rotasi.
- `MainActivity`: `glitchNavItems()`/`lastNavItemId` dihapus (logika
  pindah ke dalam bar); handler navigasi diekstrak ke
  `handleNavSelection()` (dipakai listener + sekali di cold start —
  identik efek samping BNV lama yang memicu listener saat
  `setSelectedItemId` awal).

### Fixed
- **Kotak hitam persegi menutupi navbar** (artifact kambuhan lintas
  cy3/cy8/cy10.3, memotong ikon & label, sudut persegi menonjol di
  ujung pill): akar = mesin background/elevation tersembunyi
  BottomNavigationView yang TIDAK bisa dimatikan dari XML. Fix by
  construction: komponen lib dihapus total dari navbar — tidak ada
  lagi view/mesin di area navbar yang bisa menghasilkan kotak atau
  layer (dokumentasi pencegahan permanen di KNOWN_ISSUES §4).
- Label navbar terpotong di bawah: tinggi item kini dihitung dari
  metrik font + padding (bukan fixed 56dp BNV).

## [cy10.3] — 2026-10-08

### Added
- Popup pemilih mode IP per aplikasi (1 tap): tap chip membuka menu
  anchored GLOBAL/IPv4/IPv6 berlabel + deskripsi + penanda mode aktif
  (48dp per baris) — mengganti siklus tekan berulang; bahasa visual &
  animasi sama dengan GlitchDropdown (scanline + materialize/vanish
  staggered, guard `isGlitchEnabled`), auto-dismiss saat dialog host
  ditutup.
- Legenda arti mode/warna di bawah search bar dialog "Mode IP per
  aplikasi" (dot warna + penjelasan singkat, satu sumber string resource
  bersama chip & popup).
- `docs/ARCHITECTURE.md`, `docs/GLITCH_RULES.md`, `docs/KNOWN_ISSUES.md`,
  `docs/NEW_SESSION.md`, dan `CHANGELOG.md` ini.

### Changed
- Search bar dialog "Pilih aplikasi" & "Mode IP per aplikasi" kini
  STICKY: berada di luar area scroll (judul + search + legenda tetap
  terlihat saat list di-scroll); keyboard & fokus tetap berfungsi,
  tanpa layout shift.
- Navbar pill COMPACT & terpusat: tinggi total 64dp (dari 80dp), lebar
  mengikuti isi 4 item (±222dp, konstan di semua ukuran layar — BNV
  `layout_width=300dp` mengunci lebar item ~52dp), label 11sp satu
  ukuran (tidak "melompat" saat pindah tab), area sentuh item ≥48dp.
- Chip mode IP: area sentuh 48dp (dari 30dp); row dialog dirapatkan.

### Fixed
- **Akar "tombol mode IP harus ditekan berkali-kali"**: `GlitchText.
  burst()` memakai teks dasar `PENDING` yang basi — kilatan press
  (ACTION_DOWN) menangkap label lama, kilatan perubahan (onClick) lalu
  men-`setText` label lama kembali menimpa label baru, sehingga yang
  terlihat berubah hanya warna. Kini PENDING divalidasi: karakter teks
  berubah = base basi dibuang; label per tekan langsung terlihat.
  Visual glitch (span/shadow/durasi/intensitas) tidak berubah.
- **Akar artifact kotak di tepi kiri/kanan pill**: pill lama =
  `FloatingToolbarLayout` (MaterialShapeDrawable capsule + elevation 6dp
  pada view 90% translucent) + background persegi `BottomNavigationView`
  yang hanya di-tint transparan. Diganti struktur polos (FrameLayout +
  capsule XML + elevation 0 + `background=@null`) — mesin penghasil kotak
  dihilangkan, bukan ditutup warna; tidak muncul lagi setelah pindah tab
  berulang.

## [cy10.2] — 2026-10-08

### Changed
- Optimasi INTERNAL sistem glitch (hasil visual identik, dibuktikan per
  jalur): pause/resume pulse dropdown di onPause/onResume (hemat ±6900
  post/jam di background), guard `isShown()` pada `burst()` (early-return
  sebelum alokasi span untuk view tak terlihat), bitmap pola scanline
  dibuat sekali di `init()`.

### Fixed
- Posisi centang dialog "Pilih aplikasi" dikerjakan ulang dengan benar:
  box digeser +2dp DI DRAWABLE `cb_cyber.xml` (inset kiri 2dp), bukan
  padding (padding `CompoundButton` hanya menjauhkan teks dari box —
  diverifikasi dari source AOSP 14/15/16).

## [cy10.1] — 2026-10-08

### Fixed
- Keyboard tidak muncul di search field kedua dialog (akar framework:
  `FLAG_ALT_FOCUSABLE_IM` dipasang AlertController karena custom view
  belum dianggap editor teks; fix `fixDialogIme` + softInputMode
  eksplisit + click→showSoftInput).
- Geser checkbox +2dp dari tepi dialog (metode padding — dikerjakan
  ulang di cy10.2, lihat atas).

## [cy10] — 2026-10-08

### Fixed
- Dropdown mini/terpotong: ukur AT_MOST SEMUA item + baris popup memakai
  layout dropdown via jembatan `getDropDownView` + fading edge off.

### Changed
- SATU tema dialog cybercore (`Theme.WpmultiTest.Dialog`): panel opaque +
  stroke neon, judul/tombol/checkbox/search terminal/chip mode IP/
  divider/scrollbar/ripple; window-anim & kilat status digratiskan ke
  `isGlitchEnabled()` (menutup gap cy9: mode Selalu aktif + animator
  scale 0 tetap mendapat window anim glitch).

## [cy9] — 2026-10-08

### Added
- Mode efek di halaman Setelan: **Auto (ikuti sistem) / Selalu aktif
  (default) / Mati** — glitch tetap jalan walau skala animator sistem
  = 0 (semua penggerak waktu efek = Handler/Choreographer sendiri,
  keputusan lewat satu fungsi pusat `isGlitchEnabled()`); beralih ke
  Mati membersihkan semua state visual.

## [cy8.1] — 2026-10-08

### Fixed
- Semua dropdown tidak muncul: `PopupWindow` tanpa `setContentView`
  adalah no-op senyap (guard `mContentView == null` AOSP).

## [cy8] — 2026-10-08

### Added
- GlitchDropdown: dropdown custom bertema + pulse berkala + vanish saat
  pilih (pengganti popup platform Spinner yang tidak bisa dianimasikan).

### Changed
- Rework total event-driven: korupsi ketikan per-karakter (span region,
  tanpa `setText` — cursor/IME aman), page transition korosi 7 fase
  (generation counter), semua efek lewat framework `step()` yang
  terlacak & deterministik.

### Fixed
- Artifact kotak navbar tahap 1: guard elevation untuk semua efek alpha
  (view ber-elevation tidak di-alpha; pill nav memakai jitter murni).
- Dead code cabang Spinner di `installTouch` (tertutup cabang
  ViewGroup) — akar "dropdown masih UI Android biasa".

## [cy7] — 2026-10-08

### Changed
- Presisi event: glitch per-elemen yang benar-benar berubah (item nav
  lama/baru masing-masing, chip sesi, row config baru, dll); HUD
  muncul/hilang karena glitch; guard race double-flicker RGB MAJOR.

## [cy6] — 2026-10-08

### Added
- Glitch sebagai identitas UI: hierarki MINOR/MEDIUM/MAJOR + API
  reusable (`glitchNow/glitchTree/glitchMajor/glitchView/glitchJitter/
  glitchAppear/glitchDisappear`), wander lebih sering, dropdown pulse,
  press feedback semua Button/CheckBox, dialog muncul karena glitch.

## [cy5] — 2026-10-07

### Added
- Event-driven di semua interaksi: TextWatcher massal (burst otomatis
  dengan guard reentrant + throttle), EditText mode INPUT per ketikan,
  `installTouch` Button/Spinner, row config `glitchAppear`.

## [cy4] — 2026-10-07

### Added
- GlitchText "banyak tapi tipis": ghost baseline merah 20% untuk semua
  TextView, wander burst (span neon, strike-through, blok datamosh,
  shadow RGB), restore dengan guard `TextUtils.equals`.

## [cy3] — 2026-10-07

### Added
- HUD notifikasi in-app bergaya terminal (pengganti Toast).

### Fixed
- Box abu-abu Toast di atas pill.

## [cy2] — 2026-10-07

### Added
- Lapisan glitchcore: statusBar ghost RGB-split, divider scanline
  dashed, `status_red` glitch, resource warna glitch.

## [cy1] — 2026-10-07

### Changed
- Tema cybercore dark-only: palet neon cyan/magenta di kanvas near-black
  #0B1119, values-night dihapus, warna status ke resource, remap
  `colorSecondaryContainer`.

### Fixed
- Crash bounce spring saat state engine berubah (finalPosition +∞).

## [pra-cy: fase 1–4 + v2.0] — 2026-10-07

### Added
- fase4: elemen hidup (LoadingIndicator, denyut dot, morph sudut tombol,
  spring bounce).
- fase3: kartu Monitor & VPN (throughput besar, chip sesi in-place,
  grafik trafik `TrafficGraphView` theme-aware).
- fase2: navbar pill melayang pertama + transisi halaman.
- v2.0 (overhaul 1–7): komposisi UI baru — bottom nav 4 tab menggantikan
  drawer, hero Beranda, halaman sesi profil/proxy/uji, setelan 2 kartu,
  log konsisten, token tertiary; ruang kosong atas dihapus.

(Catatan: fase cy1–cy10.x belum diberi git tag — versi dilacak dari
pesan commit di branch `ui/floating-nav`.)
