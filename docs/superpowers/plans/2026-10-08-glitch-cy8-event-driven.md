# Glitch cy8 — Event-Driven Tepat Sasaran (Rework cy7) Implementation Plan

> **Status: IMPLEMENTED** (fase cy8, branch `ui/floating-nav`)

**Goal:** Glitch terasa sebagai **mekanisme bagaimana UI berubah**, bukan animasi dekoratif yang sesekali muncul. Prinsip: «Sesuatu yang berubah → sesuatu itulah yang mengalami glitch. Bukan seluruh UI.» Frekuensi event = TINGGI, visual noise = RENDAH, target = EKSPLISIT.

**Spec:** Permintaan user: perbaiki implementasi glitch existing — jangan hanya menambah variasi; masalah: glitch jarang, timing kurang tepat, beberapa efek kena area terlalu besar, ada artifact kotak di floating navbar, dropdown/dialog masih terasa UI Android biasa, page transition masih "shake + tiny glitch".

---

## Akar Masalah yang Ditemukan (review cy7)

| # | Masalah | Akar penyebab | Perbaikan |
|---|---|---|---|
| 1 | **Dropdown masih terasa UI Android biasa** — GlitchDropdown (popup custom materialize/disintegrate) **tidak pernah terpanggil** | `GlitchText.installTouch()` mengecek `instanceof Spinner` SETELAH cabang `instanceof ViewGroup` yang selalu `return` — padahal `Spinner extends ViewGroup`. Cabang Spinner = dead code; yang terbuka popup platform. | Cabang Spinner dipindah SEBELUM ViewGroup + guard UP masih dalam bounds spinner. |
| 2 | **Artifact kotak di sekitar floating navbar** (screenshot) | `bnv.setOnTouchListener` memanggil `glitchView(navPill, MINOR)` = alpha-flicker pada view ber-elevation 6dp. Alpha < 1 memaksa offscreen layer → shadow elevation ter-clip bounds persegi = KOTAK. | Pill hanya diberi `glitchJitter` (displacement murni, tanpa alpha). Ditambah guard elevation sistemik (lihat #3). |
| 3 | Artifact kotak laten di view ber-elevation lain (HUD 8dp, kartu Elevated saat page transition) | Semua efek alpha-based tidak memperhitungkan elevation. | `guardElevation()`: BASE diperluas `{alpha, tx, scaleX, ELEVATION}` — elevation dinolkan selama efek alpha, dipulihkan deterministik oleh `stepFinish`/`cancelFor`/`stop`/`restoreAllBase`. |
| 4 | `glitchNavItems()` (per-item navbar) **dead code** — tidak pernah dipanggil, `lastNavItemId` tidak pernah di-update | Wiring hilang. | Dipanggil di `bnv.setOnItemSelectedListener` sebelum `showPage`; `lastNavItemId` di-track. |
| 5 | Glitch "jarang terasa" pada event yang benar | Dropdown custom mati (#1), nav item mati (#4), beberapa path masih redundan. | Semua event kini menyalakan jalurnya + `glitchStateChange` baru untuk enabled/disabled tombol + ketikan kini per-karakter. |
| 6 | `rebuildProxyTable()` mengglitch seluruh tabel **setiap pindah tab** walau tidak ada perubahan | Dipanggil dari listener nav tanpa guard state. | Guard `lastProxyStateKey` — hanya glitch saat status AKTIF↔MATI berubah / render pertama. |
| 7 | `rebuildConfigRows()` menggelapkan seluruh container (MAJOR) saat hapus/onCreate | Tanpa guard. | Dihapus — row yang dihapus sudah DISINTEGRATE sebelum rebuild; row lain isinya identik (tidak berubah = tidak ada glitch). |
| 8 | Micro-squeeze & shadow-swap MAJOR pakai `postDelayed` liar (tak terlacak → risiko state nyangkut saat stop/cancel) | Efek di luar framework `step()`. | Keduanya kini lewat `step()` — `cancelFor`/`stop` memulihkan SEMUA state. |
| 9 | Ketik/hapus hanya ghost shadow seluruh field | Belum ada korupsi region. | `inputCorrupt()`: span Foreground/Background/Strikethrough HANYA pada region yang berubah via `Editable.setSpan` — tanpa `setText`: cursor/selection/IME/focus/ukuran view tak tersentuh. |
| 10 | Page transition masih terasa "shake + tiny glitch" | Implementasi cy7: fragment flicker cepat tanpa fase berbeda + tanpa momen "dead air". | Rework 7 fase (~600ms): micro displacement → korupsi → kehilangan struktur → pecah + swap + scanline → fragmen B menyala staggered → rekonstruksi teks → stabil. |

## Perubahan Per File

### `GlitchText.java` (v4, cy8)
- **installTouch**: Spinner dicek SEBELUM ViewGroup (fix dead code); press Button = `glitchNow` MINOR + `glitchJitter` MINOR; UP di luar bounds tidak membuka dropdown.
- **Guard elevation** (`guardElevation` + BASE 4-elem + `stepFinish`): semua efek alpha-based (glitchView/glitchAppear/glitchDisappear/materializeStaggered/vanishStaggered/page transition) aman untuk view ber-elevation — TIDAK ADA ARTIFACT KOTAK.
- **inputCorrupt**: korupsi per-karakter (insert = region baru; hapus = seam merah + strike-through; paste = region besar). Span dilepas 120–180ms, tracked `INPUT_SPANS`/`INPUT_CLEAR`, dibersihkan `stop()`.
- **scanline/clearScanline**: lapisan scanline sementara via `ViewOverlay` (BitmapDrawable tile 4x3, cyan `glitch_scan`) — tidak menyentuh layout; idempotent; dibersihkan terjadwal/`stop()`/dismiss.
- **pageTransition**: 7 fase dengan generation counter; logView & logScroll DIKECUALIKAN (log selalu stabil); elevation kartu dijaga; scanline pada parent konten selama swap.
- **glitchStateChange** (API baru): elemen berubah enabled-state → korupsi → rekonstruksi.
- **dropdownPulse**: + micro displacement (glitchJitter) — "unstable" tanpa mengganggu pemilihan.
- **burst MAJOR**: shadow-swap RGB via `step()` (terlacak); micro-squeeze via `step()` (pulih deterministik).

### `GlitchDropdown.java` (cy8)
- OPEN: window flicker + `scanline(lv, 320)` + `glitchTree` MEDIUM + `materializeStaggered` (displacement per item).
- OPENED: pulse 380–650ms per item + shimmer scanline tipis tiap pulse ke-4.
- SELECT: kilat scanline + `vanishStaggered` + dismiss 150ms.
- DISMISS (tap luar/back): exit animation `glitch_disintegrate`.
- Hygiene: dismiss listener melepas scanline + `cancelFor` semua anak list — tidak ada langkah tertunda pada view mati.

### `MainActivity.java` (cy8)
- **FIX ARTIFACT**: `bnv` touch → `glitchJitter(navPill)` (bukan `glitchView`).
- **Wire `glitchNavItems`** + `lastNavItemId` (per-item: kehilangan active = MINOR korupsi, mendapat active = MEDIUM rekonstruksi).
- `glitchStateChange` dipasang pada: `addBtn`, `test1Btn`/`test20Btn`, kontrol config (minus/plus/max/del/count), `vpnToggleBtn` — hanya saat state benar-benar berubah, dipanggil SEBELUM `setEnabled`.
- Dialog (Pilih aplikasi / Mode IP): `dec.post()` → `scanline(dec, 340)` + `glitchTree` + `materializeStaggered(lv)` (setelah layout; token null = skip aman).
- Checkbox (auto-reconnect + dialog): `glitchNow` + `glitchJitter` MINOR.
- `updateDnsHint`: hint berubah → `glitchJitter` MINOR pada field itu (cursor & isi tak tersentuh).
- `rebuildProxyTable`: guard state (lihat tabel atas).
- `rebuildConfigRows`: tanpa `glitchMajor(container)`.
- `setTextIfChanged` untuk label yang di-refresh tiap tick (proxyStatus/vpnStatus/vpnToggleBtn) — teks identik = tidak memicu watcher glitch.

## Validasi

- **Build**: `./gradlew assembleDebug` ✓ (20.0 MB) · `./gradlew assembleRelease` ✓ R8 + shrinkResources (6.25 MB unsigned). Lint errors yang ada (2× WrongViewCast `MaterialButton`, 1× QueryAllPackagesPermission) = pre-existing, bukan dari perubahan ini (0 error menyebut kelas Glitch).
- **Statis**: semua API glitch lama dipertahankan (tidak ada caller rusak); `local.properties` di-gitignore; `gradle.properties` tidak diubah.

## Checklist Test Visual (WAJIB dijalankan manual di device — environment build ini tidak punya emulator)

**Text**: ketik DNS (per karakter) · hapus DNS (seam merah + strike) · paste (region besar) · clear.
**Dropdown**: open (scanline + staggered) · tetap terbuka (pulse + shimmer) · select item · close (disintegrate) · buka/tutup berkali-kali (toggle aman).
**Button**: press (korupsi + jitter) · disabled (korupsi → state) · enabled (rekonstruksi).
**Checkbox**: check · uncheck (label + box ber-gemetrek, row diam).
**Navbar**: pindah tab (item lama & baru glitch sendiri; TANPA KOTAK di pill) · pastikan tidak ada rectangle setelah animasi.
**Dialog**: open (materialize + scanline) · search (region per karakter) · checkbox · select app · close (disintegrate window).
**Config**: add (row baru glitchAppear) · remove (disintegrate row → rebuild tanpa container MAJOR) · edit count (+/-).
**Split Tunnel**: open dialog · add/remove app · change selection (checkbox itu saja).
**Mode IP**: ganti mode per app (tombol itu saja) · mode global (spinner + counter).
**Page**: A→B · B→A · cepat berpindah beberapa kali (generation counter — tidak ada state nyangkut).
**VPN state**: start · stop · reconnect (hero MAJOR + stats appear/disappear + HUD).
**Log**: logView/logView-scroll tetap 100% stabil tanpa glitch, termasuk saat transisi halaman Log.
**Aksesibilitas**: Setelan developer → skala animasi 0 → semua state tetap berubah benar, tanpa alpha/transform/shadow/overlay tertinggal.

## Pemetaan Acceptance Criteria

1. Glitch sering saat ada perubahan ✓ (semua event menyalakan jalurnya; dead code diaktifkan)
2. Bukan seluruh screen per event ✓ (target eksplisit per API)
3. Element yang berubah = target utama ✓
4. Typing hanya region yang berubah ✓ (`inputCorrupt`)
5. Delete punya corruption effect ✓ (seam merah + strike-through + ghost)
6/7/8. Dropdown open/close/while-open ✓ (GlitchDropdown aktif — fix dead code)
9. Dialog materialization/disintegration ✓ (window anim + scanline + staggered)
10. Button feedback ✓ (press + enabled-state)
11. Checkbox/toggle feedback ✓
12. Navbar per-item ✓ (glitchNavItems di-wire)
13. Tidak ada artifact kotak ✓ (fix penyebab + guard elevation sistemik)
14. Config baru materialize ✓
15. Split Tunnel per-perubahan ✓
16. Mode IP per-state ✓
17/18. Page navigation = korosi A menjadi B ✓ (7 fase, bukan slide)
19. Log bebas glitch ✓ (dikecualikan dari walk/collect/fragmen/scanline)
20. Tidak ada layout shift ✓ (hanya transform rendering; rantai berakhir di baseline)
21. Tidak ada stuck alpha/transform/shadow ✓ (BASE 4-elem + step framework + cleanup stop)
22. Tidak ada memory/listener leak ✓ (WeakReference/WeakHashMap; dismiss cleanup; span tracked)
23. Animation scale 0 aman ✓ (semua jalur final-state tanpa efek)
24. Sering tapi tidak terasa rusak terus-menerus ✓ (ambient 1/950–1500ms dijeda 1.5 dtk setelah event; throttle + token bucket)
