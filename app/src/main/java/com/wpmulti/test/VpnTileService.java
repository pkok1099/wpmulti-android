package com.wpmulti.test;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.Icon;
import android.net.VpnService;
import android.app.PendingIntent;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * Quick Settings tile untuk toggle VPN wpmulti.
 *
 * - Engine mati -> tile INACTIVE, tap membuka MainActivity.
 * - Engine hidup + VPN mati -> tap menyalakan VPN langsung
 *   (pakai konfigurasi DNS tersimpan; jika belum ada konfigurasi
 *   atau izin VPN belum diberikan, buka MainActivity).
 * - VPN hidup -> tile ACTIVE, tap mematikan VPN.
 *
 * TAHAP 1: state engine dibaca lewat EngineClient (GET_STATUS via binder
 * ke :goengine) — bukan Mobile.isRunning() langsung (split-brain).
 */
public class VpnTileService extends TileService {

    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    public void onStartListening() {
        EngineClient.get().init(this);
        refreshTileAsync();
    }

    /**
     * Baca state engine (GET_STATUS via binder) di THREAD LATAR lalu
     * terapkan ke tile di main thread. v1.3 memanggil isRunningSync() di
     * main thread: awaitConnected bisa blokir (ANR) dan sempat meledak
     * IllegalMonitorStateException (bug wait tanpa monitor, sudah diperbaiki
     * di EngineClient). Binder call tetap dilarang di main thread.
     */
    private void refreshTileAsync() {
        new Thread(() -> {
            final boolean engOn = readEngineRunning();
            main.post(() -> applyTile(engOn));
        }, "tileStatus").start();
    }

    /** isRunningSync (binder, bisa blokir) — HANYA dari thread latar. */
    private boolean readEngineRunning() {
        try {
            return EngineClient.get().isRunningSync(400);
        } catch (Exception e) {
            return EngineClient.get().snapshot().running;
        }
    }

    private void applyTile(boolean engOn) {
        Tile tile = getQsTile();
        if (tile == null) return;
        boolean vpnOn = VpnEngine.running;
        tile.setState(vpnOn ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        // minSdk 34: setSubtitle selalu tersedia (API 30+) -> guard dihapus.
        tile.setSubtitle(vpnOn ? "VPN aktif"
                : engOn ? "VPN mati" : "Engine mati");
        tile.updateTile();
    }

    @Override
    public void onClick() {
        EngineClient.get().init(this);
        // State engine dibaca di thread latar (binder bisa blokir); aksi
        // hasil klik tetap dieksekusi di main thread (perilaku tak berubah).
        new Thread(() -> {
            final boolean engOn = readEngineRunning();
            main.post(() -> handleClick(engOn));
        }, "tileClick").start();
    }

    private void handleClick(boolean engOn) {
        boolean vpnOn = VpnEngine.running;
        if (vpnOn) {
            // Matikan: teardown sinkron + stopService, tanpa UI.
            VpnEngine.disconnect();
            stopService(new Intent(this, VpnEngine.class));
            refreshTileAsync();
            return;
        }
        if (!engOn) {
            // Engine mati -> tidak bisa VPN langsung; buka app.
            openApp();
            return;
        }
        // Coba nyalakan langsung jika izin VPN sudah ada.
        Intent prep;
        try {
            prep = VpnService.prepare(this);
        } catch (Exception e) {
            prep = null;
        }
        if (prep != null) {
            // Belum ada izin -> buka app untuk alur consent.
            openApp();
            return;
        }
        Intent vpnIntent = buildVpnIntent();
        if (vpnIntent == null) {
            openApp();
            return;
        }
        try {
            // minSdk 34: selalu startForegroundService (API 26+), guard mati
            // dihapus. VpnEngine kini memanggil startForeground() di
            // onStartCommand sehingga tidak crash "did not start in time".
            startForegroundService(vpnIntent);
        } catch (Exception e) {
            android.util.Log.w("VpnTile", "start VPN gagal: " + e);
        }
        refreshTileAsync();
    }

    private void openApp() {
        Intent it = new Intent(this, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // WAJIB overload PendingIntent: overload Intent (di bawah) melempar
        // IllegalStateException di targetSdk 34+ -> crash saat tap tile.
        PendingIntent pi = PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        startActivityAndCollapse(pi);
    }

    /**
     * Bangun intent VPN dari konfigurasi tersimpan.
     * Mengembalikan null jika belum ada konfigurasi valid.
     */
    private Intent buildVpnIntent() {
        SharedPreferences p = getSharedPreferences("vpn", MODE_PRIVATE);
        String mode = p.getString("dns_mode", null);
        String dnsIp = p.getString("dns_ip", null);
        String dnsTarget = p.getString("dns_target", null);
        if (mode == null || dnsIp == null || dnsTarget == null) return null;
        String appMode = p.getString("vpn_app_mode", "all");
        java.util.Set<String> appSet =
                p.getStringSet("vpn_apps", new java.util.HashSet<>());
        Intent it = new Intent(this, VpnEngine.class);
        it.putExtra("dns_mode", mode);
        it.putExtra("dns_ip", dnsIp);
        it.putExtra("dns_target", dnsTarget);
        it.putExtra("vpn_app_mode", appMode);
        it.putExtra("vpn_app_list", appSet.toArray(new String[0]));
        it.putExtra("vpn_ip_mode", p.getString("vpn_ip_mode", "dual"));
        return it;
    }
}
