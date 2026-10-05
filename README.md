# wpmulti-android

Android test app for **wpmulti** — multi-session WireGuard proxy with
per-flow IP rotation.

The app bundles N WireGuard sessions (max 240 per profile) behind one
shared proxy:

- SOCKS5 `127.0.0.1:1080`
- HTTP `127.0.0.1:8080`

New connections are distributed round-robin across sessions (atomic
counter, so even simultaneous connections land on different sessions);
established flows stay sticky on their session so TCP never breaks.
Each session gets its own Cloudflare WARP egress IPv6.

## Module

`wpmulti/` is a git submodule pointing at
[pkok1099/wpmulti](https://github.com/pkok1099/wpmulti) — the Go core
(shared gVisor netstack, flowMux, noStickyBind, mobile bindings).

```sh
git clone --recurse-submodules https://github.com/pkok1099/wpmulti-android
```

## Build the AAR

The app needs `app/libs/wpmulti.aar` built from the submodule via gomobile:

```sh
cd wpmulti
gomobile bind -o ../app/libs/wpmulti.aar -target android/arm64 ./mobile
```

Requires Go, gomobile, and the Android NDK.

## Build the APK

```sh
gradle assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

## Configs

`app/src/main/assets/confs/` is git-ignored (contains private keys).
Place your `*.conf` WireGuard profiles there before building, or upload
them from the app's Config page at runtime.

## Features

- Sidebar navigation: Config, Proxy, Monitor, Log, Setting
- Persistent status bar (idle / starting / running / stopping)
- Per-profile session count (1 profile = max 240 sessions)
- Proxy test with editable URL and batch size
- RAM (VmRSS) / CPU (process) / cache monitor
