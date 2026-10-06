# VENDORED — wpmulti-go

Salinan source modul Go engine (`wpmulti`) yang di-vendor ke dalam repo Android
agar repo ini self-contained untuk rebuild AAR.

- **Sumber**: `github.com/pkok1099/wpmulti`, branch `rebuild/v1.5-base`, commit `1937f4f`
- **Komposisi**: merge dari `ram/bufferpool-32k` (c1fa2d5) + `fix/android-audit` (9799bfb)
- **Tanggal vendor**: 2026-10-07

## Isi optimasi RAM di source ini

1. **Buffer go-socks5 32KB** (`multi.go: socksBufSize = 32*1024`) — sebelumnya
   256KB/buffer; pada ~185 buffer konkuren hemat ~40 MB inuse (TAHAP 1 heap.prof).
2. **Binding `mobile.FreeOSMemory()`** — dipanggil Java saat engine idle
   (TAHAP 3; aktif penuh setelah AAR di-rebuild dari source ini).
3. **Fix race/TOCTOU `devMu`** untuk `m.devs` + urutan shutdown anti-deadlock
   (`closeQueues` sebelum device Close) — dari `fix/android-audit`.

## Catatan binding

Modul ini mengekspor 13 binding `Mobile.*` (Start, Stop, IsRunning, SessionCount,
SessionStats, MemStats, GoroutineCount, SetLogFile, SetStatusListener, SetTempDir,
FreeOSMemory, WriteHeapProfile, WriteGoroutineProfile).

TIGA binding berikut **belum ada** di source ini — hanya ada di tree build asli
yang belum di-push (milik pemilik repo): `socksSocketPath`, `udpSocketPath`,
`icmpSocketPath`. Akibatnya `gomobile bind` dari folder ini menghasilkan AAR yang
membuat kompilasi Java gagal di `GoEngineService.java:127-129` (3 pemanggilan
tersebut). Sebelum rebuild, tambahkan ketiga binding dari tree build asli, atau
komentari 3 baris itu (fitur status unix-socket path di dashboard hilang, sisanya
normal).

AAR prebuilt di `app/libs/wpmulti.aar` (versi ter-patch 32KB) tetap sumber
kebenaran untuk build APK v1.5.
