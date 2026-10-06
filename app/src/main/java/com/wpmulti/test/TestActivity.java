package com.wpmulti.test;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import java.io.File;
import java.io.FileWriter;

/**
 * TestActivity v110: Multi-siklus + dump mode via intent.
 * Dump: adb shell am start -n com.wpmulti.test/.TestActivity --ez dump true
 *
 * TAHAP 1: seluruh operasi engine lewat EngineClient (AIDL ke :goengine)
 * — tidak ada lagi Mobile.* langsung (split-brain). TAHAP 3: STOP kini
 * hard-kill proses :goengine, sehingga setelah stop siklus berikutnya
 * otomatis rebind ke PROSES BARU (goroutine/memory kembali ke basis —
 * bukti teardown penuh, bukan hanya angka pasca-Mobile.stop()).
 */
public class TestActivity extends Activity {
    private static final String TAG = "TestActivity";
    private Handler handler = new Handler(Looper.getMainLooper());
    private StringBuilder result = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EngineClient.get().init(this);
        boolean dumpOnly = getIntent().getBooleanExtra("dump", false);
        log("TestActivity v110 dimulai, dumpOnly=" + dumpOnly);
        new Thread(() -> {
            try {
                if (dumpOnly) {
                    doDump();
                } else {
                    runTest();
                }
            } catch (Exception e) {
                log("ERROR: " + e);
            } finally {
                writeResult();
                handler.post(() -> finish());
            }
        }).start();
    }

    private void doDump() throws Exception {
        File extDir = getExternalFilesDir(null);
        if (extDir == null) {
            log("extDir null");
            return;
        }
        String path = extDir.getAbsolutePath() + "/goroutine.txt";
        try {
            // Dump goroutine dari PROSES ENGINE (binder writeProfile).
            String err = EngineClient.get().writeProfile(false, path, 15000);
            if (err == null || err.isEmpty()) {
                log("Dump ditulis ke " + path);
                result.append("DUMP_OK=").append(path).append("\n");
            } else {
                log("writeGoroutineProfile gagal: " + err);
                result.append("DUMP_FAIL=").append(err).append("\n");
            }
        } catch (Exception e) {
            log("writeGoroutineProfile gagal: " + e);
            result.append("DUMP_FAIL=").append(e).append("\n");
        }
        try {
            // TAHAP 4: GET_DEBUG_MEM — detail memori runtime Go.
            String mem = EngineClient.get().getDebugMem(10000);
            EngineStatus s = EngineClient.get().fetchStatus(false, 5000);
            long g = (s != null) ? s.goroutines : -1;
            log("goroutine=" + g + " mem=" + mem);
            result.append("GOROUTINE=").append(g).append("\n");
            result.append("MEM=").append(mem).append("\n");
        } catch (Exception e) {
            log("stats gagal: " + e);
        }
    }

    private void runTest() throws Exception {
        File confDir = new File(getFilesDir(), "confs");
        File[] confs = confDir.listFiles((d, name) -> name.endsWith(".conf"));
        int nConfs = (confs != null) ? confs.length : 0;
        log("confs: " + nConfs);
        if (nConfs == 0) {
            result.append("STATUS=GAGAL_NO_CONF\n");
            return;
        }
        // Catatan: stop terdahulu = hard kill proses; siklus berikutnya
        // otomatis bind ke proses :goengine BARU (EngineClient menangani).
        for (int cycle = 1; cycle <= 3; cycle++) {
            log("=== SIKLUS " + cycle + " START ===");
            long t0 = System.currentTimeMillis();
            String err = EngineClient.get().startBlocking(
                    confDir.getAbsolutePath(), 60_000);
            long tStart = System.currentTimeMillis() - t0;
            if (err != null && !err.isEmpty()) {
                log("START GAGAL: " + err);
                result.append("CYCLE" + cycle + "_STATUS=GAGAL_START\n");
                return;
            }
            log("START OK dalam " + tStart + " ms");
            Thread.sleep(8000);
            EngineStatus s = EngineClient.get().fetchStatus(true, 5000);
            long gRun = (s != null) ? s.goroutines : -1;
            String memRun = EngineClient.get().getDebugMem(10000);
            if (memRun == null) memRun = "err: tidak tersambung";
            log("Cycle " + cycle + " RUNNING: g=" + gRun + " mem=" + memRun);
            result.append("CYCLE" + cycle + "_GOROUTINE_RUN=").append(gRun).append("\n");
            result.append("CYCLE" + cycle + "_MEM_RUN=").append(memRun).append("\n");
            try {
                EngineClient.get().writeProfile(false,
                        getExternalFilesDir(null).getAbsolutePath()
                                + "/goroutine_cycle" + cycle + ".txt", 15000);
            } catch (Exception e) { log("dump cycle gagal: " + e); }
            log("=== SIKLUS " + cycle + " STOP (hard kill) ===");
            t0 = System.currentTimeMillis();
            EngineClient.get().stop();
            long tStop = System.currentTimeMillis() - t0;
            Thread.sleep(3000);
            // Setelah hard kill + rebind otomatis: proses BARU.
            EngineStatus s2 = EngineClient.get().fetchStatus(false, 5000);
            long gStop = (s2 != null) ? s2.goroutines : -1;
            String memStop = EngineClient.get().getDebugMem(10000);
            if (memStop == null) memStop = "err: tidak tersambung";
            log("Cycle " + cycle + " STOPPED (proses baru): g=" + gStop
                    + " stopMs=" + tStop);
            result.append("CYCLE" + cycle + "_GOROUTINE_STOP=").append(gStop).append("\n");
            result.append("CYCLE" + cycle + "_MEM_STOP=").append(memStop).append("\n");
            result.append("CYCLE" + cycle + "_STOP_MS=").append(tStop).append("\n");
        }
        result.append("STATUS=SELESAI\n");
    }

    private void log(String msg) {
        android.util.Log.i(TAG, msg);
        result.append("LOG: ").append(msg).append("\n");
    }

    private void writeResult() {
        try {
            File extDir = getExternalFilesDir(null);
            if (extDir != null) {
                File out = new File(extDir, "test_result.txt");
                FileWriter w = new FileWriter(out);
                w.write(result.toString());
                w.close();
            }
        } catch (Exception e) {
            android.util.Log.e(TAG, "Gagal tulis: " + e);
        }
    }
}
