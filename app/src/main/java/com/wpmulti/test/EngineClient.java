package com.wpmulti.test;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * EngineClient — SATU pintu kontrol engine dari proses utama.
 *
 * WAJIB dipakai oleh semua komponen proses utama (MainActivity,
 * VpnTileService, VpnControlActivity, TestActivity, ProxyKeepaliveService,
 * VpnEngine). Dilarang memanggil mobile.Mobile.* langsung: runtime gomobile
 * hanya hidup di proses :goengine; pemanggilan Mobile.* di proses utama
 * akan memunculkan runtime Go KEDUA yang tidak melihat engine (split-brain).
 *
 * Perintah (AIDL IEngineControl):
 *   - START(configDir)   : startBlocking(), blokir sampai engine siap.
 *   - STOP               : stop(), hard kill proses :goengine.
 *   - GET_STATUS         : fetchStatus()/refreshAsync(), satu sumber kebenaran.
 *   - GET_DEBUG_MEM      : getDebugMem(), detail memori runtime Go.
 *   - writeProfile       : dump goroutine/heap dari proses engine.
 *
 * Ketahanan START (menggantikan mekanisme v1.2 yang poll Mobile.isRunning):
 * hasil start dikonfirmasi lewat TIGA kanal independen — (1) reply binder
 * startEngine, (2) broadcast ACTION_STATUS (setPackage) dari service,
 * (3) poll GET_STATUS via binder di MainActivity (timeout 60 dtk).
 *
 * Ketahanan STOP (TAHAP 3): flag userRequestedStop diset SEBELUM perintah
 * dikirim; kematian proses engine terdeteksi via onServiceDisconnected +
 * linkToDeath, dan karena userRequestedStop aktif, UI menampilkannya sebagai
 * "berhenti normal" — bukan crash.
 */
public final class EngineClient {

    /** Pemantau status engine di proses utama (callback di thread bebas). */
    public interface Listener {
        /** Status terbaru dari GET_STATUS (binder) atau snapshot internal. */
        void onStatus(EngineStatus s);

        /** Koneksi ke :goengine putus (proses mati). */
        void onEngineGone(boolean userRequested);
    }

    private static final EngineClient INSTANCE = new EngineClient();

    public static EngineClient get() { return INSTANCE; }

    private EngineClient() {}

    private final Object lock = new Object();
    private Context appCtx;
    private volatile IEngineControl svc;
    private volatile EngineStatus last = EngineStatus.stopped();
    private volatile boolean userRequestedStop = false;
    private boolean bound;
    private final List<Listener> listeners = new ArrayList<>();
    private IBinder.DeathRecipient deathRecipient;

    private final ServiceConnection conn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            // Setter service WAJIB synchronized(lock) + lock.notifyAll():
            // awaitConnected() wait() di monitor `lock`, jadi pemberitahuan
            // harus pada monitor yang SAMA.
            synchronized (lock) {
                svc = IEngineControl.Stub.asInterface(binder);
                if (deathRecipient != null) {
                    try { binder.unlinkToDeath(deathRecipient, 0); }
                    catch (Exception ignored) {}
                }
                deathRecipient = () -> handleGone();
                try { binder.linkToDeath(deathRecipient, 0); }
                catch (RemoteException ignored) {}
                lock.notifyAll();
            }
            Log.i(TAG, "tersambung ke :goengine");
            refreshAsync(); // isi snapshot pertama
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Log.w(TAG, "onServiceDisconnected (proses engine mati)");
            setService(null); // synchronized(lock) + notifyAll
            handleGone();
        }

        @Override
        public void onBindingDied(ComponentName name) {
            Log.w(TAG, "onBindingDied");
            forceUnbind();
            setService(null); // synchronized(lock) + notifyAll
            handleGone();
        }

        @Override
        public void onNullBinding(ComponentName name) {
            Log.w(TAG, "onNullBinding");
            forceUnbind();
        }
    };

    private void handleGone() {
        boolean user = userRequestedStop;
        last = EngineStatus.stopped();
        for (Listener l : snapshotListeners()) {
            try { l.onEngineGone(user); } catch (Exception ignored) {}
        }
        // Tidak auto-rebind: biarkan :goengine tetap mati (memori kembali ke
        // OS). Binding berikutnya dibuat otomatis oleh start/fetch berikutnya.
    }

    private void forceUnbind() {
        synchronized (lock) {
            if (bound && appCtx != null) {
                try { appCtx.unbindService(conn); } catch (Exception ignored) {}
            }
            bound = false;
            lock.notifyAll();
        }
    }

    private List<Listener> snapshotListeners() {
        synchronized (lock) { return new ArrayList<>(listeners); }
    }

    /**
     * Setter tunggal field svc. WAJIB dipakai semua pengubah service:
     * synchronized(lock) { svc = ...; lock.notifyAll(); } — monitor yang
     * sama dengan wait() di awaitConnected(), kalau tidak waiters tidak
     * akan pernah bangun (atau meledak IllegalMonitorStateException).
     */
    private void setService(IEngineControl s) {
        synchronized (lock) {
            svc = s;
            lock.notifyAll();
        }
    }

    /** Inisialisasi context aplikasi (aman dipanggil berulang). */
    public void init(Context ctx) {
        synchronized (lock) {
            if (appCtx == null) appCtx = ctx.getApplicationContext();
        }
    }

    public void addListener(Listener l) {
        synchronized (lock) { if (!listeners.contains(l)) listeners.add(l); }
    }

    public void removeListener(Listener l) {
        synchronized (lock) { listeners.remove(l); }
    }

    /** Snapshot status terakhir TANPA I/O (dipakai label UI & hot path). */
    public EngineStatus snapshot() { return last; }

    public boolean connected() { return svc != null; }

    private void bindLocked() {
        if (bound || appCtx == null) return;
        Intent it = new Intent(appCtx, GoEngineService.class);
        try {
            // BIND_AUTO_CREATE: proses :goengine dibuat saat dibutuhkan.
            appCtx.bindService(it, conn, Context.BIND_AUTO_CREATE);
            bound = true;
        } catch (Exception e) {
            Log.w(TAG, "bind gagal: " + e);
        }
    }

    /**
     * Pastikan binding ke :goengine terbentuk; tunggu maksimal timeoutMs.
     * Return true bila binder siap dipakai.
     *
     * Pola wait yang BENAR (v1.4): wait() selalu dipanggil di dalam
     * synchronized(lock) pada monitor `lock` — persis objek yang sama
     * dengan receiver wait(). v1.3 memanggil waiter.wait() padahal thread
     * hanya memegang monitor `lock` -> IllegalMonitorStateException
     * ("object not locked by thread before wait()"). Kini waiters menunggu
     * langsung di `lock`; semua setter svc (onServiceConnected /
     * onServiceDisconnected / onBindingDied / pemulih RemoteException)
     * melakukan notifyAll() pada `lock` lewat setService(). Loop while
     * menanggalkan spurious wakeup dan bangun karena svc=null.
     */
    public boolean awaitConnected(long timeoutMs) {
        long deadline = SystemClock.elapsedRealtime() + timeoutMs;
        synchronized (lock) {
            bindLocked(); // belum bound -> bind dulu, jangan hanya wait
            while (svc == null) {
                long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) return false;
                try {
                    lock.wait(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * START(configDir): blokir sampai engine siap atau error/timeout.
     * HARUS dipanggil dari background thread (binder call memblokir ~detik).
     * Return "" bila sukses, selain itu pesan error.
     */
    public String startBlocking(String confDir, long timeoutMs) {
        initNeeded();
        userRequestedStop = false; // start baru -> reset flag stop
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
        long wait = Math.min(timeoutMs, 15_000);
        if (!awaitConnected(wait)) return "timeout menunggu layanan engine (:goengine)";
        long remain = deadline - SystemClock.uptimeMillis();
        if (remain <= 0) return "timeout";
        try {
            String err = svc.startEngine(confDir);
            EngineStatus s = tryFetch(false);
            if (s != null) last = s;
            return err == null ? "" : err;
        } catch (RemoteException e) {
            setService(null);
            return "proses engine mati saat start: " + e;
        }
    }

    /**
     * STOP: hard kill proses :goengine. userRequestedStop diset SEBELUM
     * perintah dikirim agar kematian proses tidak dianggap crash. Binding
     * dilepas setelahnya supaya framework tidak membangkitkan proses baru.
     */
    public void stop() {
        userRequestedStop = true;
        IEngineControl s = svc;
        if (s != null) {
            try { s.stopEngine(); } // oneway: reply tak pernah datang
            catch (Exception ignored) {}
        }
        last = EngineStatus.stopped();
        new Thread(() -> {
            try { Thread.sleep(600); } catch (InterruptedException ignored) {}
            forceUnbind();
        }).start();
    }

    /**
     * GET_STATUS sinkron via binder (blokir maks timeoutMs untuk koneksi).
     * Return null bila gagal (engine proses mati / tidak tersambung).
     */
    public EngineStatus fetchStatus(boolean includeSessions, long timeoutMs) {
        initNeeded();
        if (!awaitConnected(timeoutMs)) return null;
        EngineStatus s = tryFetch(includeSessions);
        if (s != null) last = s;
        return s;
    }

    private EngineStatus tryFetch(boolean includeSessions) {
        IEngineControl s = svc;
        if (s == null) return null;
        try { return s.getStatus(includeSessions); }
        catch (RemoteException e) { setService(null); return null; }
    }

    /**
     * GET_STATUS asinhron di background thread + notify listeners.
     * v1.4: exception APAPUN ditangkap — thread tidak boleh mati diam-diam.
     * Bila status tidak bisa diambil (tidak terhubung), listener tetap
     * dipanggil dengan snapshot terakhir (default EngineStatus.stopped(),
     * running=false) supaya UI selalu menerima status dan tidak menggantung.
     * Kematian proses yang pasti tetap dilaporkan lewat onEngineGone.
     */
    public void refreshAsync() {
        new Thread(() -> {
            EngineStatus s;
            try {
                s = fetchStatus(false, 5000);
            } catch (Throwable t) {
                Log.w(TAG, "refreshAsync gagal (tidak terhubung): " + t);
                s = null;
            }
            if (s == null) s = last; // status "tidak terhubung" terbaik yang ada
            final EngineStatus fs = s;
            for (Listener l : snapshotListeners()) {
                try { l.onStatus(fs); } catch (Exception ignored) {}
            }
        }, "EngineClient-refresh").start();
    }

    /** GET_DEBUG_MEM (blokir maks timeoutMs). Null bila gagal. */
    public String getDebugMem(long timeoutMs) {
        initNeeded();
        if (!awaitConnected(timeoutMs)) return null;
        try { return svc.getDebugMem(); }
        catch (RemoteException e) { setService(null); return null; }
    }

    /** Dump goroutine/heap dari proses engine (blokir maks timeoutMs). */
    public String writeProfile(boolean heap, String path, long timeoutMs) {
        initNeeded();
        if (!awaitConnected(timeoutMs)) return "timeout menunggu layanan engine";
        try { return svc.writeProfile(heap, path); }
        catch (RemoteException e) { setService(null); return "engine mati: " + e; }
    }

    /** isRunning sinkron singkat — untuk QS tile (tidak boleh lama). */
    public boolean isRunningSync(long timeoutMs) {
        EngineStatus s = fetchStatus(false, timeoutMs);
        return s != null && s.running;
    }

    /** Path unix socket relay dari snapshot terakhir (untuk VpnEngine). */
    public String socksPath() { EngineStatus s = last; return s == null ? null : s.socksPath; }
    public String udpPath() { EngineStatus s = last; return s == null ? null : s.udpPath; }
    public String icmpPath() { EngineStatus s = last; return s == null ? null : s.icmpPath; }

    private void initNeeded() {
        synchronized (lock) {
            if (appCtx == null) throw new IllegalStateException(
                    "EngineClient.init(context) belum dipanggil");
        }
    }

    private static final String TAG = "EngineClient";
}
