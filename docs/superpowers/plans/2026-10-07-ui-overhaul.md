# UI Overhaul v2 — Komposisi Baru (Bukan Reskin) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Mengganti total komposisi UI wpmulti-test — navigasi sidebar-drawer diganti Bottom Navigation 4 tab, halaman disusun ulang (hero kontrol + kartu fungsional) — tanpa mengubah logika bisnis dan tanpa dependency baru.

**Architecture:** `activity_main.xml` ditulis ulang dari nol: MaterialToolbar + FrameLayout 4 halaman (Beranda/Sesi/Log/Setelan) + `BottomNavigationView`. Sidebar, scrim, btnMenu, menu item lama DIHAPUS (bukan dibungkus). MainActivity hanya berubah di blok navigasi (~60 baris: hapus openSidebar/closeSidebar, showPage jadi by-ID, wiring tab baru). Fitur dipetakan penuh via tabel di bawah — tidak ada fitur yang boleh hilang.

**Tech Stack:** material 1.14.0 (sudah terpasang; BottomNavigationView + MaterialToolbar + MaterialCardView dari lib ini), AGP 8.13.2, compileSdk 36, minSdk 34 — TANPA dependency baru.

**Spec:** Permintaan user (verbatim): "saya ingin UI baru dan komposisi baru tanpa mengikuti UI dan komposisi lama, benar-benar baru" — REVISI dari plan v1 (8e74d7e) yang masih reskin. Konstrain yang tetap berlaku dari sesi: sudut token 4/6/8dp, palet "Orkid Tegas" (light `#8D32AE` / night `#E5A0E2`), zero emoji (vector only), "pastikan selalu push setelah merubah sesuatu", disiplin ponytail (material skill: `ponytail` full).

## Peta Pemetaan Fitur (anti-fitur-hilang — SIFATNYA WAJIB)

| Lokasi baru | ID yang wajib ada (dipakai Java) |
|---|---|
| **Beranda — Hero** | `statusBar`, `monGo`, `btnEngine` |
| **Beranda — Kartu VPN** | `vpnStatus`, `vpnStats`, `vpnToggleBtn`, `pingTestBtn`, `pingResult` |
| **Beranda — Kartu Monitor** | `trafficGraph`, `headerStats`, `monRam`, `monCpu`, `monCache`, `monSesi`, `monSesiDetail` |
| **Sesi — Kartu Profil** | `totalView`, `configList`, `addBtn` |
| **Sesi — Kartu Proxy** | `proxyTable`, `proxyStatus` (dashboard) |
| **Sesi — Kartu Uji Proxy** | `testUrl`, `test1Btn`, `test20Btn`, `testResult`, `verifyView` |
| **Log** (pindah apa adanya) | `logLevel`, `copyLogBtn`, `clearLogBtn`, `logFilter`, `logScroll`, `logView` |
| **Setelan — Kartu VPN & Trafik** | `vpnDnsMode`, `vpnDnsServer`, `vpnIpMode`, `vpnAppMode`, `vpnPickAppsBtn`, `vpnAppCount`, `vpnPickIpModeBtn`, `vpnIpModeAppCount`, `vpnAutoReconnect`, `vpnSysSettingsBtn` |
| **Setelan — Kartu Aplikasi** | `settingVer`, `settingDevice`, `settingPaths`, `exportLogBtn`, `clearCacheBtn` |
| **Rows** (desain baru, ID tetap) | `label,minus,count,plus,max,del` / `proxyName,proxyAddr,proxyStatus,proxyCopy` |
| **DIHAPUS dari komposisi & Java** | `btnMenu`, `scrim`, `sidebar`, `menuDashboard`, `menuSetting`, `menuLog`, `pageDashboard` (→ `pageHome`) |

## Global Constraints

- TIDAK ADA dependency baru; hanya widget material 1.14.0.
- Nol hex baru di layout; warna hanya via token `@color/m3_*` / `@color/sidebar_*` (+ `m3_tertiary` baru di Task 5). Warna status semantik Java tidak diubah.
- Sudut semua komponen ikut token 4/6/8dp (`Shape.Wpmulti.*` / style turunan) — dilarang cornerSize literal.
- Zero emoji; icon vector drawable saja.
- KOMPOSISI LAMA DIGANTI: sidebar-drawer + scrim + tombol hamburger tidak boleh ada sisa di layout maupun Java.
- Setiap Task diakhiri commit + push ke `main` + `wptest-v1.5`.
- versionCode/versionName hanya di Task 7.

## Review Focus

1. **Fitur hilang** — ID yang di-referensi `MainActivity` tapi tidak ada di layout baru → NPE runtime. Pin: script ID-diff (grep semua `R.id.` di Java vs semua `@+id/` di layout) di Task 6.
2. **Semantik showPage** — `showPage(1)` lama = Setelan; komposisi baru salah map = klik tab buka halaman salah. Pin: Task 1 mengubah showPage jadi by-ID dan grep semua caller (baris 1614-1618 + lainnya) disesuaikan eksplisit.
3. **Status text di hero** — Java `setTextColor` per status (merah `FF5252`/amber/hijau); di atas `colorPrimary` gelap kontras buruk → hero memakai `colorPrimaryContainer` agar semua warna status terbaca di light+night. Pin: Task 2 inspeksi.
4. **Spinner/EditText di kartu** — keterbacaan default M3E di surface container. Pin: Task 3/4 build + halaman dibuka.
5. **Grid TrafficGraphView** — `0x33FFFFFF` tak terlihat di surface terang. Pin: Task 5 theme attr.

---

### Task 1: Kerangka komposisi baru — Bottom Navigation 4 tab

**Files:**
- Rewrite: `app/src/main/res/layout/activity_main.xml` (kerangka: Toolbar + 4 FrameLayout + BottomNavigationView; `pageLog` & `pageSetting` pindah apa adanya dulu; `pageHome`/`pageSesi` stub TextView)
- Create: `app/src/main/res/menu/bottom_nav.xml` (navHome/navSesi/navLog/navSetelan + icon + label)
- Create: `app/src/main/res/drawable/ic_config.xml` (Material Symbols "tune"; tab Sesi)
- Modify: `app/src/main/java/com/wpmulti/test/MainActivity.java` (blok navigasi saja)

**Interfaces:**
- Consumes: tema M3E + token existing.
- Produces: ID halaman `pageHome`, `pageSesi`, `pageLog`, `pageSetting`; menu id `navHome/navSesi/navLog/navSetelan`; fungsi `showPage(int pageId)` berbasis ID — dipakai semua task berikutnya.

- [ ] **Step 1: Tulis kerangka activity_main.xml baru** — root LinearLayout vertikal: `MaterialToolbar` (style `Widget.Material3.Toolbar.OnSurface`, title "wpmulti-test", tanpa menu) → `FrameLayout` weight=1 berisi 4 child FrameLayout (`pageHome` stub, `pageSesi` stub, `pageLog` = konten lama baris 218-260 pindah apa adanya, `pageSetting` = konten lama baris 263-468 pindah apa adanya, hanya pageLog visible) → `BottomNavigationView` (`app:menu="@menu/bottom_nav"`, labelVisibilityMode labeled). TIDAK ADA scrim/sidebar/btnMenu.

- [ ] **Step 2: Java — ganti blok navigasi.** Hapus `openSidebar()`+`closeSidebar()` (baris ~275-297) dan wiring sidebar (baris ~1608-1618). `showPage(int idx)` → `showPage(int pageId)` by-ID: visibility 4 halaman sesuai `pageId`. Wiring baru: `BottomNavigationView bnv = findViewById(R.id.bottomNav)` + `setOnItemSelectedListener`: navHome→`rebuildProxyTable(); updateVpnUi(); showPage(R.id.pageHome)`, navSesi→`rebuildProxyTable(); showPage(R.id.pageSesi)`, navLog→`showPage(R.id.pageLog)`, navSetelan→`showPage(R.id.pageSetting)`; return true. Grep semua caller `showPage(` lama dan samakan ke ID eksplisit (Review Focus #2). Inisialisasi akhir onCreate: `bnv.setSelectedItemId(R.id.navHome)`.

- [ ] **Step 3: Build + verifikasi nol sisa navigasi lama**

Run: `gradle assembleDebug`; `rg -n "btnMenu|scrim|sidebar|menuDashboard|menuSetting|menuLog|pageDashboard" app/src/main` → HARUS nol match.
Expected: BUILD SUCCESSFUL; nol sisa.

- [ ] **Step 4: Commit + push**

```bash
git add -A && git commit -m "feat(ui)! komposisi baru: bottom nav 4 tab, sidebar drawer dihapus (overhaul 1/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 2: Halaman Beranda — hero + VPN + Monitor

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml` (isi `pageHome`)
- Modify: `app/src/main/res/values/themes.xml` (+style `Widget.Wpmulti.Card`, `Widget.Wpmulti.HeroCard`)

**Interfaces:**
- Consumes: `Shape.Wpmulti.{Large,Medium}`; `pageHome` stub dari Task 1.
- Produces: `Widget.Wpmulti.Card` (MaterialCardView corner_large + surface_container_high) — dipakai Task 3/4; `Widget.Wpmulti.HeroCard` (corner_large + `m3_primary_container` bg).

- [ ] **Step 1: Hero card** — `Widget.Wpmulti.HeroCard`: `statusBar` (20sp bold, dot compound tetap mekanisme Java) + `monGo` (subtitle, onPrimaryContainer) + `btnEngine` (Widget.Material3.Button filled, 56dp, full-width, iconTint onPrimary).

- [ ] **Step 2: Kartu VPN** — `Widget.Wpmulti.Card`: heading "VPN (TUN)" (TitleMedium) + `vpnStatus`, `vpnStats`, `vpnToggleBtn` (TonalButton + ic_play), `pingTestBtn` (OutlinedButton), `pingResult`.

- [ ] **Step 3: Kartu Monitor** — `Widget.Wpmulti.Card`: `trafficGraph` (120dp, di ATAS stat — komposisi baru: grafik dulu baru angka) + `headerStats` + `monRam/monCpu/monCache/monSesi` (2 kolom LinearLayout) + `monSesiDetail` (monospace 12sp).

- [ ] **Step 4: Build + verifikasi ID Beranda**

Run: `gradle assembleDebug`; grep ID peta baris "Beranda" (10 ID) masing-masing ≥1 di pageHome.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit + push**

```bash
git add -A && git commit -m "feat(ui): beranda hero+vpn+monitor komposisi baru (overhaul 2/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 3: Halaman Sesi — profil, proxy, uji + rows baru

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml` (isi `pageSesi`)
- Rewrite: `app/src/main/res/layout/row_config.xml`, `row_proxy.xml` (ID tetap semua)
- Create: `app/src/main/res/drawable/ic_minus.xml`, `ic_plus.xml`, `ic_close.xml`, `ic_content_copy.xml`
- Modify: `app/src/main/res/values/themes.xml` (+`Widget.Wpmulti.TextField`)

**Interfaces:**
- Consumes: `Widget.Wpmulti.Card`; ID rows tetap (Java `row.findViewById` aman).
- Produces: `Widget.Wpmulti.TextField` (OutlinedBox + `Shape.Wpmulti.Small`) — dipakai Task 4.

- [ ] **Step 1: Kartu Profil** (`totalView`, `configList`, `addBtn` = TonalButton + ic_plus? tidak — upload = `ic_config` tidak cocok; addBtn tanpa icon cukup) + **Kartu Proxy** (`proxyTable`, `proxyStatus`) + **Kartu Uji** (`testUrl` di `TextInputLayout` `Widget.Wpmulti.TextField` — id pindah ke inner `TextInputEditText`; `test1Btn`+`test20Btn` sebaris weight 1; `testResult`, `verifyView`).

- [ ] **Step 2: row_config baru** — root MaterialCardView `Widget.Wpmulti.Card` (contentPadding 4dp, margin bawah 4dp); `minus`/`plus`/`del` → `Widget.Material3.Button.IconButton` + ic_minus/ic_plus/ic_close (iconTint onSurfaceVariant); `max` → OutlinedButton; `count` EditText center 56dp. ID semua tetap.

- [ ] **Step 3: row_proxy baru** — root MaterialCardView sama; `proxyName` (weight 1, medium), `proxyAddr` (monospace), `proxyStatus` (TextView — warna dari Java, jangan disentuh), `proxyCopy` → IconButton + ic_content_copy.

- [ ] **Step 4: Build + verifikasi ID Sesi & rows**

Run: `gradle assembleDebug`; grep ID peta "Sesi" (10 ID) + rows (10 ID).
Expected: BUILD SUCCESSFUL; `id/testUrl` tepat 1 match di `TextInputEditText` inner.

- [ ] **Step 5: Commit + push**

```bash
git add -A && git commit -m "feat(ui): halaman sesi profil/proxy/uji + rows kartu-iconbutton (overhaul 3/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 4: Halaman Setelan & Log — konsistensi kartu

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml` (re-org `pageSetting`; polish `pageLog`)
- Modify: `app/src/main/res/layout/` — `logFilter` wrap `Widget.Wpmulti.TextField`

**Interfaces:**
- Consumes: `Widget.Wpmulti.Card`, `Widget.Wpmulti.TextField`; semua ID peta "Setelan"/"Log" tetap.
- Produces: tidak ada.

- [ ] **Step 1: Setelan jadi 2 kartu** — "VPN & Trafik" (10 ID vpn*/`vpnAutoReconnect` + catatan 12sp) dan "Aplikasi" (`settingVer/settingDevice/settingPaths/exportLogBtn/clearCacheBtn`); heading TitleMedium; spinner & checkbox tidak dibungkus ulang (bawaan tema).

- [ ] **Step 2: Log** — `logFilter` wrap TextField; `copyLogBtn`/`clearLogBtn` OutlinedButton; sisanya pindahan Task 1 dibiarkan.

- [ ] **Step 3: Build + verifikasi ID Setelan/Log**

Run: `gradle assembleDebug`; grep 15 ID Setelan + 6 ID Log.
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit + push**

```bash
git add -A && git commit -m "feat(ui): setelan 2 kartu + log konsisten (overhaul 4/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 5: TrafficGraphView theme-aware + token tertiary

**Files:**
- Modify: `app/src/main/java/com/wpmulti/test/TrafficGraphView.java` (`init()` saja)
- Modify: `values/colors.xml`, `values-night/colors.xml` (+`m3_tertiary`: light `#B331A2`, night `#FDB0F0` — klaster image)
- Modify: `values/themes.xml`, `values-night/themes.xml` (+item `colorTertiary`)

**Interfaces:**
- Produces: token `m3_tertiary` — aksen kedua palet orkid.

- [ ] **Step 1: Token tertiary + item colorTertiary** di kedua tema.

- [ ] **Step 2: init() baca attr tema** — helper `attr(a)` via `MaterialColors.getColor(getContext(), a)`: grid = colorOutline alpha 0x33, label = colorOnSurfaceVariant, RX = colorPrimary, TX = colorTertiary.

- [ ] **Step 3: Build + push**

Run: `gradle assembleDebug` → SUCCESSFUL (Review Focus #5 tertutup).

```bash
git add -A && git commit -m "feat(ui): grafik trafik theme-aware + token tertiary (overhaul 5/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 6: Verifikasi menyeluruh anti-regresi

**Files:**
- Create (sementara, tidak di-commit): `scripts/check_ids.py`
- Test: ID-diff + build kedua varian

- [ ] **Step 1: Script ID-diff** — banding `R.id.X` dari `MainActivity.java` (plus row IDs) vs `@+id/` di semua layout. Expected: setiap R.id Java ada di layout, KECUALI daftar dihapus (`btnMenu, scrim, sidebar, menuDashboard, menuSetting, menuLog, pageDashboard`) yang juga harus nol di Java. Nol gap = lulus (Review Focus #1).

- [ ] **Step 2: Build debug + release** → SUCCESSFUL; badging masih versionCode 10 (bump di Task 7).

- [ ] **Step 3: Commit (jika ada fix) + push**

```bash
git add -A && git commit -m "fix(ui): perbaikan hasil verifikasi ID menyeluruh (overhaul 6/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 7: Release v2.0

**Files:**
- Modify: `app/build.gradle` (versionCode 11, versionName `'2.0'`), `README.md` (+baris 2.0)

- [ ] **Step 1: Bump + README** riwayat versi (komposisi baru: bottom nav, hero, kartu).

- [ ] **Step 2: Build debug + release** → `aapt2 dump badging` = `versionCode='11' versionName='2.0'`.

- [ ] **Step 3: Commit + push**

```bash
git add -A && git commit -m "release: v2.0 - komposisi UI baru (bottom nav + hero)"
git push origin HEAD:main HEAD:wptest-v1.5
```

- [ ] **Step 4: Upload APK** (`POST https://tmpfile.link/api/upload`, multipart `file`) + worklog Task 23.

---

## Self-Review

1. **Spec coverage:** "komposisi benar-benar baru" = navigasi sidebar→bottom nav (Task 1), susunan halaman baru hero-first (Task 2), regroup fitur (Task 3/4), chart ikut komposisi (grafik-di-atas-stat); konstrain lama (sudut/palet/no-emoji/push) = Global Constraints. ✓
2. **Step scan:** tiap step = satu aksi checkable dengan target & expected eksak. ✓
3. **Type consistency:** `pageHome/pageSesi/pageLog/pageSetting` + `navHome/navSesi/navLog/navSetelan` konsisten Task 1→4; `Widget.Wpmulti.Card` Task 2→3/4; `Widget.Wpmulti.TextField` Task 3→4; `m3_tertiary` Task 5 sendiri; rows ID tetap. ✓
4. **Review Focus:** #1→Task 6, #2→Task 1 step 2, #3→Task 2 step 1 (hero container), #4→Task 3/4 build, #5→Task 5. ✓
5. **Proportion:** plan ±200 baris untuk rewrite layout ±550 baris — proporsional. ✓
