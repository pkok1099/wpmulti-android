# ARCHITECTURE — wpmulti-android

Peta arsitektur proyek untuk melanjutkan pekerjaan tanpa membaca seluruh
riwayat commit. Terakhir diperbarui: **cy10.4** (2026-10-08).

## 1. Struktur proyek

```
app/src/main/java/com/wpmulti/test/
├── MainActivity.java      — Activity utama (2.8k baris): 4 halaman
│                            (Beranda/Sesi/Log/Setelan), semua dialog,
│                            monitor 2 dtk, konfigurasi profil, HUD.
├── VpnControlActivity.java— Activity translucent pengganti dialog VPN.
├── TestActivity.java      — Activity uji (HTTP proxy test).
├── GoEngineService.java   — Service :goengine (host runtime Go/gomobile).
├── VpnEngine.java         — JNI ke tun2socks/engine Go, service VPN.
├── EngineClient.java      — AIDL IEngineControl ke proses :goengine.
├── EngineStatus.java      — Value object status engine.
├── ProxyKeepaliveService.java — Foreground service keep-alive proxy.
├── VpnTileService.java    — Quick settings tile VPN.
├── GlitchText.java        — MESIN GLITCH (lihat §4). Satu-satunya
│                            sumber API efek visual teks/view.
├── GlitchDropdown.java    — Popup dropdown pengganti popup Spinner.
├── CyberNavBar.java       — Navbar pill melayang pengganti BottomNavigation-
│                            View (cy10.4; view framework polos — lihat
│                            KNOWN_ISSUES §4 utk akar artifact kotak hitam).
└── SelfAnim.java          — Penggerak animasi custom (Choreographer
                             sendiri) — tetap jalan walau skala animator
                             sistem = 0.
app/src/main/res/
├── layout/activity_main.xml — rootMain FrameLayout: 4 halaman (page*),
│                             hudToast, navPill (CyberNavBar capsule).
├── layout/row_*.xml       — row config / proxy / sesi.
├── layout/spinner_item.xml + spinner_dropdown_item.xml — item Spinner.
├── drawable/              — bg_dialog, bg_dropdown, bg_nav_pill,
│                             bg_nav_indicator (indikator aktif nav),
│                             bg_search_terminal, bg_chip_mode_{global,
│                             v4,v6}, cb_cyber, cursor_cyber,
│                             divider_scanline, scrollbar_cyber, ikon.
├── color/                 — nav_item_icon_tint / nav_item_text_tint
│                             (selector state_selected item navbar).
├── menu/bottom_nav.xml    — SATU sumber id/ikon/judul item navbar
│                             (dibaca CyberNavBar.setMenu via parser
│                             XmlPullParser TYPED; id tidak pernah berubah).
├── values/themes.xml      — SEMUA style: Theme.WpmultiTest (M3E dark),
│                             Theme.WpmultiTest.Dialog (SATU tema semua
│                             dialog), Widget.Wpmulti.* (Card/TextField/
│                             ModeChip/CheckBox/EditText.Terminal),
│                             TextAppearance.Wpmulti.NavLabel (label nav).
├── values/colors.xml      — palet cybercore (satu sumber, dark-only).
├── values/dimens.xml      — token sudut 4/6/8dp, nav_pill_clearance.
└── values/strings.xml     — label mode IP (chip/popup/legenda).
```

Build: `./gradlew assembleDebug` (JDK 17). Output:
`app/build/outputs/apk/debug/app-debug.apk`.

## 2. Modul utama & alur data

- **Engine (Go)** hidup di proses terpisah `:goengine` — Java mengontrol
  lewat AIDL (`EngineClient` → `IEngineControl`). Status engine dipoll /
  didorong ke `EngineStatus` snapshot.
- **VpnEngine** mengelola TUN + konfigurasi sesi (profil dari file conf,
  maks 5 profil × 240 sesi per konfigurasi).
- **MainActivity** = satu-satunya UI "berat". State engine:
  `ST_IDLE/STARTING/RUNNING/STOPPING` + watchdog start 60 dtk.
- **Per-app (split tunnel)**: `vpn_apps` (StringSet) di SharedPreferences
  `vpn`; **mode IP per aplikasi**: key `vpn_app_ip_<pkg>` (""=global,
  `"v4"`, `"v6"`), berlaku saat VPN connect berikutnya.

## 3. Alur UI

```
rootMain (FrameLayout, edge-to-edge, inset via listener)
├── pageHome / pageSesi / pageLog / pageSetting (GONE/VISIBLE;
│   pergantian = showPage() → GlitchText.pageTransition 7 fase)
├── hudToast   — notifikasi in-app (pengganti Toast), glitchAppear/Disappear
└── navPill    — CyberNavBar (cy10.4): pill capsule SATU sumber
                 (bg_nav_pill.xml) + clipToOutline, elevation 0, view
                 framework polos TANPA mesin lib (akar artifact kotak
                 hitam — KNOWN_ISSUES §4). Isi: LinearLayout 4 item
                 (ikon 20dp + label 11sp, sentuh ≥48dp) + SATU view
                 indikator aktif (bg_nav_indicator.xml 64×32dp) yang
                 bergeser translationX ke item terpilih. Lebar mengikuti
                 isi (~260dp), terpusat bawah. Konten lewat DI BELAKANG
                 pill.
```

Pindah tab = klik item `CyberNavBar` → internal `select()`: efek glitch
HANYA pada item yang berubah (lama MINOR, baru MEDIUM + jitter, API beku
cy7/cy8) + indikator bergeser (state, bukan efek) → listener →
`handleNavSelection()` → `showPage()` → `GlitchText.pageTransition`
7 fase.

## 4. Sistem glitch (GlitchText)

**Prinsip**: event-driven — "sesuatu yang berubah, sesuatu itulah yang
glitch". Glitch adalah lapisan VISUAL; tidak pernah memblokir input, dan
state UI selalu berubah normal (bahkan saat efek mati).

### Hierarki intensitas (BEKU — jangan diubah)
- `MINOR`  — ketikan, teks dinamis, press tombol, checkbox, nav item.
- `MEDIUM` — dropdown, dialog, config, split tunnel, panel muncul.
- `MAJOR`  — page navigation, state engine, rekonstruksi list besar.

### API inti
| API | Fungsi |
|---|---|
| `glitchNow(tv, level)` | korupsi span + kilat shadow pada SATU TextView |
| `glitchTree(root, level)` | glitch semua TextView terdaftar di bawah root |
| `glitchMajor(root)` | glitchTree MAJOR + displacement container |
| `glitchView(v, level)` | flicker alpha non-teks (guard elevation) |
| `glitchJitter(v, level)` | displacement translationX murni (aman utk pill) |
| `glitchAppear / glitchDisappear(v)` | elemen muncul/hilang karena glitch |
| `glitchStateChange(v, enabled)` | state enabled berubah |
| `pageTransition(out, in)` | transisi halaman 7 fase (generation counter) |
| `materializeStaggered / vanishStaggered(g)` | fragmen anak container |
| `scanline(host, ms)` / `clearScanline` | overlay scanline ViewOverlay |
| `registerTree / registerCustom / installTouch` | pendaftaran & wiring |
| `setMode / isGlitchEnabled / setAnimScale` | mode & keputusan pusat |

### Penggerak waktu (semua bukan Animator sistem)
1. `GlitchText.H` (Handler main looper) — TICK ambient (wander 950–1500ms
   memilih 2–5 teks terdaftar; jeda 1,5 dtk setelah event) + RESTORE
   (pelepasan span/shadow 120–190ms) + semua langkah `step()`.
2. `GlitchDropdown` Handler sendiri — pulse dropdown 380–650ms saat popup
   terbuka (di-pause `onPause`, resume `onResume`).
3. `SelfAnim` (Choreographer) — denyut dot, morph tombol, bounce spring.

### Mode efek & `isGlitchEnabled()` (cy9)
```
MODE_AUTO      → aktif hanya bila skala animator sistem > 0
MODE_ALWAYS_ON → aktif selalu (DEFAULT) — semua penggerak waktu efek
                 adalah Handler/Choreographer sendiri, bukan Animator
                 sistem, jadi "hapus animasi" sistem tidak mematikannya
MODE_OFF       → mati total; beralih ke OFF mempurge semua state visual
```
`isGlitchEnabled()` = SATU fungsi pusat keputusan untuk SEMUA efek
(GlitchText, GlitchDropdown, window-anim dialog/popup, elemen hidup
SelfAnim). **Baca saat event terjadi, bukan di-cache.**

### Pemulihan state (anti-artifact)
- `step()` mencatat baseline `{alpha, translationX, scaleX, ELEVATION}`
  per view; langkah yang sudah terjadwal SELALU berjalan sampai nilai
  final (deterministik) atau dibatalkan `cancelFor()` yang memulihkan
  baseline.
- `guardElevation()` — view ber-elevation tidak boleh di-alpha < 1
  (offscreen layer memotong shadow jadi KOTAK). Efek pada pill nav
  hanya `glitchJitter` (translationX), TIDAK PERNAH alpha.
- `PENDING` = teks dasar untuk restore span; **cy10.3**: base hanya
  dipakai bila karakternya masih sama — bila aplikasi mengganti teks
  (mis. label chip mode setelah pilih), PENDING basi dibuang agar label
  baru tidak tertimpa teks lama.
- logView (`R.id.logView`) 100% bebas glitch (walk/collect/burst/
  fragmen melewatinya).

## 5. Sistem tema

- **Dark-only cybercore**: kanvas near-black `#0B1119`, aksen cyan
  `#22D3EE` + magenta `#F0ABFC` (satu sumber: `values/colors.xml`;
  values-night dihapus). Parent: `Theme.Material3Expressive.Dark.
  NoActionBar`.
- Sudut tegas 4/6/8dp via `Shape.Wpmulti.*` + token `shapeCornerSize*`.
- `Widget.Wpmulti.*`: Card, TextField, ModeChip (48dp), CheckBox, EditText
  Terminal, Snackbar.
- **Navbar (cy10.4)**: `CyberNavBar` — BUKAN komponen lib. Pill capsule
  dari SATU sumber (`bg_nav_pill.xml`) + `clipToOutline` (outline capsule
  sendiri); elevation 0; indikator aktif = view terpisah
  (`bg_nav_indicator.xml`, 64×32dp, `m3_primary_container`); warna item via
  selector `res/color/nav_item_{icon,text}_tint.xml` (aktif
  `m3_on_primary_container`, nonaktif `m3_on_surface_variant` — identik
  era BNV); label `TextAppearance.Wpmulti.NavLabel` 11sp.
  `Widget.Wpmulti.Nav` + `ThemeOverlay.Wpmulti.NavPill` DIHAPUS.
  Larangan permanen (akar artifact kotak hitam, lihat KNOWN_ISSUES §4):
  komponen navigasi Material TIDAK PERNAH dipakai di dalam pill —
  `background="@null"`/`elevation="0dp"` di XML terbukti TIDAK mematikan
  mesin background/elevation tersembunyinya.

## 6. Dialog

Semua `android.app.AlertDialog` bertema **SATU sumber**:
`Theme.WpmultiTest.Dialog` (dipasang via `android:alertDialogTheme` +
`dialogCtx()` ContextThemeWrapper untuk widget programatis). Isi: panel
opaque `bg_dialog.xml` + stroke neon, judul/tombol monospace, search
terminal, checkbox cybercore, chip mode IP, divider scanline, scrollbar +
ripple cyan.

Struktur dialog "Pilih aplikasi" & "Mode IP per aplikasi" (cy10.3):

```
AlertDialog
├── Judul (selalu terlihat — bagian dari window)
└── custom view = buildSearchList(): LinearLayout vertikal
    ├── EditText search   ← STICKY: di luar area scroll
    ├── [legenda mode IP] ← hanya dialog Mode IP
    └── ListView          ← hanya ini yang scroll
```

Keyboard: `fixDialogIme()` (clear `FLAG_ALT_FOCUSABLE_IM` +
`SOFT_INPUT_STATE_VISIBLE|ADJUST_RESIZE`) + click→`showSoftInput`.
Muncul karena glitch: window-anim `GlitchWindowAnim`/`...Off` (guard
`isGlitchEnabled`) + `scanline(dec)` + `glitchTree(lv, MEDIUM)` +
`materializeStaggered(lv)`.

## 7. Dropdown & popup

- **Spinner** → `GlitchDropdown.show(sp)` (dipasang otomatis oleh
  `GlitchText.installTouch`; Spinner dicek SEBELUM cabang ViewGroup).
  Popup = ListView bertema `bg_dropdown`, ukuran diukur AT_MOST semua
  item (cap 280dp / ruang layar), choiceMode single menandai nilai
  sekarang, pulse berkala saat terbuka. Pilih = kilat scanline + vanish
  staggered + `sp.setSelection` (listener state nyata tetap milik
  pemanggil).
- **Popup mode IP per aplikasi (cy10.3)** — `MainActivity.
  showIpModeMenu(anchor, key, vp)`: popup 3 baris (GLOBAL/IPv4/IPv6, 48dp,
  baris aktif berlatar wash token chip), 1 tap = pilih; bahasa visual
  sama dgn GlitchDropdown; auto-dismiss saat anchor (chip) lepas dari
  window — termasuk saat dialog host ditutup.
