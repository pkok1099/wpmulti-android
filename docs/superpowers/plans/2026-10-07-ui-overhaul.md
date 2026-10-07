# UI Overhaul (Rombak Total v2.0) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rombak total permukaan UI wpmulti-test ke komponen Material 3 Expressive penuh (toolbar, kartu, textfield, row, chart, CTA) tanpa mengubah logika dan tanpa dependency baru.

**Architecture:** Semua perubahan = lapisan presentasi XML + spot-fix Java kecil (showPage, TrafficGraphView.init). Setiap `R.id` lama dipertahankan sehingga MainActivity 1917 baris tidak disentuh kecuali 2 fungsi. Identitas visual mengalir dari token yang sudah ada: sudut 4/6/8dp (dimens.xml), palet orkid (colors.xml light+night), tema M3E 2-lapis (themes.xml).

**Tech Stack:** material 1.14.0 (sudah terpasang), AGP 8.13.2, compileSdk 36, minSdk 34 — TANPA dependency baru.

**Spec:** Permintaan user sesi ini (verbatim): "terlalu round, buat kotak dengan sudut round kecil UI tegas dan elegant, ambil pallet color dari image tersebut, jangan lupa sidebar juga, icon (jangan gunakan emoji)" + "rombak total UI" + "pastikan selalu push setelah merubah sesuatu". Keputusan desain yang sudah dikunci sesi sebelumnya: sudut token 4/6/8dp; palet "Orkid Tegas" light `m3_primary #8D32AE` / night `m3_primary #E5A0E2`; sidebar custom bekerja (animasi 220ms + scrim + ripple) — dipertahankan, bukan dimigrasi.

## Global Constraints

- TIDAK ADA dependency baru — hanya widget dari `com.google.android.material:material:1.14.0`.
- Nol hex baru di layout: warna baru hanya lewat token `@color/m3_*` / `@color/sidebar_*` di colors.xml (light+night). Warna status semantik Java (`FF5252/FFD740/69F0AE` dst) tidak boleh diubah.
- Semua `android:id="@+id/..."` lama WAJIB tetap ada dengan tipe yang compatible (EditText→TextInputEditText, Button→MaterialButton/legal turunannya) agar `findViewById` di MainActivity tidak rusak.
- Sudut: semua komponen baru ikut token `@dimen/corner_{small,medium,large}` (4/6/8dp) via tema atau `Shape.Wpmulti.*` — dilarang menulis cornerSize literal di layout.
- Zero emoji; icon hanya vector drawable.
- Setiap Task diakhiri commit + push ke `main` + `wptest-v1.5` (instruksi eksplisit user: storage tidak persisten).
- versionCode/versionName hanya disentuh di Task 7.
- `ponytail:` skala sudut M3E bawaan (12dp+ pada Card/TextField) ditimpa style turunan — satu style per jenis komponen, bukan atribut berulang di tiap widget.

## Review Focus

1. **ID pindah ke inner EditText** saat migrasi TextInputLayout — kalau `@+id/testUrl` tertinggal di TextInputLayout (bukan inner), `findViewById` return bukan-EditText → NPE saat START. Pin: build lulus + grep ID di inner tag (Task 2 step verifikasi).
2. **Spinner di atas kartu** — default M3E bisa render teks tak terbaca di background container. Pin: build + buka halaman Setting (Task 2).
3. **showPage() di dark mode** — token harus di-resolve dari tema view saat itu, bukan konstanta. Pin: badging + inspeksi nilai token night (Task 4).
4. **TrafficGraphView di light mode** — grid `0x33FFFFFF` putih-alpha tak terlihat di surface `#FFFAFC`. Pin: build + theme attr (Task 5).
5. **Corner kartu & textfield** — style M3 default membawa radius 12dp+, melanggar constraint 4/6/8. Pin: style turunan dengan `Shape.Wpmulti.*` (Task 1 & 2).

---

### Task 1: Toolbar M3 + kartu section dashboard

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml` (topbar baris 14-41; tiga section dashboard)
- Modify: `app/src/main/res/values/themes.xml` (+1 style turunan kartu)
- Test: build + daftar ID

**Interfaces:**
- Consumes: `@dimen/corner_large` (8dp), `Shape.Wpmulti.Large`, token `@color/m3_*` — sudah ada.
- Produces: style `Widget.Wpmulti.Card` (MaterialCardView, corner_large, surface_container_high) — dipakai ulang Task 3 (row cards). ID `btnMenu`, `headerStats`, `statusBar` tetap hidup.

- [ ] **Step 1: Ganti topbar manual dengan MaterialToolbar**

`com.google.android.material.appbar.MaterialToolbar` (style `Widget.Material3.Toolbar.OnSurface`) sebagai child pertama konten, dengan child LinearLayout berisi `btnMenu` (IconButton, app:icon ic_menu), judul `@+id/headerTitle` (textAppearance `TextAppearance.Material3.TitleLarge`), `headerStats`. ID Java yang ada (`btnMenu`, `headerStats`) tidak berubah fungsi.

- [ ] **Step 2: Buat style kartu di themes.xml**

```xml
<style name="Widget.Wpmulti.Card" parent="Widget.Material3.CardView.Elevated">
    <item name="shapeAppearance">@style/Shape.Wpmulti.Large</item>
    <item name="cardBackgroundColor">@color/m3_surface_container_high</item>
    <item name="contentPadding">12dp</item>
</style>
```

- [ ] **Step 3: Bungkus 3 section dashboard dengan kartu**

Section "Proxy" (proxyTable..testResult), "VPN (TUN)" (vpnStatus..pingResult), "Monitor" (monGo..monSesiDetail) masing-masing dibungkus `MaterialCardView` style `Widget.Wpmulti.Card`; heading TextView section jadi `TextAppearance.Material3.TitleMedium`. Semua ID child tak tersentuh.

- [ ] **Step 4: Build + verifikasi ID lengkap**

Run: `gradle assembleDebug` lalu script banding daftar `R.id` layout vs referensi `R.id.` di MainActivity.
Expected: BUILD SUCCESSFUL; daftar ID identik; nol "unresolved reference".

- [ ] **Step 5: Commit + push**

```bash
git add -A && git commit -m "refactor(ui): toolbar M3 + kartu section dashboard (overhaul 1/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 2: TextField M3 untuk semua input

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml` (`testUrl`, `vpnDnsServer`, `logFilter`)
- Modify: `app/src/main/res/values/themes.xml` (+1 style textfield)
- Test: build + grep lokasi ID

**Interfaces:**
- Consumes: `Shape.Wpmulti.Small` (4dp) untuk box textfield.
- Produces: style `Widget.Wpmulti.TextField` (TextInputLayout OutlinedBox, corner_small) — dipakai Task 2 saja.

- [ ] **Step 1: Buat style textfield**

```xml
<style name="Widget.Wpmulti.TextField" parent="Widget.Material3.TextInputLayout.OutlinedBox">
    <item name="shapeAppearance">@style/Shape.Wpmulti.Small</item>
</style>
```

- [ ] **Step 2: Wrap 3 EditText**

Masing-masing EditText dibungkus TextInputLayout style `Widget.Wpmulti.TextField` (hint pindah ke layout hint); `android:id` pindah ke EditText yang diganti kelas `TextInputEditText` (masih EditText-castable). `logFilter` hint "filter teks..." tetap.

- [ ] **Step 3: Build + verifikasi ID ada di inner EditText**

Run: `gradle assembleDebug`; `rg -n 'id/testUrl|id/vpnDnsServer|id/logFilter' layout` → masing-masing tepat 1 match, di tag `TextInputEditText`.
Expected: BUILD SUCCESSFUL; 3 ID di inner field (Review Focus #1 tertutup).

- [ ] **Step 4: Commit + push**

```bash
git add -A && git commit -m "refactor(ui): TextInputLayout OutlinedBox utk testUrl/dnsServer/logFilter (overhaul 2/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 3: Row components — row_config & row_proxy

**Files:**
- Create: `app/src/main/res/drawable/ic_minus.xml`, `ic_plus.xml`, `ic_close.xml`, `ic_content_copy.xml` (Material Symbols, path 24dp standar)
- Modify: `app/src/main/res/layout/row_config.xml`, `row_proxy.xml`
- Test: build

**Interfaces:**
- Consumes: style `Widget.Wpmulti.Card` (dari Task 1); ID `label,count,minus,plus,max,del,proxyName,proxyAddr,proxyStatus,proxyCopy` tetap.
- Produces: 4 vector icon (dipakai row; ic_close juga kandidat scrim tap).

- [ ] **Step 1: Buat 4 vector icon** (`ic_minus`, `ic_plus`, `ic_close`, `ic_content_copy`) — `android:fillColor="#FFFFFFFF"` (di-tint runtime), path Material Symbols resmi.

- [ ] **Step 2: Restyle row_config** — root jadi MaterialCardView `Widget.Wpmulti.Card` (contentPadding 4dp); `minus`/`plus`/`del` → `Widget.Material3.Button.IconButton` + `app:icon` (ic_minus/ic_plus/ic_close, iconTint `?attr/colorOnSurfaceVariant`); `max` → `Widget.Material3.Button.OutlinedButton`; `count` tetap EditText center 56dp.

- [ ] **Step 3: Restyle row_proxy** — root MaterialCardView sama; `proxyCopy` → IconButton + `app:icon="@drawable/ic_content_copy"`; `proxyStatus` tetap TextView (warna status diset Java — jangan disentuh).

- [ ] **Step 4: Build + verifikasi**

Run: `gradle assembleDebug` — `setEnabled`/`findViewById` pada row tetap valid (IconButton adalah Button).
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit + push**

```bash
git add -A && git commit -m "refactor(ui): row_config/row_proxy jadi kartu + IconButton (overhaul 3/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 4: State halaman & sidebar pakai token

**Files:**
- Modify: `app/src/main/java/com/wpmulti/test/MainActivity.java` (`showPage()` saja, baris ~301-310)
- Modify: `app/src/main/res/layout/activity_main.xml` (statusBar pill)
- Create: `app/src/main/res/drawable/bg_pill.xml`
- Test: build

**Interfaces:**
- Consumes: attr `colorPrimaryContainer`/`colorSurfaceContainerHighest` M3E; `corner_small`.
- Produces: tidak ada (perbaikan state).

- [ ] **Step 1: showPage() resolve token dari tema**

Ganti argumen `setBackgroundColor(...)` hardcoded pada item menu sidebar: selected = `MaterialColors.getColor(view, com.google.android.material.R.attr.colorPrimaryContainer)`, unselected = `Color.TRANSPARENT`; teks menu selected `colorOnPrimaryContainer`. Import `com.google.android.material.color.MaterialColors`.

- [ ] **Step 2: statusBar jadi pill**

`bg_pill.xml`: shape rounded `@dimen/corner_small`, solid `@color/m3_surface_container_high`. statusBar: `android:background="@drawable/bg_pill"` + margin 12dp.

- [ ] **Step 3: Build**

Run: `gradle assembleDebug`.
Expected: BUILD SUCCESSFUL (Review Focus #3: token di-resolve runtime, night ikut otomatis).

- [ ] **Step 4: Commit + push**

```bash
git add -A && git commit -m "refactor(ui): state halaman & status pill pakai token tema (overhaul 4/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 5: TrafficGraphView theme-aware + palet orkid

**Files:**
- Modify: `app/src/main/java/com/wpmulti/test/TrafficGraphView.java` (`init()` saja)
- Modify: `app/src/main/res/values/colors.xml` + `values-night/colors.xml` (+1 token `m3_tertiary`)
- Modify: `app/src/main/res/values/themes.xml` + `values-night/themes.xml` (+item `colorTertiary`)
- Test: build

**Interfaces:**
- Consumes: `MaterialColors.getColor(Context, attr)`; attr `colorOutline`, `colorOnSurfaceVariant`, `colorPrimary`, `colorTertiary`.
- Produces: token `m3_tertiary` (light `#B331A2` klaster image; night `#FDB0F0` klaster image) — dipakai chart & kandidat aksen berikutnya.

- [ ] **Step 1: Tambah token tertiary** (light `#B331A2`, night `#FDB0F0`) + item `colorTertiary` di kedua tema.

- [ ] **Step 2: init() baca attr tema**

Grid = `colorOutline` + alpha 0x33; label = `colorOnSurfaceVariant`; garis RX = `colorPrimary`; garis TX = `colorTertiary`. Satu helper `private int attr(int a)` memakai `MaterialColors.getColor(getContext(), a)`.

- [ ] **Step 3: Build**

Run: `gradle assembleDebug`.
Expected: BUILD SUCCESSFUL (Review Focus #4 tertutup: grid gelap di light, terang di night).

- [ ] **Step 4: Commit + push**

```bash
git add -A && git commit -m "refactor(ui): TrafficGraphView theme-aware + token tertiary orkid (overhaul 5/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 6: Hierarki CTA

**Files:**
- Modify: `app/src/main/res/layout/activity_main.xml` (`btnEngine`, `vpnToggleBtn` saja)
- Test: build

**Interfaces:**
- Consumes: style bawaan lib; Java `btnEngine` sudah MaterialButton + `setIconResource` (Task 20) — kompatibel.

- [ ] **Step 1: btnEngine jadi CTA filled**

style `Widget.Material3.Button` + `app:iconTint="?attr/colorOnPrimary"`; `vpnToggleBtn` eksplisit ke `Widget.Material3.Button.TonalButton` (kontras dgn CTA). Tombol lain tidak disentuh (default tonal dari tema).

- [ ] **Step 2: Build + push**

Run: `gradle assembleDebug` → BUILD SUCCESSFUL.

```bash
git add -A && git commit -m "refactor(ui): CTA START filled primary, vpnToggle tonal (overhaul 6/7)"
git push origin HEAD:main HEAD:wptest-v1.5
```

---

### Task 7: Release v2.0

**Files:**
- Modify: `app/build.gradle` (versionCode 11, versionName `'2.0'`), `README.md` (+baris 2.0)
- Test: badging kedua APK

- [ ] **Step 1: Bump versionCode 11 / versionName '2.0'** + README riwayat versi.

- [ ] **Step 2: Build debug + release**

Run: `gradle assembleDebug assembleRelease`.
Expected: BUILD SUCCESSFUL; `aapt2 dump badging` = `versionCode='11' versionName='2.0'`.

- [ ] **Step 3: Commit + push**

```bash
git add -A && git commit -m "release: v2.0 - rombak total UI M3E"
git push origin HEAD:main HEAD:wptest-v1.5
```

- [ ] **Step 4: Upload APK** ke `POST https://tmpfile.link/api/upload` (multipart `file`) + tulis worklog Task 22.

---

## Self-Review

1. **Spec coverage:** "rombak total" = 7 permukaan (toolbar/kartu/textfield/rows/state/chart/CTA) semua ada task-nya; "tegas & elegan" = constraint sudut 4/6/8 + palet orkid via token; "sidebar juga" = Task 4 state token; "icon no-emoji" = Task 3 vector; "push selalu" = step commit+push tiap task. ✓
2. **Step scan:** tiap step = satu aksi checkable (edit dengan target eksak / build dengan expected / push). Tidak ada "TBD". ✓
3. **Type consistency:** `Widget.Wpmulti.Card` dipakai Task 1 & 3; `Shape.Wpmulti.*` konsisten dengan themes.xml existing; `m3_tertiary` didefinisikan Task 5 dan hanya dipakai di sana; 4 nama icon Task 3 = yang direferensikan layout. ✓
4. **Review Focus:** #1 → Task 2 step 3; #2 → Task 2 build (halaman Setting hidup); #3 → Task 4 step 1; #4 → Task 5 step 2; #5 → Task 1 step 2 & Task 2 step 1. ✓
5. **Proportion:** plan ~1/3 panjang dari diff XML yang akan dihasilkan — bukan transcript. ✓
