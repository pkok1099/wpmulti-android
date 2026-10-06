# wpmulti-android

Aplikasi Android VPN **WireGuard multi-profil** (WGCF/WARP) dengan **proksi SOCKS5/HTTP**
yang dijalankan oleh engine Go (`wpmulti`) yang dikemas via gomobile sebagai AAR.
Engine berjalan di proses terpisah (`:goengine`) dan dikontrol secara eksklusif lewat AIDL —
satu jalur kontrol, tanpa duplikasi instance.

| | |
|---|---|
| Package | `com.wpmulti.test` |
| Versi saat ini | **v1.5** (versionCode 6) |
| SDK | minSdk 34 · targetSdk 36 |
| Bahasa | Java (UI) + Go 1.26 (engine) |
| Toolchain build | JDK 17 · Gradle 8.14.5 · AGP 8.5.2 · NDK 27 · gomobile |

---

## Fitur

- **Multi-profil WireGuard** — beberapa konfigurasi (`assets/confs/*.conf`) dijalankan
  bersamaan oleh satu engine, berbagi satu network stack (gVisor netstack).
- **Proksi SOCKS5 + HTTP** dari engine Go — satu instance dibagi semua sesi
  (port internal 1080/8080), tanpa perlu VPN aktif.
- **Mode VPN TUN** (`VpnEngine`) — relay unix-socket ke engine; anti-loop via
  `addDisallowedApplication` per-UID.
- **Dashboard monitor** — status engine, statistik sesi, trafik, memori Go, log.
- **Quick Settings tile** + keepalive service + control/test activity untuk eksperimen.
- **Keandalan start 4 kanal** — reply binder, broadcast (`setPackage`), callback
  `StatusListener.onReady`, dan polling status 1s; start tidak pernah "nyangkut".

## Arsitektur

```
┌─ Proses utama (UI) ─────────────────────────┐      ┌─ Proses :goengine ──────────────┐
│ MainActivity · VpnControlActivity           │ AIDL │ GoEngineService                  │
│ TestActivity · VpnTileService               │◄────►│  └─ Mobile.Start/Stop/... (Go)   │
│ ProxyKeepaliveService · VpnEngine (TUN)     │      │  └─ SOCKS5/HTTP + WireGuard      │
│ └─ EngineClient (SATU-SATUNYA jalan ke Go)  │      │     (wpmulti.aar / libgojni.so)  │
└─────────────────────────────────────────────┘      └──────────────────────────────────┘
```

- `IEngineControl.aidl` + `EngineStatus` (Parcelable): kontrak kontrol.
- `EngineClient`: bind + `awaitConnected()` (monitor `lock` yang benar, deadline,
  interrupt-safe), `linkToDeath`, pemulihan `RemoteException`.
- Stop = `stopForeground(true)` → `Process.killProcess(myPid())`; mati mendadak engine
  memicu kill-switch lokal (VPN ikut dimatikan).
- Tidak ada pemanggilan `Mobile.*` di proses utama (di-audit 0 call site).

## Struktur repo

```
app/                    # modul Android (Java, AIDL, manifest, konfigurasi)
app/libs/wpmulti.aar    # engine Go PREBUILT — versi ter-patch 32KB (sumber kebenaran v1.5)
app/libs/wpmulti-sources.jar
wpmulti-go/             # SOURCE modul Go (vendored) untuk rebuild AAR — lihat VENDORED.md
ram-patch-32k/          # patch source (format-patch) + script binary-patch libgojni.so
```

## Modul Go (`wpmulti-go/`)

Di-vendor dari [`pkok1099/wpmulti`](https://github.com/pkok1099/wpmulti)
branch `rebuild/v1.5-base` (merge `ram/bufferpool-32k` + `fix/android-audit`).
Modul berbasis [wireproxy](https://github.com/pufferffish/wireproxy) + wireguard-go
(`golang.zx2c4.com/wireguard v0.0.0-20250521234502-f333402bd9cb`) + go-socks5,
tanpa `replace` lokal — bisa di-build langsung.

### Optimasi RAM (berdasarkan heap.prof)

| Tahap | Isi | Status di v1.5 |
|---|---|---|
| 1 | Buffer relay go-socks5 **256KB → 32KB** (`bufferpool.NewPool`) — hemat ~40 MB pada ~185 buffer konkuren | **Aktif** (binary-patch `libgojni.so` + source) |
| 2 | Analisis pool wireguard-go: `PreallocatedBuffersPerPool=4096 × 64KB` per device (~256 MB teoretis); usulan penurunan ke 256 — **laporan saja, tidak diterapkan** | Dokumen |
| 3 | `debug.FreeOSMemory()` tiap 60 dtk saat TX/RX diam 3 interval berturut (monitor idle di `GoEngineService`) | **Aktif di Java**; penuh setelah AAR di-rebuild dari `wpmulti-go` |

GOGC tidak diubah; pool size dan logika WireGuard tidak disentuh.

### Rebuild AAR (opsional)

```bash
cd wpmulti-go
gomobile bind -target=android -androidapi 34 -o ../app/libs/wpmulti.aar ./mobile
```

⚠️ **Baca `wpmulti-go/VENDORED.md` dulu** — modul ini memuat 13 dari 15 binding
`Mobile.*` yang dipanggil Java. Tiga binding socket-path (`socksSocketPath`,
`udpSocketPath`, `icmpSocketPath`) hanya ada di tree build asli pemilik repo;
tanpa mereka kompilasi Java gagal di `GoEngineService.java:127-129`.
Untuk build APK biasa, **pakai AAR prebuilt** — tidak perlu rebuild.

## Build APK

```bash
# Android Studio: open project → Run
# atau CLI (butuh JDK 17, Android SDK 34/36):
gradle assembleDebug      # output: app/build/outputs/apk/debug/
gradle assembleRelease    # R8 minify + shrinkResources (unsigned)
```

Keystore release dibaca dari env `WPMULTI_STORE_*` (tidak ada rahasia di repo).

## Riwayat versi

| Versi | Isi utama |
|---|---|
| 1.1 | Audit 24 bug (split-brain proses, manifest rusak, leak), ukuran APK −54/−61% |
| 1.2 | Fix "stuck MEMULAI" — start dikonfirmasi 4 kanal |
| 1.3 | Satu jalur kontrol: semua `Mobile.*` via AIDL ke `:goengine` |
| 1.4 | Fix `IllegalMonitorStateException` di `EngineClient.awaitConnected()` |
| 1.5 | Reduksi RAM heap.prof: buffer SOCKS 32KB + idle-GC `FreeOSMemory` |

## Kredit & lisensi

- [wireproxy](https://github.com/pufferffish/wireproxy) & [wireguard-go](https://github.com/WireGuard/wireguard-go) — lisensi MIT masing-masing
- [things-go/go-socks5](https://github.com/things-go/go-socks5) — MIT
- [gVisor netstack](https://gvisor.dev) — Apache-2.0
- Source modul Go mewarisi `LICENSE` upstream — lihat `wpmulti-go/LICENSE`

## Disclaimer

Proyek testing pribadi, bukan produk. Penggunaan WGCF/WARP mengikuti ketentuan
Cloudflare/WireGuard — risiko di tangan pengguna. Jangan pakai untuk melanggar
hukum yang berlaku di yurisdiksi Anda.
