# ARCHITECTURE — wpmulti-android

Peta arsitektur proyek untuk melanjutkan pekerjaan tanpa membaca seluruh
riwayat commit. Terakhir diperbarui: **cy10.3** (2026-10-08).

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
└── SelfAnim.java          — Penggerak animasi custom (Choreographer
                             sendiri) — tetap jalan walau skala animator
                             sistem = 0.
app/src/main/res/
├── layout/activity_main.xml — rootMain FrameLayout: 4 halaman (page*),
│                             hudToast, navPill (FrameLayout capsule).
├── layout/row_*.xml       — row config / proxy / sesi.
├── layout/spinner_item.xml + spinner_dropdown_item.xml — item Spinner.
├── drawable/              — bg_dialog, bg_dropdown, bg_nav_pill,
│                             bg_search_terminal, bg_chip_mode_{global,
│                             v4,v6}, cb_cyber, cursor_cyber,
│                             divider_scanline, scrollbar_cyber, ikon.
├── values/themes.xml      — SEMUA style: Theme.WpmultiTest (M3E dark),
│                             Theme.WpmultiTest.Dialog (SATU tema semua
│                             dialog), Widget.Wpmulti.* (Card/TextField/
│                             Nav/ModeChip/CheckBox/EditText.Terminal).
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
└── navPill    — FrameLayout capsule (bg_nav_pill.xml, elevation 0,
                 translucent 90%) berisi BottomNavigationView 4 tab
                 (56dp, label 11sp, lebar item ~52dp → pill ±222dp,
                 terpusat bawah). Konten lewat DI BELAKANG pill.
```

Pindah tab = `BottomNavigationView.setOnItemSelectedListener` →
`glitchNavItems()` (item lama MINOR, item baru MEDIUM — per item) →
`showPage()`.

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
- `Widget.Wpmulti.*`: Card, TextField, Nav (+ `ThemeOverlay.NavPill`
  scope warna indikator ke BNV saja), ModeChip (48dp), CheckBox, EditText
  Terminal, Snackbar.
- **Navbar pill (cy10.3)**: FrameLayout + `bg_nav_pill.xml` (capsule XML,
  radius 32dp = ½ tinggi 64dp) + elevation 0 — TANPA MaterialShapeDrawable
  lib / shadow (akar artifact kotak, lihat KNOWN_ISSUES). BNV:
  `background=@null`, 56dp, `layout_width=300dp` (mengunci lebar item
  ~52dp agar pill mengikuti ISI, bukan lebar layar).

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
