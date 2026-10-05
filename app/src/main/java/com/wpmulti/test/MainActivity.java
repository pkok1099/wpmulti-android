package com.wpmulti.test;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import mobile.Mobile;
import mobile.StatusListener;

public class MainActivity extends Activity {
    private static final int MAX_PER_CONFIG = 240;
    private static final int PICK_CONF = 1001;

    // engine states
    private static final int ST_IDLE = 0;
    private static final int ST_STARTING = 1;
    private static final int ST_RUNNING = 2;
    private static final int ST_STOPPING = 3;
    private volatile int engineState = ST_IDLE;

    private TextView headerStats;
    private TextView statusBar;
    private TextView logView;
    private TextView totalView;
    private TextView verifyView;
    private TextView testResult;
    private TextView monRam, monCpu, monCache, monSesi;
    private LinearLayout configContainer;
    private LinearLayout proxyTable;
    private EditText logFilter;
    private Spinner logLevel;
    private final List<Profile> profiles = new ArrayList<>();
    private final List<EditText> countFields = new ArrayList<>();
    private final List<View> configRows = new ArrayList<>();
    private final List<String> logLines = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean monitorOn = false;
    private String logLevelSel = "Semua";
    private String filterText = "";

    static class Profile {
        String name;
        File file;
        int count;
        Profile(String n, File f) { name = n; file = f; }
    }

    private void log(String s) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date());
        final String line = "[" + ts + "] " + s;
        ui.post(() -> {
            logLines.add(line);
            renderLog();
        });
    }

    private void renderLog() {
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) {
            if (!logLevelSel.equals("Semua")) {
                boolean isErr = l.contains("ERROR") || l.contains("gagal")
                        || l.contains("GAGAL") || l.contains("exception");
                if (logLevelSel.equals("Error") && !isErr) continue;
            }
            if (!filterText.isEmpty() && !l.contains(filterText)) continue;
            sb.append(l).append('\n');
        }
        logView.setText(sb.toString());
    }

    private int totalSesi() {
        int t = 0;
        for (Profile p : profiles) t += p.count;
        return t;
    }

    private void updateTotal() {
        final int t = totalSesi();
        ui.post(() -> totalView.setText("total sesi: " + t));
    }

    private void updateHeader(String ram, String cpu) {
        ui.post(() -> headerStats.setText("RAM: " + ram + " | CPU: " + cpu));
    }

    // ---------- Engine state ----------
    private void setEngineState(int st) {
        engineState = st;
        ui.post(() -> {
            Button btn = findViewById(R.id.btnEngine);
            switch (st) {
                case ST_IDLE:
                    btn.setText("\u25B6 START");
                    btn.setEnabled(true);
                    statusBar.setText("\uD83D\uDD34 BERHENTI");
                    statusBar.setTextColor(0xFFFF5252);
                    break;
                case ST_STARTING:
                    btn.setText("\u23F3 MEMULAI...");
                    btn.setEnabled(false);
                    statusBar.setText("\uD83D\uDFE1 MEMULAI...");
                    statusBar.setTextColor(0xFFFFD740);
                    break;
                case ST_RUNNING:
                    btn.setText("\u25A0 STOP");
                    btn.setEnabled(true);
                    statusBar.setText("\uD83D\uDFE2 BERJALAN \u2014 "
                            + Mobile.sessionCount() + " sesi");
                    statusBar.setTextColor(0xFF69F0AE);
                    break;
                case ST_STOPPING:
                    btn.setText("\u23F3 MENGHENTIKAN...");
                    btn.setEnabled(false);
                    statusBar.setText("\uD83D\uDFE1 MENGHENTIKAN...");
                    statusBar.setTextColor(0xFFFFD740);
                    break;
            }
            boolean cfg = (st == ST_IDLE);
            findViewById(R.id.addBtn).setEnabled(cfg);
            findViewById(R.id.addBtn).setAlpha(cfg ? 1f : 0.4f);
            for (View row : configRows) {
                for (int id : new int[]{R.id.minus, R.id.plus, R.id.max,
                        R.id.del, R.id.count}) {
                    row.findViewById(id).setEnabled(cfg);
                }
            }
            boolean tst = (st == ST_RUNNING);
            findViewById(R.id.test1Btn).setEnabled(tst);
            findViewById(R.id.test20Btn).setEnabled(tst);
        });
    }

    // ---------- Sidebar ----------
    private void openSidebar() {
        View sidebar = findViewById(R.id.sidebar);
        View scrim = findViewById(R.id.scrim);
        if (sidebar.getVisibility() == View.VISIBLE) return;
        sidebar.setVisibility(View.VISIBLE);
        sidebar.post(() -> {
            sidebar.setTranslationX(-sidebar.getWidth());
            sidebar.animate().translationX(0).setDuration(220).start();
        });
        scrim.setVisibility(View.VISIBLE);
        scrim.setAlpha(0f);
        scrim.animate().alpha(1f).setDuration(220).start();
    }

    private void closeSidebar() {
        View sidebar = findViewById(R.id.sidebar);
        View scrim = findViewById(R.id.scrim);
        if (sidebar.getVisibility() != View.VISIBLE) return;
        sidebar.animate().translationX(-sidebar.getWidth()).setDuration(220)
                .withEndAction(() -> sidebar.setVisibility(View.GONE)).start();
        scrim.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> scrim.setVisibility(View.GONE)).start();
    }

    // ---------- Pages ----------
    private void showPage(int idx) {
        int[] pages = {R.id.pageConfig, R.id.pageProxy, R.id.pageMonitor,
                R.id.pageLog, R.id.pageSetting};
        int[] menus = {R.id.menuConfig, R.id.menuProxy, R.id.menuMonitor,
                R.id.menuLog, R.id.menuSetting};
        for (int i = 0; i < pages.length; i++) {
            findViewById(pages[i]).setVisibility(
                    i == idx ? View.VISIBLE : View.GONE);
            findViewById(menus[i]).setBackgroundColor(
                    i == idx ? 0x33FFFFFF : 0x00000000);
        }
        closeSidebar();
    }

    // ---------- Config rows ----------
    private void rebuildConfigRows() {
        configContainer.removeAllViews();
        countFields.clear();
        configRows.clear();
        for (int i = 0; i < profiles.size(); i++) {
            final int idx = i;
            Profile pr = profiles.get(i);
            View row = LayoutInflater.from(this)
                    .inflate(R.layout.row_config, configContainer, false);
            ((TextView) row.findViewById(R.id.label)).setText(pr.name);
            EditText et = row.findViewById(R.id.count);
            et.setText(String.valueOf(pr.count));
            countFields.add(et);
            et.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) {
                    try {
                        int v = Integer.parseInt(s.toString());
                        if (v < 0) v = 0;
                        if (v > MAX_PER_CONFIG) {
                            v = MAX_PER_CONFIG;
                            et.setText(String.valueOf(v));
                            et.setSelection(et.getText().length());
                        }
                        profiles.get(idx).count = v;
                    } catch (NumberFormatException e) {
                        profiles.get(idx).count = 0;
                    }
                    updateTotal();
                }
            });
            row.findViewById(R.id.minus).setOnClickListener(v -> {
                Profile p = profiles.get(idx);
                if (p.count > 0) {
                    p.count--;
                    et.setText(String.valueOf(p.count));
                }
            });
            row.findViewById(R.id.plus).setOnClickListener(v -> {
                Profile p = profiles.get(idx);
                if (p.count < MAX_PER_CONFIG) {
                    p.count++;
                    et.setText(String.valueOf(p.count));
                }
            });
            row.findViewById(R.id.max).setOnClickListener(v -> {
                profiles.get(idx).count = MAX_PER_CONFIG;
                et.setText(String.valueOf(MAX_PER_CONFIG));
            });
            row.findViewById(R.id.del).setOnClickListener(v -> {
                Profile rm = profiles.remove(idx);
                if (rm.file.exists()) rm.file.delete();
                rebuildConfigRows();
                updateTotal();
                log("profile dihapus: " + rm.name);
            });
            configContainer.addView(row);
            configRows.add(row);
        }
        updateTotal();
        // terapkan lock jika engine tidak idle
        if (engineState != ST_IDLE) setEngineState(engineState);
    }

    private File profilesDir() {
        File d = new File(getFilesDir(), "profiles");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    private void loadProfiles() {
        profiles.clear();
        String[] bundled = {"wireproxy-1.conf", "wireproxy-2.conf",
                "wireproxy-3.conf", "wireproxy-4.conf", "wireproxy-5.conf"};
        for (String b : bundled) {
            try (InputStream in = getAssets().open("confs/" + b)) {
                File out = new File(profilesDir(), b);
                if (!out.exists()) {
                    try (OutputStream o = new FileOutputStream(out)) {
                        byte[] buf = new byte[8192];
                        int n;
                        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                    }
                }
            } catch (Exception e) {
                log("gagal salin bundled " + b + ": " + e.getMessage());
            }
        }
        File[] fs = profilesDir().listFiles();
        if (fs != null) {
            java.util.Arrays.sort(fs, (a, b2) -> a.getName().compareTo(b2.getName()));
            for (File f : fs) profiles.add(new Profile(f.getName(), f));
        }
    }

    // ---------- Proxy table ----------
    private void rebuildProxyTable() {
        proxyTable.removeAllViews();
        boolean running = Mobile.isRunning();
        addProxyRow("SOCKS5", "127.0.0.1:1080", running);
        addProxyRow("HTTP", "127.0.0.1:8080", running);
    }

    private void addProxyRow(String name, String addr, boolean running) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.row_proxy, proxyTable, false);
        ((TextView) row.findViewById(R.id.proxyName)).setText(name);
        ((TextView) row.findViewById(R.id.proxyAddr)).setText(addr);
        ((TextView) row.findViewById(R.id.proxyStatus))
                .setText(running ? "AKTIF" : "MATI");
        row.findViewById(R.id.proxyCopy).setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "proxy", addr));
            log("proxy disalin: " + addr);
        });
        proxyTable.addView(row);
    }

    // ---------- Test IP ----------
    private void testIp(int n) {
        String urlStr = ((EditText) findViewById(R.id.testUrl)).getText().toString().trim();
        if (urlStr.isEmpty()) urlStr = "http://ifconfig.me/ip";
        final String targetUrl = urlStr;
        final boolean isIpEcho = targetUrl.contains("ifconfig.me/ip")
                || targetUrl.contains("api.ipify.org");
        new Thread(() -> {
            log("test " + n + "x via proxy -> " + targetUrl);
            ExecutorService pool = Executors.newFixedThreadPool(Math.min(n, 20));
            Map<String, Integer> cnts = new ConcurrentHashMap<>();
            Map<String, Integer> errs = new ConcurrentHashMap<>();
            CountDownLatch latch = new CountDownLatch(n);
            AtomicInteger ok = new AtomicInteger();
            AtomicInteger fail = new AtomicInteger();
            long t0 = System.currentTimeMillis();
            for (int i = 0; i < n; i++) {
                pool.submit(() -> {
                    try {
                        Proxy proxy = new Proxy(Proxy.Type.HTTP,
                                new InetSocketAddress("127.0.0.1", 8080));
                        HttpURLConnection c = (HttpURLConnection)
                                new URL(targetUrl).openConnection(proxy);
                        c.setConnectTimeout(15000);
                        c.setReadTimeout(15000);
                        int code = c.getResponseCode();
                        String firstLine = "";
                        try (BufferedReader br = new BufferedReader(
                                new InputStreamReader(c.getInputStream()))) {
                            firstLine = br.readLine();
                        } catch (Exception ignored) {}
                        if (code >= 200 && code < 300) {
                            ok.incrementAndGet();
                            if (isIpEcho && firstLine != null && !firstLine.trim().isEmpty()) {
                                cnts.merge(firstLine.trim(), 1, Integer::sum);
                            } else {
                                cnts.merge("HTTP " + code, 1, Integer::sum);
                            }
                        } else {
                            fail.incrementAndGet();
                            errs.merge("HTTP " + code, 1, Integer::sum);
                        }
                    } catch (Exception e) {
                        fail.incrementAndGet();
                        String m = String.valueOf(e.getMessage());
                        if (m.length() > 60) m = m.substring(0, 60);
                        errs.merge(m, 1, Integer::sum);
                    } finally {
                        latch.countDown();
                    }
                });
            }
            try {
                latch.await(180, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {}
            pool.shutdownNow();
            long dt = System.currentTimeMillis() - t0;
            StringBuilder sb = new StringBuilder();
            sb.append("hasil: ").append(ok.get()).append(" sukses, ")
              .append(fail.get()).append(" gagal, ").append(dt).append("ms");
            if (isIpEcho) sb.append(", ").append(cnts.size()).append(" IP unik");
            log(sb.toString());
            for (Map.Entry<String, Integer> e : cnts.entrySet())
                log("  " + e.getKey() + " x" + e.getValue());
            for (Map.Entry<String, Integer> e : errs.entrySet())
                log("  err: " + e.getKey() + " x" + e.getValue());
            final int nOk = ok.get(), nUniq = cnts.size();
            ui.post(() -> {
                verifyView.setText(isIpEcho
                    ? "verifikasi: " + nUniq + " IP unik dari " + nOk + " request"
                    : "verifikasi: " + nOk + "/" + n + " sukses");
                testResult.setText(sb.toString());
            });
        }).start();
    }

    // ---------- Monitor ----------
    private long readVmRssKb() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/status"))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("VmRSS:"))
                    return Long.parseLong(line.trim().split("\\s+")[1]);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    private long readProcTicks() {
        try (BufferedReader br = new BufferedReader(new FileReader("/proc/self/stat"))) {
            String s = br.readLine();
            int end = s.lastIndexOf(')');
            String[] p = s.substring(end + 1).trim().split("\\s+");
            return Long.parseLong(p[11]) + Long.parseLong(p[12]);
        } catch (Exception ignored) {}
        return -1;
    }

    private long dirSize(File d) {
        long s = 0;
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs)
            s += f.isDirectory() ? dirSize(f) : f.length();
        return s;
    }

    private void startMonitor() {
        monitorOn = true;
        new Thread(() -> {
            long prevTicks = readProcTicks();
            long prevTime = System.currentTimeMillis();
            while (monitorOn) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                long curTicks = readProcTicks();
                long curTime = System.currentTimeMillis();
                String cpuStr = "-";
                if (prevTicks >= 0 && curTicks >= 0 && curTime > prevTime) {
                    double secs = (curTime - prevTime) / 1000.0;
                    cpuStr = String.format("%.1f%%",
                            100.0 * (curTicks - prevTicks) / 100.0 / secs);
                }
                prevTicks = curTicks;
                prevTime = curTime;
                long rssKb = readVmRssKb();
                String ramStr = rssKb >= 0 ? (rssKb / 1024) + " MB" : "-";
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long totalMb = mi.totalMem / 1048576;
                long availMb = mi.availMem / 1048576;
                long cacheKb = dirSize(getCacheDir()) / 1024;
                final String fRam = ramStr, fCpu = cpuStr;
                final String fSys = "sistem " + (totalMb - availMb) + "/" + totalMb + " MB";
                final String fCache = "cache: " + cacheKb + " KB";
                final String fSesi = "sesi aktif: " + Mobile.sessionCount();
                updateHeader(fRam, fCpu);
                ui.post(() -> {
                    monRam.setText("RAM app: " + fRam + " (" + fSys + ")");
                    monCpu.setText("CPU app: " + fCpu);
                    monCache.setText(fCache);
                    monSesi.setText(fSesi);
                });
            }
        }).start();
    }

    // ---------- Start/Stop ----------
    private int buildConfs(File dir) throws Exception {
        if (dir.exists()) {
            File[] fs = dir.listFiles();
            if (fs != null) for (File f : fs) f.delete();
        } else dir.mkdirs();
        int total = 0, ci = 0;
        for (Profile pr : profiles) {
            byte[] base = java.nio.file.Files.readAllBytes(pr.file.toPath());
            for (int j = 0; j < pr.count; j++) {
                File out = new File(dir, "c" + ci + "_" + j + ".conf");
                try (OutputStream o = new FileOutputStream(out)) { o.write(base); }
                total++;
            }
            ci++;
        }
        log("confs: " + total + " file");
        return total;
    }

    private void doStart() {
        setEngineState(ST_STARTING);
        new Thread(() -> {
            try {
                log("=== START ===");
                Mobile.setTempDir(getCacheDir().getAbsolutePath());
                File confDir = new File(getFilesDir(), "confs");
                int total = buildConfs(confDir);
                if (total == 0) {
                    log("set jumlah sesi dulu");
                    setEngineState(ST_IDLE);
                    return;
                }
                log("start " + total + " sesi...");
                String err = Mobile.start(confDir.getAbsolutePath(),
                        "127.0.0.1:1080", "127.0.0.1:8080");
                if (err != null && !err.isEmpty()) {
                    log("start gagal: " + err);
                    setEngineState(ST_IDLE);
                } else {
                    log("running, sesi=" + Mobile.sessionCount());
                    ui.post(() -> rebuildProxyTable());
                    setEngineState(ST_RUNNING);
                    startMonitor();
                }
            } catch (Exception e) {
                log("exception: " + e);
                setEngineState(ST_IDLE);
            }
        }).start();
    }

    private void doStop() {
        setEngineState(ST_STOPPING);
        log("stopping...");
        new Thread(() -> {
            monitorOn = false;
            Mobile.stop();
            log("stopped, running=" + Mobile.isRunning());
            ui.post(() -> {
                verifyView.setText("verifikasi: belum dites");
                rebuildProxyTable();
                updateHeader("-", "-");
            });
            setEngineState(ST_IDLE);
        }).start();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                File dir = getExternalFilesDir(null);
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs();
                    try (java.io.PrintWriter pw =
                            new java.io.PrintWriter(new File(dir, "crash.log"))) {
                        pw.println("time: " + new java.util.Date());
                        e.printStackTrace(pw);
                    }
                }
            } catch (Exception ignored) {}
            android.os.Process.killProcess(android.os.Process.myPid());
        });
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        headerStats = findViewById(R.id.headerStats);
        statusBar = findViewById(R.id.statusBar);
        logView = findViewById(R.id.logView);
        totalView = findViewById(R.id.totalView);
        verifyView = findViewById(R.id.verifyView);
        testResult = findViewById(R.id.testResult);
        monRam = findViewById(R.id.monRam);
        monCpu = findViewById(R.id.monCpu);
        monCache = findViewById(R.id.monCache);
        monSesi = findViewById(R.id.monSesi);
        configContainer = findViewById(R.id.configList);
        proxyTable = findViewById(R.id.proxyTable);
        logFilter = findViewById(R.id.logFilter);
        logLevel = findViewById(R.id.logLevel);
        ((TextView) findViewById(R.id.settingVer)).setText("wpmulti-test v22");

        // sidebar
        findViewById(R.id.btnMenu).setOnClickListener(v -> openSidebar());
        findViewById(R.id.scrim).setOnClickListener(v -> closeSidebar());
        findViewById(R.id.menuConfig).setOnClickListener(v -> showPage(0));
        findViewById(R.id.menuProxy).setOnClickListener(v -> {
            rebuildProxyTable();
            showPage(1);
        });
        findViewById(R.id.menuMonitor).setOnClickListener(v -> showPage(2));
        findViewById(R.id.menuLog).setOnClickListener(v -> showPage(3));
        findViewById(R.id.menuSetting).setOnClickListener(v -> showPage(4));
        showPage(0);

        // log level spinner
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Semua", "Error"});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        logLevel.setAdapter(ad);
        logLevel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                logLevelSel = (String) p.getItemAtPosition(pos);
                renderLog();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        logFilter.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            public void onTextChanged(CharSequence s, int a, int b, int c) {}
            public void afterTextChanged(Editable s) {
                filterText = s.toString();
                renderLog();
            }
        });
        findViewById(R.id.clearLogBtn).setOnClickListener(v -> {
            logLines.clear();
            renderLog();
        });
        findViewById(R.id.copyLogBtn).setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "wpmulti-log", logView.getText().toString()));
            log("log disalin ke clipboard");
        });

        // config
        loadProfiles();
        rebuildConfigRows();
        findViewById(R.id.addBtn).setOnClickListener(v -> {
            Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            it.addCategory(Intent.CATEGORY_OPENABLE);
            it.setType("*/*");
            startActivityForResult(it, PICK_CONF);
        });

        // engine toggle
        findViewById(R.id.btnEngine).setOnClickListener(v -> {
            if (engineState == ST_IDLE) doStart();
            else if (engineState == ST_RUNNING) doStop();
        });
        setEngineState(ST_IDLE);

        // proxy test
        findViewById(R.id.test1Btn).setOnClickListener(v -> testIp(1));
        findViewById(R.id.test20Btn).setOnClickListener(v -> testIp(20));

        // setting
        findViewById(R.id.clearCacheBtn).setOnClickListener(v -> new Thread(() -> {
            deleteDir(getCacheDir());
            log("cache dihapus");
        }).start());

        Mobile.setStatusListener(new StatusListener() {
            @Override public void onSessionUp(long up, long total) {
                long step = Math.max(1, total / 20);
                if (up == total || up % step == 0) log("sesi " + up + "/" + total);
            }
            @Override public void onReady(long total) {
                log("READY: " + total + " sesi");
            }
            @Override public void onError(String message) {
                log("ERROR: " + message);
            }
        });

        rebuildProxyTable();
        updateHeader("-", "-");
    }

    private void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) deleteDir(f);
            f.delete();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == PICK_CONF && res == RESULT_OK && data != null) {
            Uri uri = data.getData();
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                String name = "profile_" + System.currentTimeMillis() + ".conf";
                File out = new File(profilesDir(), name);
                try (OutputStream o = new FileOutputStream(out)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
                }
                profiles.add(new Profile(name, out));
                rebuildConfigRows();
                log("profile ditambah: " + name);
            } catch (Exception e) {
                log("gagal upload profile: " + e.getMessage());
            }
        }
    }

    @Override
    public void onBackPressed() {
        View sidebar = findViewById(R.id.sidebar);
        if (sidebar.getVisibility() == View.VISIBLE) {
            closeSidebar();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        monitorOn = false;
        super.onDestroy();
    }
}
