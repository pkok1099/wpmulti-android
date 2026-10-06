package com.wpmulti.test;

import android.app.Activity;
import android.content.Intent;
import android.net.VpnService;
import android.os.Bundle;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Activity tanpa UI untuk kontrol VPN dari luar (mis. via `am start` dari Termux).
 * Exported, jadi bisa dipanggil: am start -n com.wpmulti.test/.VpnControlActivity --es action start
 * Actions: "start" (engine+VPN), "stop" (VPN saja), "toggle".
 */
public class VpnControlActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final String act0 = getIntent().getStringExtra("action");
        final String action = act0 == null ? "toggle" : act0;

        new Thread(() -> {
            try {
                if ("stop".equals(action)) {
                    stopVpn();
                } else if ("start".equals(action)) {
                    startEngineIfNeeded();
                    startVpnIfNeeded();
                } else { // toggle
                    if (VpnEngine.running) stopVpn();
                    else {
                        startEngineIfNeeded();
                        startVpnIfNeeded();
                    }
                }
            } catch (Exception e) {
                android.util.Log.e("VpnControl", "gagal: " + e);
            } finally {
                runOnUiThread(this::finish);
            }
        }).start();
    }

    private void stopVpn() {
        if (VpnEngine.running) {
            VpnEngine.disconnect();
            stopService(new Intent(this, VpnEngine.class));
        }
    }

    private void startEngineIfNeeded() throws Exception {
        EngineClient.get().init(this);
        // TAHAP 1: isRunning + start via AIDL (binder ke :goengine) —
        // bukan Mobile.isRunning()/Mobile.start() di proses utama.
        if (EngineClient.get().isRunningSync(1000)) return;
        // default: 1 config, 5 sesi per config (max 5). Bisa dioverride via extra.
        int numConfigs = getIntent().getIntExtra("configs", 1);
        if (numConfigs < 1) numConfigs = 1;
        if (numConfigs > 5) numConfigs = 5;
        int perCount = getIntent().getIntExtra("count", 5);
        if (perCount < 1) perCount = 1;
        if (perCount > 5) perCount = 5;
        File confDir = new File(getFilesDir(), "confs");
        if (confDir.exists()) {
            File[] fs = confDir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
        } else confDir.mkdirs();
        // hanya 1 config bundled (wireproxy-1.conf); numConfigs diabaikan
        String[] bundled = {"wireproxy-1.conf"};
        File profDir = new File(getFilesDir(), "profiles");
        if (!profDir.exists()) profDir.mkdirs();
        int total = 0, ci = 0;
        for (String b : bundled) {
            File pf = new File(profDir, b);
            if (!pf.exists()) {
                try (InputStream in = getAssets().open("confs/" + b);
                     OutputStream o = new FileOutputStream(pf)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                } catch (Exception ignored) { continue; }
            }
            byte[] base = java.nio.file.Files.readAllBytes(pf.toPath());
            for (int j = 0; j < perCount; j++) {
                File out = new File(confDir, "c" + ci + "_" + j + ".conf");
                try (OutputStream o = new FileOutputStream(out)) { o.write(base); }
                total++;
            }
            ci++;
        }
        if (total == 0) throw new Exception("tidak ada profil");
        // START(configDir) via binder — blokir sampai engine siap.
        String err = EngineClient.get().startBlocking(
                confDir.getAbsolutePath(), 60_000);
        if (err != null && !err.isEmpty()) throw new Exception(err);
        ProxyKeepaliveService.start(this);
    }

    private void startVpnIfNeeded() {
        if (VpnEngine.running) return;
        android.content.SharedPreferences vp =
                getSharedPreferences("vpn", MODE_PRIVATE);
        String mode = vp.getString("dns_mode", "plain");
        String dnsIp = vp.getString("dns_ip", "1.1.1.1");
        String dnsTarget = vp.getString("dns_target", "1.1.1.1");
        Intent vpnIntent = new Intent(this, VpnEngine.class);
        vpnIntent.putExtra("dns_mode", mode);
        vpnIntent.putExtra("dns_ip", dnsIp);
        vpnIntent.putExtra("dns_target", dnsTarget);
        vpnIntent.putExtra("vpn_app_mode",
                vp.getString("vpn_app_mode", "all"));
        java.util.Set<String> appSet =
                vp.getStringSet("vpn_apps", new java.util.HashSet<>());
        vpnIntent.putExtra("vpn_app_list", appSet.toArray(new String[0]));
        vpnIntent.putExtra("vpn_ip_mode",
                vp.getString("vpn_ip_mode", "dual"));
        // consent: jika belum pernah, prepare() kembalikan intent dialog
        Intent prep = VpnService.prepare(this);
        if (prep != null) {
            // tidak bisa tampilkan dialog dari thread; lempar ke UI
            runOnUiThread(() -> {
                try {
                    startActivityForResult(prep, 1);
                    // simpan untuk onActivityResult
                    pendingVpn = vpnIntent;
                } catch (Exception e) {
                    android.util.Log.e("VpnControl", "prepare gagal: " + e);
                    finish();
                }
            });
            // jangan finish dulu; tunggu onActivityResult
            return;
        }
        startService(vpnIntent);
    }

    private Intent pendingVpn;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1 && resultCode == RESULT_OK && pendingVpn != null) {
            startService(pendingVpn);
        }
        finish();
    }
}
