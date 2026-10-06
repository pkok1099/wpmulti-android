package com.wpmulti.test;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.os.Process;
import android.util.Log;

import mobile.Mobile;
import mobile.StatusListener;

/**
 * GoEngineService — SATU-SATUNYA tempat runtime Go (gomobile) hidup.
 *
 * Berjalan di proses TERPISAH ":goengine" (dideklarasikan di manifest).
 * Alasan arsitektur (TAHAP 1 + TAHAP 3):
 *  - Semua pemanggilan Mobile.* terkonsentrasi di sini. Komponen proses
 *    utama mengontrol engine lewat AIDL IEngineControl (START/STOP/
 *    GET_STATUS/GET_DEBUG_MEM) — tidak ada lagi split-brain (runtime Go
 *    kedua di proses utama yang melihat "engine hantu").
 *  - STOP = hard kill proses ini: seluruh memori Go (heap, goroutine,
 *    buffer pool) dikembalikan ke OS seketika oleh kernel, tanpa risiko
 *    hang pada Mobile.stop() (yang menutup device WireGuard satu per satu).
 *
 * Status ke proses utama dikirim via:
 *  - reply binder startEngine()/getStatus() (kanal utama), dan
 *  - broadcast ACTION_STATUS dengan setPackage() pada transisi
 *    (sukses/gagal/ready/error) — kanal asinkron tambahan.
 * Poll berkala 2 detik DIHAPUS: status kini ditarik lewat GET_STATUS.
 */
public class GoEngineService extends Service {
    private static final String TAG = "GoEngineService";
    public static final String ACTION_STATUS = "com.wpmulti.test.GO_STATUS";
    public static final String EXTRA_RUNNING = "running";
    public static final String EXTRA_SESSIONS = "sessions";
    public static final String EXTRA_ERROR = "error";

    /** Alamat proxy default (tetap sama dengan perilaku sebelumnya). */
    private static final String SOCKS_ADDR = "127.0.0.1:1080";
    private static final String HTTP_ADDR = "127.0.0.1:8080";

    // TAHAP 3 (Task 10) — pelepasan memori idle.
    // Tiap 60 dtk: jumlahkan TX+RX semua sesi (Mobile.sessionStats(),
    // JSON tx_bytes/rx_bytes per sesi). Bila total TIDAK berubah selama
    // 3 interval berturut-turut (~3 menit tanpa trafik) -> panggil
    // debug.FreeOSMemory() (GC + scavenge: halaman idle dikembalikan ke
    // OS). Bila trafik aktif (delta != 0) monitor TIDAK pernah membebaskan
    // memori. GOGC tidak diubah.
    private static final long IDLE_CHECK_INTERVAL_MS = 60_000L;
    private static final int IDLE_TICKS_REQUIRED = 3;

    private volatile Thread idleMonitor;
    private volatile java.lang.reflect.Method freeOsMemoryMethod;
    private volatile boolean freeOsMemoryMissing;

    private final Object startLock = new Object();

    private final IEngineControl.Stub binder = new IEngineControl.Stub() {

        @Override
        public String startEngine(String confDir) {
            synchronized (startLock) {
                try {
                    // Temp dir khusus proses engine (cache dir sama untuk
                    // semua proses app — per user/per package).
                    Mobile.setTempDir(getCacheDir().getAbsolutePath());
                    Log.i(TAG, "Mobile.start: " + confDir);
                    String err = Mobile.start(confDir, SOCKS_ADDR, HTTP_ADDR);
                    if (err != null && !err.isEmpty()) {
                        Log.e(TAG, "start gagal: " + err);
                        broadcastStatus(false, 0, err);
                        return err;
                    }
                    long n = Mobile.sessionCount();
                    Log.i(TAG, "running, sesi=" + n);
                    broadcastStatus(true, (int) n, null);
                    startIdleMonitor(); // TAHAP 3: pemantau idle memori
                    return "";
                } catch (Exception e) {
                    Log.e(TAG, "start exception", e);
                    String msg = e.toString();
                    broadcastStatus(false, 0, msg);
                    return msg;
                }
            }
        }

        @Override
        public void stopEngine() {
            // TAHAP 3 — HARD KILL proses :goengine.
            // Urutan: stopForeground -> (fd TUN ditutup DI PROSES UTAMA
            // oleh VpnEngine.disconnect() sebelum perintah ini dikirim;
            // proses ini tidak memegang TUN apa pun — engine userspace
            // netstack) -> kill proses.
            // Sengaja TANPA System.exit() dan TANPA Mobile.stop():
            // kernel menutup semua fd/socket dan membebaskan seluruh
            // memori Go seketika; tidak ada jalur teardown yang bisa
            // menggantung (dev.Close() satu per satu).
            // Metode ini oneway — pemanggil tidak menunggu reply karena
            // proses ini memang akan mati di sini.
            Log.w(TAG, "STOP: hard kill proses :goengine (pid=" + Process.myPid() + ")");
            try { stopForeground(true); } catch (Exception ignored) {}
            // Best effort: kabari proses utama (kemungkinan tak sempat
            // terkirim; kematian binder tetap terdeteksi via linkToDeath).
            broadcastStatus(false, 0, null);
            Process.killProcess(Process.myPid());
        }

        @Override
        public EngineStatus getStatus(boolean includeSessions) {
            EngineStatus s = new EngineStatus();
            try { s.running = Mobile.isRunning(); } catch (Exception e) { s.running = false; }
            try { s.sessions = s.running ? Mobile.sessionCount() : 0; }
            catch (Exception e) { s.sessions = 0; }
            try { s.goroutines = s.running ? (int) Mobile.goroutineCount() : 0; }
            catch (Exception e) { s.goroutines = 0; }
            try { s.memStats = s.running ? Mobile.memStats() : null; }
            catch (Exception e) { s.memStats = null; }
            s.sessionStats = null;
            if (s.running && includeSessions) {
                try { s.sessionStats = Mobile.sessionStats(); }
                catch (Exception ignored) {}
            }
            // Path unix socket relay — dibuat oleh runtime Go proses ini;
            // proses utama (VpnEngine) wajib memakai nilai dari sini,
            // bukan dari runtime Go-nya sendiri.
            try { s.socksPath = Mobile.socksSocketPath(); } catch (Exception e) { s.socksPath = null; }
            try { s.udpPath = Mobile.udpSocketPath(); } catch (Exception e) { s.udpPath = null; }
            try { s.icmpPath = Mobile.icmpSocketPath(); } catch (Exception e) { s.icmpPath = null; }
            s.lastError = null;
            return s;
        }

        @Override
        public String getDebugMem() {
            // TAHAP 4 — detail memori runtime Go: memStats mentah (heap,
            // sys, dsb.) + jumlah goroutine. Catatan: binding per-pool
            // (out/max tiap pool) belum tersedia di AAR prebuilt; yang
            // tersedia adalah seluruh field runtime/mem.
            StringBuilder sb = new StringBuilder();
            try { sb.append(Mobile.memStats()); }
            catch (Exception e) { sb.append("memStats error: ").append(e); }
            try { sb.append("\ngoroutines: ").append(Mobile.goroutineCount()); }
            catch (Exception ignored) {}
            return sb.toString();
        }

        @Override
        public String writeProfile(boolean heap, String path) {
            try {
                return heap ? Mobile.writeHeapProfile(path)
                        : Mobile.writeGoroutineProfile(path);
            } catch (Exception e) {
                return e.toString();
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        // Log Go ditulis dari proses engine (satu pemilik log).
        try {
            java.io.File extDir = getExternalFilesDir(null);
            if (extDir != null) {
                Mobile.setLogFile(extDir.getAbsolutePath() + "/wpmulti.log");
            }
        } catch (Exception ignored) {}
        // Jembatan StatusListener Go -> Android: event engine diterjemahkan
        // jadi broadcast (setPackage) ke proses utama. Dulu didaftarkan di
        // MainActivity (proses utama) — itu split-brain: yang mendengar
        // callback adalah runtime Go proses utama, bukan proses engine.
        try {
            Mobile.setStatusListener(new StatusListener() {
                @Override public void onSessionUp(long up, long total) {
                    // progress per sesi — tidak perlu broadcast tiap event
                }
                @Override public void onReady(long total) {
                    Log.i(TAG, "onReady: " + total + " sesi");
                    broadcastStatus(true, (int) total, null);
                }
                @Override public void onError(String message) {
                    // OnError hanya dikirim saat Start gagal (core Go)
                    Log.e(TAG, "onError: " + message);
                    broadcastStatus(false, 0, message);
                }
            });
        } catch (Exception ignored) {}
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Jalur resmi kontrol engine = AIDL (binder di atas). startService
        // dengan extra confDir tetap didukung sebagai jalur legacy agar
        // perilaku lama tidak langsung patah.
        if (intent == null) return START_NOT_STICKY;
        final String confDir = intent.getStringExtra("confDir");
        if (confDir != null) {
            // Panggilan lokal (stub di proses yang sama tetap lewat Binder
            // transact) — RemoteException tetap mungkin; tangkap agar
            // thread legacy tidak mati tanpa jejak.
            new Thread(() -> {
                try {
                    binder.startEngine(confDir);
                } catch (Exception e) {
                    Log.e(TAG, "startEngine (legacy) gagal: " + e);
                }
            }).start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopIdleMonitor();
        // Stop resmi = hard kill (proses mati tanpa onDestroy). Jika
        // onDestroy tetap terpanggil (stopService oleh sistem), laporkan
        // kondisi terakhir apa adanya; engine TIDAK dihentikan di sini
        // karena teardown per-device bisa menggantung (prinsip TAHAP 3).
        boolean r;
        try { r = Mobile.isRunning(); } catch (Exception e) { r = false; }
        broadcastStatus(r, r ? (int) Mobile.sessionCount() : 0, null);
        super.onDestroy();
    }

    // ---------- TAHAP 3: pelepasan memori idle ----------

    /**
     * Mulai pemantau idle di proses :goengine. Dipanggil sekali tepat
     * setelah start sukses; thread mati sendiri saat engine berhenti atau
     * service dihancurkan. Tidak mengubah GOGC dan tidak menyentuh
     * WaitPool/logika WireGuard.
     */
    private void startIdleMonitor() {
        stopIdleMonitor();
        Thread t = new Thread(() -> {
            long prevTotal = -1;
            int idleTicks = 0;
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(IDLE_CHECK_INTERVAL_MS);
                } catch (InterruptedException e) {
                    break;
                }
                long total;
                try {
                    if (!Mobile.isRunning()) break; // engine mati
                    total = sumSessionTraffic();
                } catch (Throwable ignored) {
                    continue; // engine sibuk / transien -> coba tick berikut
                }
                // total == prevTotal berarti TX+RX selama interval ini 0.
                // delta != 0 (termasuk negatif karena counter reset saat
                // sesi reconnect) dianggap aktivitas -> reset gate.
                if (prevTotal >= 0 && total == prevTotal) idleTicks++;
                else idleTicks = 0;
                prevTotal = total;
                if (idleTicks >= IDLE_TICKS_REQUIRED) {
                    freeOsMemory(idleTicks);
                }
            }
        }, "GoEngine-idleGC");
        t.setDaemon(true);
        idleMonitor = t;
        t.start();
        Log.i(TAG, "idle monitor aktif (cek tiap "
                + (IDLE_CHECK_INTERVAL_MS / 1000) + " dtk, butuh "
                + IDLE_TICKS_REQUIRED + " interval tanpa trafik)");
    }

    private void stopIdleMonitor() {
        Thread t = idleMonitor;
        if (t != null) {
            t.interrupt();
            idleMonitor = null;
        }
    }

    /** Jumlahkan tx_bytes + rx_bytes semua sesi dari Mobile.sessionStats(). */
    private long sumSessionTraffic() throws Exception {
        String json = Mobile.sessionStats();
        long total = 0;
        if (json != null && !json.isEmpty()) {
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject o = arr.getJSONObject(i);
                total += o.optLong("tx_bytes") + o.optLong("rx_bytes");
            }
        }
        return total;
    }

    /**
     * debug.FreeOSMemory di proses engine. Binding mobile.freeOSMemory()
     * tersedia setelah wpmulti di-rebuild dengan patch Task 10
     * (0001-ram-bufferpool-32k-FreeOSMemory.patch); AAR saat ini belum
     * memilikinya, jadi dipanggil via refleksi + log sekali agar
     * transparan - bukan crash.
     */
    private void freeOsMemory(int idleTicks) {
        try {
            java.lang.reflect.Method m = freeOsMemoryMethod;
            if (m == null) {
                m = Mobile.class.getMethod("freeOSMemory");
                freeOsMemoryMethod = m;
            }
            m.invoke(null);
            Log.i(TAG, "FreeOSMemory selesai (idle " + idleTicks
                    + " interval, total TX+RX "
                    + "tidak berubah) - memori idle dikembalikan ke OS");
        } catch (NoSuchMethodException e) {
            if (!freeOsMemoryMissing) {
                freeOsMemoryMissing = true;
                Log.w(TAG, "binding freeOSMemory belum ada di AAR - rebuild "
                        + "wpmulti dengan patch 0001-ram-bufferpool-32k "
                        + "(TAHAP 3 penuh aktif setelahnya)");
            }
        } catch (Throwable t) {
            Log.w(TAG, "FreeOSMemory gagal: " + t);
        }
    }

    private void broadcastStatus(boolean isRunning, int sessions, String error) {
        Intent i = new Intent(ACTION_STATUS);
        i.putExtra(EXTRA_RUNNING, isRunning);
        i.putExtra(EXTRA_SESSIONS, sessions);
        if (error != null) i.putExtra(EXTRA_ERROR, error);
        // WAJIB setPackage: broadcast implisit tanpa package bisa terdiam
        // di sebagian OEM Android 13/14 untuk receiver NOT_EXPORTED.
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }
}
