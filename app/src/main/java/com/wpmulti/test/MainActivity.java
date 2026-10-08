package com.wpmulti.test;

import androidx.appcompat.app.AppCompatActivity;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.content.SharedPreferences;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

// TAHAP 1: TIDAK ADA lagi import mobile.Mobile / mobile.StatusListener di
// sini — runtime gomobile hanya hidup di proses :goengine; semua kontrol
// lewat EngineClient (AIDL IEngineControl).
public class MainActivity extends AppCompatActivity {
    private static final int MAX_PER_CONFIG = 240;
    private static final int MAX_PROFILES = 5;
    private static final int PICK_CONF = 1001;
    private static final int VPN_REQUEST = 2001;

    // engine states
    private static final int ST_IDLE = 0;
    private static final int ST_STARTING = 1;
    private static final int ST_RUNNING = 2;
    private static final int ST_STOPPING = 3;
    private volatile int engineState = ST_IDLE;

    // M1: watchdog START. Mobile.start() memblokir sampai semua sesi siap
    // (terukur ~2 dtk untuk 100 sesi, ~8 dtk untuk 1200 sesi). Timeout 60 dtk
    // = 7,5x kasus terukur paling lambat; jika terlampaui, UI dikembalikan
    // ke IDLE agar tidak stuck di "MEMULAI..." selamanya.
    private static final long START_TIMEOUT_MS = 60_000;
    // C2/M5: cegah dua percobaan start bersamaan di sisi Java. (Di sisi Go,
    // start ganda sudah gagal aman via guard "engine sudah berjalan" +
    // bind port, tapi percobaan ganda tetap membuang waktu spin-up sesi.)
    private final AtomicBoolean startBusy = new AtomicBoolean(false);
    // M1: menandai attempt start yang masih valid; watchdog menaikannya
    // saat timeout agar hasil worker yang telat tidak menimpa state baru.
    private volatile int startEpoch = 0;
    // FIX "macet di MEMULAI": poll sinkronisasi selama STARTING (lihat
    // beginStartSync). Runnable-nya disimpan agar bisa dibatalkan.
    private Runnable startSync;

    private TextView headerStats;
    private TextView statusBar;
    // Glitchcore cy3: HUD notifikasi in-app (pengganti Toast sistem yang
    // tampil sbg box abu-abu gelap di atas pill nav).
    private TextView hudToast;
    // cy7: guard generasi hide HUD - HUD yang muncul lagi sebelum hide
    // selesai tidak ikut GONE (race glitchDisappear vs re-show).
    private int hudGen = 0;
    private TextView logView;
    private TextView totalView;
    private TextView verifyView;
    private TextView testResult;
    private TextView monRam, monCpu, monCache, monSesi, monGo;
    // Fase 3: throughput = angka besar + total per arah; monSesiDetail
    // kini CONTAINER chip sesi (LinearLayout), bukan TextView monospace.
    private TextView monRx, monTx, monRxTotal, monTxTotal;
    private LinearLayout monSesiDetail;
    private TrafficGraphView trafficGraph;
    // total sesi sebelumnya (untuk hitung rate per detik)
    private long prevSessTx = -1, prevSessRx = -1, prevSessT = 0;
    // Fase 3 (revisi review): chip sesi di-update IN-PLACE (tanpa inflate
    // ulang per tick) dan status expand per sesi dipersist di expandedSess
    // - dulu rebuild tiap 2 dtk membuat detail yang di-expand kolap
    // sendiri. prevSessAct = total tx+rx tick lalu, untuk mendeteksi
    // sesi yang aktif mengirim/menerima.
    private final java.util.Map<Integer, View> chipViews = new java.util.HashMap<>();
    private final java.util.Set<Integer> expandedSess = new java.util.HashSet<>();
    private final java.util.Map<Integer, Long> prevSessAct = new java.util.HashMap<>();

    private TextView vpnStatusView, vpnStatsView;
    private TextView proxyStatusView; // label "Proxy SOCKS5/HTTP: aktif/tidak aktif"
    private MaterialButton vpnToggleBtn;
    private android.widget.Spinner vpnDnsMode;
    private EditText vpnDnsServer;
    private android.widget.CheckBox vpnAutoReconnect;
    private BroadcastReceiver vpnDropReceiver;
    private android.widget.Spinner vpnAppMode;
    private android.widget.Spinner vpnIpMode;
    private android.widget.TextView vpnAppCount;
    private android.widget.TextView vpnIpModeAppCount;
    private Intent pendingVpnIntent;
    private BroadcastReceiver vpnReceiver;
    private LinearLayout configContainer;
    private LinearLayout proxyTable;
    private EditText logFilter;
    private Spinner logLevel;
    // Fase 4: elemen hidup - LoadingIndicator transisi, denyut dot
    // status RUNNING, morph sudut tombol START<->STOP, bounce spring.
    // cy9: penggerak = SelfAnim (Choreographer sendiri), bukan
    // ValueAnimator/SpringAnimation sistem -> tetap jalan walau skala
    // animator sistem = 0; keputusan efek = GlitchText.isGlitchEnabled().
    private View heroLoading;
    private SelfAnim.Token dotPulse;
    private SelfAnim.Token morphAnim, bounceAnim;
    // guard bounce: dulu bounce terpicu walau state tidak berubah
    // (setEngineState redundan dari rebuildConfigRows dll) - sekarang
    // fx hanya jalan saat state benar-benar berubah.
    private int lastFxState = -1;
    // morph: fallback diam + log sekali saja bila background tombol
    // ternyata bukan MaterialShapeDrawable (fitur tidak boleh crash).
    private boolean msdWarned = false;
    // bounce: pola sama dengan morph - fitur hidup tidak boleh crash.
    private boolean bounceWarned = false;
    private final List<Profile> profiles = new ArrayList<>();
    private final List<EditText> countFields = new ArrayList<>();
    private final List<View> configRows = new ArrayList<>();
    private final List<LogEntry> logLines = new ArrayList<>();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean monitorOn = false;
    private String logLevelSel = "Semua";
    private String filterText = "";

    // level log ala Android Logcat
    private static final int LV_VERBOSE = 2;
    private static final int LV_DEBUG = 3;
    private static final int LV_INFO = 4;
    private static final int LV_WARN = 5;
    private static final int LV_ERROR = 6;

    static class LogEntry {
        int level;
        String text;
        LogEntry(int l, String t) { level = l; text = t; }
    }

    static class Profile {
        String name;
        File file;
        int count = 10;
        Profile(String n, File f) { name = n; file = f; }
    }

    private void log(String s) {
        log(LV_INFO, s);
    }

    private void log(int level, String s) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss",
                java.util.Locale.US).format(new java.util.Date());
        final LogEntry e = new LogEntry(level, "[" + ts + "] " + s);
        ui.post(() -> {
            logLines.add(e);
            // Cap memori: 2000 baris cukup untuk diagnostik; baris terlama
            // dibuang. Tanpa ini logLines tumbuh tanpa batas selama app hidup
            // (leak memori perlahan, terlihat di sesi lama dengan test 20x).
            while (logLines.size() > 2000) logLines.remove(0);
            scheduleLogRender();
        });
    }

    // Coalescing render: renderLog() rebuild StringBuilder dari SEMUA baris
    // (hingga 2000). Tanpa coalescing, burst log (test 20x = 40+ baris,
    // start 1200 sesi = 20+ progress) memicu O(n) rebuild per baris ->
    // O(n²) di UI thread. Satu render per putaran message loop cukup.
    private boolean logRenderPending = false;

    private void scheduleLogRender() {
        if (logRenderPending) return;
        logRenderPending = true;
        ui.post(() -> {
            logRenderPending = false;
            if (currentPage == R.id.pageLog) renderLog(); // hanya saat halaman Log terlihat
        });
    }

    private int levelThreshold() {
        switch (logLevelSel) {
            case "Verbose": return LV_VERBOSE;
            case "Debug": return LV_DEBUG;
            case "Info": return LV_INFO;
            case "Warn": return LV_WARN;
            case "Error": return LV_ERROR;
            default: return LV_VERBOSE; // "Semua"
        }
    }

    private void renderLog() {
        int thr = levelThreshold();
        StringBuilder sb = new StringBuilder();
        for (LogEntry e : logLines) {
            if (e.level < thr) continue;
            if (!filterText.isEmpty() && !e.text.contains(filterText)) continue;
            sb.append(e.text).append('\n');
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
        // Chokepoint: loop sinkronisasi start hanya relevan saat STARTING;
        // state lain (sukses/gagal/timeout/stop) otomatis membatalkannya.
        if (st != ST_STARTING) stopStartSync();
        ui.post(() -> {
            MaterialButton btn = findViewById(R.id.btnEngine);
            switch (st) {
                case ST_IDLE:
                    btn.setText("START");
                    btn.setIconResource(R.drawable.ic_play);
                    btn.setEnabled(true);
                    statusBar.setText("BERHENTI");
                    statusBar.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_dot_red, 0, 0, 0);
                    glitchFlash(statusBar, getColor(R.color.status_red));
                    break;
                case ST_STARTING:
                    btn.setText("MEMULAI...");
                    btn.setIconResource(0);
                    btn.setEnabled(false);
                    statusBar.setText("MEMULAI...");
                    statusBar.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_dot_amber, 0, 0, 0);
                    glitchFlash(statusBar, getColor(R.color.status_amber));
                    break;
                case ST_RUNNING:
                    btn.setText("STOP");
                    btn.setIconResource(R.drawable.ic_stop);
                    btn.setEnabled(true);
                    // FIX label kontradiktif: jumlah sesi dari snapshot
                    // EngineClient (GET_STATUS), bukan Mobile.sessionCount()
                    // (runtime Go tidak ada lagi di proses utama).
                    statusBar.setText("BERJALAN \u2014 "
                            + EngineClient.get().snapshot().sessions + " sesi");
                    statusBar.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_dot_green, 0, 0, 0);
                    glitchFlash(statusBar, getColor(R.color.status_green));
                    break;
                case ST_STOPPING:
                    btn.setText("MENGHENTIKAN...");
                    btn.setIconResource(0);
                    btn.setEnabled(false);
                    statusBar.setText("MENGHENTIKAN...");
                    statusBar.setCompoundDrawablesRelativeWithIntrinsicBounds(
                            R.drawable.ic_dot_amber, 0, 0, 0);
                    glitchFlash(statusBar, getColor(R.color.status_amber));
                    break;
            }
            boolean cfg = (st == ST_IDLE);
            updateAddBtn();
            for (View row : configRows) {
                for (int id : new int[]{R.id.minus, R.id.plus, R.id.max,
                        R.id.del, R.id.count}) {
                    View c = row.findViewById(id);
                    // cy8: kontrol config lock/unlock = HANYA kontrol yang
                    // state-nya benar-benar berubah yang korupsi/rekonstruksi.
                    if (c.isEnabled() != cfg)
                        GlitchText.glitchStateChange(c, cfg);
                    c.setEnabled(cfg);
                }
            }
            boolean tst = (st == ST_RUNNING);
            View t1 = findViewById(R.id.test1Btn);
            View t20 = findViewById(R.id.test20Btn);
            if (t1.isEnabled() != tst) {
                GlitchText.glitchStateChange(t1, tst);
                GlitchText.glitchStateChange(t20, tst);
            }
            t1.setEnabled(tst);
            t20.setEnabled(tst);
            // Fase 4: elemen hidup - loading transisi, denyut dot, morph
            // & bounce (morph/bounce hanya saat state benar2 berubah).
            boolean fxChanged = (st != lastFxState);
            lastFxState = st;
            // cy6: loading indicator muncul/hilang KARENA GLITCH (bukan
            // toggle visibility kasar). Sembunyi ditunda 110ms agar flicker
            // "de-rez" terlihat; guard state mencegah GONE salah waktu.
            if (st == ST_STARTING || st == ST_STOPPING) {
                heroLoading.setVisibility(View.VISIBLE);
                GlitchText.glitchAppear(heroLoading);
            } else if (heroLoading.getVisibility() == View.VISIBLE) {
                GlitchText.glitchDisappear(heroLoading);
                heroLoading.postDelayed(() -> {
                    if (engineState != ST_STARTING
                            && engineState != ST_STOPPING) {
                        heroLoading.setVisibility(View.GONE);
                    } else {
                        // cy6: guard gagal (state balik STARTING) - pulihkan
                        // alpha agar elemen tidak tertinggal transparan.
                        heroLoading.setAlpha(1f);
                    }
                }, 110);
            }
            updateDotPulse(st);
            if (fxChanged) {
                morphEngineButton(btn, st);
                bounceEngine(btn);
                // cy6: perubahan state engine = glitch MAJOR di kartu hero
                // (statusBar/monGo/btnEngine ikut "kehilangan sinkron").
                View hero = btn.getParent() instanceof View
                        ? (View) btn.getParent() : null;
                if (hero != null) GlitchText.glitchMajor(hero);
            }
            updateVpnUi();
        });
    }

    // ---------- Fase 4: elemen hidup ----------
    // Denyut dot status saat engine berjalan: alpha compound drawable
    // status bar 255 <-> ~90, 900ms bolak-balik. Dibatalkan di state
    // lain dan di onPause (hemat CPU/baterai; dulu jalan terus walau
    // activity tidak terlihat); dinyalakan lagi di onResume.
    // cy9: penggerak = SelfAnim.pulse (Choreographer sendiri) dengan
    // kurva & nilai IDENTIK ValueAnimator.ofFloat(1f,0.35f) 900ms
    // REVERSE INFINITE lama - jalan walau animator scale sistem = 0;
    // keputusan aktif = GlitchText.isGlitchEnabled() (satu pusat).
    private void updateDotPulse(int st) {
        if (st != ST_RUNNING || !GlitchText.isGlitchEnabled()) {
            stopDotPulse();
            return;
        }
        if (dotPulse != null) return; // sudah berdenyut
        android.graphics.drawable.Drawable[] ca =
                statusBar.getCompoundDrawablesRelative();
        if (ca == null || ca.length < 1 || ca[0] == null) return;
        final android.graphics.drawable.Drawable dot = ca[0].mutate();
        // fraksi arah p: v = 1 - 0.65*ease(p) == interpolasi 1f -> 0.35f
        // (AccelerateDecelerate) yang dibalik tiap setengah-periode.
        dotPulse = SelfAnim.pulse(900, p -> dot.setAlpha(Math.round(
                (1f - 0.65f * SelfAnim.ease(p)) * 255f)));
    }

    private void stopDotPulse() {
        if (dotPulse != null) {
            dotPulse.cancel();
            dotPulse = null;
            // pastikan dot kembali penuh (alpha bisa tertinggal rendah)
            android.graphics.drawable.Drawable[] ca =
                    statusBar.getCompoundDrawablesRelative();
            if (ca != null && ca.length > 0 && ca[0] != null)
                ca[0].mutate().setAlpha(255);
        }
    }

    // Morph sudut tombol START<->STOP via MaterialShapeDrawable (lapisan
    // 0 dari RippleDrawable bawaan MaterialButton): START = sudut tegas
    // token large (8dp), STOP = pill (tinggi aktual / 2). cy9: penggerak
    // = SelfAnim.ofDuration (Choreographer sendiri), kurva
    // AccelerateDecelerate & 260ms IDENTIK ValueAnimator lama - jalan
    // walau animator scale sistem = 0; fallback diam + log sekali bila
    // background bukan MaterialShapeDrawable - fitur dekoratif tidak
    // boleh crash.
    private void morphEngineButton(final MaterialButton btn, int st) {
        if (!GlitchText.isGlitchEnabled()) return;
        try {
            android.graphics.drawable.Drawable bg = btn.getBackground();
            MaterialShapeDrawable msd = null;
            if (bg instanceof MaterialShapeDrawable) {
                msd = (MaterialShapeDrawable) bg;
            } else if (bg instanceof android.graphics.drawable.RippleDrawable) {
                android.graphics.drawable.Drawable c =
                        ((android.graphics.drawable.RippleDrawable) bg)
                                .getDrawable(0);
                if (c instanceof MaterialShapeDrawable) {
                    msd = (MaterialShapeDrawable) c;
                }
            }
            if (msd == null) return;
            int hgt = btn.getHeight();
            if (hgt <= 0) {
                hgt = (int) (56 * getResources()
                        .getDisplayMetrics().density);
            }
            float from = msd.getShapeAppearanceModel()
                    .getTopRightCornerSize().getCornerSize(
                            new android.graphics.RectF(0, 0,
                                    btn.getWidth(), hgt));
            float target = st == ST_RUNNING
                    ? hgt / 2f
                    : 8 * getResources().getDisplayMetrics().density;
            // lambda butuh effectively-final (msd di-reassign di atas)
            final MaterialShapeDrawable msdF = msd;
            if (morphAnim != null) morphAnim.cancel(); // morph baru ganti lama
            morphAnim = SelfAnim.ofDuration(260, t -> {
                float r = from + (target - from) * SelfAnim.ease(t);
                msdF.setShapeAppearanceModel(
                        ShapeAppearanceModel.builder()
                                .setAllCornerSizes(r)
                                .build());
            });
        } catch (Exception e) {
            if (!msdWarned) {
                msdWarned = true;
                log(LV_DEBUG, "morph tombol dilewati: " + e.getMessage());
            }
        }
    }

    // Bounce spring halus saat tombol kembali aktif (state berubah).
    // Dipicu hanya dari jalur fxChanged. cy9: penggerak = SelfAnim.spring
    // (Choreographer sendiri) - parameter IDENTIK SpringAnimation lama
    // (stiffness 200 = STIFFNESS_LOW, dampingRatio 0.5 =
    // DAMPING_RATIO_MEDIUM_BOUNCY, 0.92 -> 1.0, minVisible 1/500 utk
    // scale), berakhir PASTI di 1.0 - jalan walau animator scale = 0.
    // FIX crash "Final position of the spring cannot be greater than the
    // max value": kini tidak relevan (tidak ada SpringForce sistem),
    // try/catch meniru morph (fitur hidup tidak boleh crash).
    private void bounceEngine(final MaterialButton btn) {
        if (!GlitchText.isGlitchEnabled()) return;
        try {
            if (bounceAnim != null) bounceAnim.cancel();
            bounceAnim = SelfAnim.spring(0.92f, 1f, 200f, 0.5f,
                    1f / 500f, v -> {
                        btn.setScaleX(v);
                        btn.setScaleY(v);
                    });
        } catch (Exception e) {
            if (!bounceWarned) {
                bounceWarned = true;
                log(LV_DEBUG, "bounce dilewati: " + e.getMessage());
            }
        }
    }

    // ---------- Pages ----------
    private int currentPage = R.id.pageHome;
    // cy7: showPage pertama (onCreate) tanpa transisi; flag anti-transisi
    // palsu saat XML masih semua VISIBLE.
    private boolean pageShownOnce = false;

    // Skala animator global (Setelan developer > skala animasi; 0 =
    // "hapus animasi"): HANYA dipakai mode efek AUTO ("ikuti sistem")
    // via GlitchText.setAnimScale - mode Selalu aktif / Mati tidak
    // membacanya. Fallback 1f bila key tidak tersedia.
    private float animScale() {
        // cy7: skala EFEKTIF = minimum dari TIGA skala animasi sistem -
        // "Remove animations" (aksesibilitas) menset semuanya 0; window/
        // popup animation (dialog/dropdown) diatur WINDOW/TRANSITION
        // scale, bukan ANIMATOR - jadi ketiganya wajib dicek.
        try {
            float s = android.provider.Settings.Global.getFloat(
                    getContentResolver(),
                    android.provider.Settings.Global.ANIMATOR_DURATION_SCALE,
                    1f);
            float t = android.provider.Settings.Global.getFloat(
                    getContentResolver(),
                    android.provider.Settings.Global.TRANSITION_ANIMATION_SCALE,
                    1f);
            float w = android.provider.Settings.Global.getFloat(
                    getContentResolver(),
                    android.provider.Settings.Global.WINDOW_ANIMATION_SCALE,
                    1f);
            return Math.min(s, Math.min(t, w));
        } catch (Exception e) {
            return 1f;
        }
    }

    // Overhaul UI 1/7: navigasi by-ID halaman (pageHome/pageSesi/pageLog/
    // pageSetting) — pengganti showPage(int idx) era navigasi drawer.
    // cy7: page transition "A terkorosi -> B direkonstruksi" per-fragment
    // (GlitchText.pageTransition) - BUKAN slide/fade, BUKAN shake. Bila
    // animator scale 0: langsung final tanpa efek (aksesibilitas).
    private void showPage(int pageId) {
        currentPage = pageId;
        int[] pages = {R.id.pageHome, R.id.pageSesi, R.id.pageLog,
                R.id.pageSetting};
        View oldShown = null, shown = null;
        if (pageShownOnce) {
            for (int id : pages) {
                View v = findViewById(id);
                if (v.getVisibility() == View.VISIBLE && id != pageId)
                    oldShown = v;
            }
        }
        pageShownOnce = true;
        for (int id : pages) {
            View v = findViewById(id);
            if (id == pageId) {
                v.setVisibility(View.VISIBLE);
                shown = v;
            } else if (v != oldShown) {
                // cy7: halaman yang tidak terlibat transisi langsung GONE;
                // oldShown TETAP VISIBLE sampai pageTransition menyembunyikannya
                // setelah fase korupsi (atau langsung GONE bila fx mati) -
                // anti-race fast-switch: setiap showPage menyembunyikan
                // halaman basi yang tidak sedang dianimasikan.
                v.setVisibility(View.GONE);
            }
        }
        GlitchText.pageTransition(oldShown, shown);
        if (pageId == R.id.pageLog) renderLog(); // flush log yang tertunda saat masuk halaman Log
    }

    // cy7: id item nav terakhir aktif - untuk glitch PER-ITEM navbar.
    private int lastNavItemId = -1;

    /**
     * cy7: perubahan active state navbar = glitch pada ITEM yang berubah,
     * masing-masing (item lama korupsi MINOR, item baru rekonstruksi
     * MEDIUM). Pill background TIDAK disentuh - tanpa alpha flicker pada
     * view ber-elevation (artifact kotak = layer clipping shadow, lihat
     * docs GlitchText.glitchView).
     */
    private void glitchNavItems(BottomNavigationView bnv,
            int oldId, int newId) {
        if (oldId == -1 || oldId == newId) return;
        if (!(bnv.getChildAt(0) instanceof android.view.ViewGroup)) return;
        android.view.ViewGroup menuView =
                (android.view.ViewGroup) bnv.getChildAt(0);
        android.view.Menu m = bnv.getMenu();
        int oi = -1, ni = -1;
        for (int i = 0; i < m.size(); i++) {
            int iid = m.getItem(i).getItemId();
            if (iid == oldId) oi = i;
            if (iid == newId) ni = i;
        }
        // cy8: label item nav di-inflate LAZY oleh BNV (setelah onCreate,
        // setelah registerTree(rootMain) dijalankan) -> daftarkan di sini
        // agar span korupsi benar-benar menyala (dedup idempotent di
        // dalam walk, murah dipanggil berulang).
        GlitchText.registerTree(menuView);
        if (oi >= 0 && oi < menuView.getChildCount()) {
            View it = menuView.getChildAt(oi);
            GlitchText.glitchTree(it, GlitchText.MINOR);
            GlitchText.glitchJitter(it, GlitchText.MINOR);
        }
        if (ni >= 0 && ni < menuView.getChildCount()) {
            View it = menuView.getChildAt(ni);
            GlitchText.glitchTree(it, GlitchText.MEDIUM);
            GlitchText.glitchJitter(it, GlitchText.MINOR);
        }
    }

    // cy8: setText hanya bila isi benar-benar berubah - label yang
    // di-refresh tiap tick (updateVpnUi) tidak memicu watcher glitch
    // saat teksnya identik (tidak berubah = tidak ada glitch).
    private static void setTextIfChanged(TextView tv, CharSequence s) {
        if (tv == null || TextUtils.equals(tv.getText(), s)) return;
        tv.setText(s);
    }

    // ---------- VPN ----------
    private void updateVpnUi() {
        ui.post(() -> {
            boolean vpnOn = VpnEngine.running;
            boolean engOn = engineState == ST_RUNNING;
            // SATU sumber kebenaran: EngineStatus dari GET_STATUS (binder).
            EngineStatus es = EngineClient.get().snapshot();
            boolean proxyOn = es.running;
            if (proxyStatusView != null) {
                setTextIfChanged(proxyStatusView, "Proxy SOCKS5/HTTP: "
                        + (proxyOn
                            ? "aktif (SOCKS5 127.0.0.1:1080 \u00b7 HTTP 127.0.0.1:8080)"
                            : "tidak aktif"));
                proxyStatusView.setTextColor(getColor(proxyOn
                        ? R.color.status_green : R.color.status_coral));
            }
            // VPN (TUN) adalah subsistem TERPISAH dari proxy: bisa mati
            // sementara proxy aktif. Label dibuat eksplisit agar tidak
            // terbaca kontradiktif dengan header BERJALAN.
            setTextIfChanged(vpnStatusView, "VPN (TUN): "
                    + (vpnOn ? "dipakai \u2014 trafik dirutekan lewat tunnel"
                            : "tidak dipakai"));
            vpnStatusView.setTextColor(getColor(vpnOn
                    ? R.color.status_green : R.color.status_gray));
            if (vpnOn) {
                vpnStatsView.setText("koneksi TCP: " + VpnEngine.connCount()
                        + "\nRX: " + fmtBytes(VpnEngine.bytesRx())
                        + " | TX: " + fmtBytes(VpnEngine.bytesTx()));
                // cy6: stats muncul KARENA GLITCH hanya saat transisi
                // GONE -> VISIBLE (updateVpnUi dipanggil tiap tick monitor;
                // tanpa guard, glitch terpicu berulang tiap 2 dtk).
                if (vpnStatsView.getVisibility() != View.VISIBLE) {
                    vpnStatsView.setVisibility(View.VISIBLE);
                    GlitchText.glitchAppear(vpnStatsView);
                }
            } else {
                vpnStatsView.setText("");
                // cy6: hilang KARENA GLITCH - de-rez dulu 110ms, baru GONE
                // (guard VpnEngine.running mencegah GONE salah waktu).
                if (vpnStatsView.getVisibility() == View.VISIBLE) {
                    GlitchText.glitchDisappear(vpnStatsView);
                    vpnStatsView.postDelayed(() -> {
                        if (!VpnEngine.running) {
                            vpnStatsView.setVisibility(View.GONE);
                        } else {
                            // cy6: guard gagal (VPN nyambung lagi <110ms) -
                            // pulihkan alpha agar tidak transparan permanen.
                            vpnStatsView.setAlpha(1f);
                        }
                    }, 110);
                }
            }
            setTextIfChanged(vpnToggleBtn, vpnOn ? "DISCONNECT VPN"
                    : "CONNECT VPN");
            vpnToggleBtn.setIconResource(vpnOn
                    ? R.drawable.ic_stop : R.drawable.ic_play);
            // boleh connect hanya saat engine running; disconnect selalu boleh
            boolean canVpn = vpnOn || engOn;
            // cy8: enabled-state tombol BERUBAH = tombol itu korupsi lalu
            // rekonstruksi ke state barunya (bukan sekadar alpha-nya di-set).
            if (vpnToggleBtn.isEnabled() != canVpn) {
                GlitchText.glitchStateChange(vpnToggleBtn, canVpn);
            }
            vpnToggleBtn.setEnabled(canVpn);
            vpnToggleBtn.setAlpha(canVpn ? 1f : 0.4f);
        });
    }

    private static String fmtBytes(long b) {
        if (b < 1024) return b + " B";
        if (b < 1048576) return String.format("%.1f KB", b / 1024.0);
        return String.format("%.1f MB", b / 1048576.0);
    }

    private String dnsModeKey() {
        switch (vpnDnsMode.getSelectedItemPosition()) {
            case 1: return "dot";
            case 2: return "doq";
            case 3: return "plain";
            default: return "doh";
        }
    }

    private void updateDnsHint() {
        int ps = vpnDnsMode.getSelectedItemPosition();
        CharSequence hint = ps == 0
                ? "URL atau IP, mis. https://cloudflare-dns.com/dns-query"
                : "IP server, mis. 1.1.1.1";
        // cy8: hint BERUBAH = field itu ter-gemetrek mikro (displacement
        // saja - isi text user & cursor tidak disentuh).
        if (!TextUtils.equals(vpnDnsServer.getHint(), hint)) {
            vpnDnsServer.setHint(hint);
            GlitchText.glitchJitter(vpnDnsServer, GlitchText.MINOR);
        }
    }

    // Mapping IP terkenal -> URL DoH kanonis (sertifikat valid).
    private String dohUrlForIp(String ip) {
        if (ip.equals("1.1.1.1") || ip.equals("1.0.0.1"))
            return "https://cloudflare-dns.com/dns-query";
        if (ip.equals("8.8.8.8") || ip.equals("8.8.4.4"))
            return "https://dns.google/dns-query";
        if (ip.equals("9.9.9.9") || ip.equals("149.112.112.112"))
            return "https://dns.quad9.net/dns-query";
        // Quad9: port 5053 adalah endpoint DoH lama; standar RFC 8484 = 443
        // (sertifikat & CDN mengharapkan 443).
        return "https://" + ip + "/dns-query";
    }

    // Validasi alamat IP literal (v4/v6). Hostname ditolak.
    private boolean validDns(String s) {
        s = s.trim();
        if (s.isEmpty()) return false;
        if (s.matches("\\d{1,3}(\\.\\d{1,3}){3}")) {
            for (String part : s.split("\\.")) {
                int v;
                try { v = Integer.parseInt(part); }
                catch (NumberFormatException e) { return false; }
                if (v < 0 || v > 255) return false;
            }
            return true;
        }
        if (s.contains(":")) {
            try {
                return java.net.InetAddress.getByName(s)
                        instanceof java.net.Inet6Address;
            } catch (Exception e) { return false; }
        }
        return false;
    }

    private void updateVpnAppCount() {
        android.content.SharedPreferences vp =
                getSharedPreferences("vpn", MODE_PRIVATE);
        int n = vp.getStringSet("vpn_apps",
                new java.util.HashSet<>()).size();
        final int count = n;
        ui.post(() -> vpnAppCount.setText(
                count == 0 ? "Belum ada aplikasi dipilih"
                        : count + " aplikasi dipilih"));
    }

    private String appIpModeLabel(String m) {
        return m.equals("v4") ? "IPv4" : m.equals("v6") ? "IPv6" : "Global";
    }

    private void updateVpnIpModeCount() {
        int n = 0;
        for (String k : getSharedPreferences("vpn", MODE_PRIVATE)
                .getAll().keySet()) {
            if (k.startsWith("vpn_app_ip_")) n++;
        }
        final int count = n;
        ui.post(() -> vpnIpModeAppCount.setText(
                count == 0 ? "Belum ada aplikasi diatur"
                        : count + " aplikasi diatur"));
    }

    // Semua aplikasi terinstal (user + sistem), urut alfabetis.
    // Dipakai dua dialog. Butuh QUERY_ALL_PACKAGES agar lengkap di
    // Android 11+ (query launcher-intent tidak mencakup aplikasi
    // sistem tanpa launcher activity).
    // Cache daftar aplikasi: loadLabel() untuk 300+ aplikasi lambat,
    // jadi dimuat sekali di background lalu dipakai ulang.
    // Dihangatkan saat aplikasi start.
    private static java.util.List<String[]> sAppCache = null;
    private static volatile boolean sAppCacheLoading = false;

    private void ensureAppCache(
            java.util.function.Consumer<java.util.List<String[]>> onReady) {
        java.util.List<String[]> cached = sAppCache;
        if (cached != null) { onReady.accept(cached); return; }
        android.app.AlertDialog loading =
                new android.app.AlertDialog.Builder(this)
                        .setMessage("Memuat daftar aplikasi...")
                        .setCancelable(false)
                        .show();
        synchronized (MainActivity.class) {
            if (!sAppCacheLoading) {
                sAppCacheLoading = true;
                new Thread(() -> {
                    try {
                        sAppCache = loadInstalledApps();
                    } catch (Exception ignored) {
                        // GAGAL -> list kosong, BUKAN null: null membuat
                        // polling 300ms di bawah berjalan selamanya + dialog
                        // "Memuat" tak pernah tertutup (macet).
                        sAppCache = new java.util.ArrayList<>();
                    }
                    sAppCacheLoading = false;
                }).start();
            }
        }
        final long t0 = android.os.SystemClock.uptimeMillis();
        Runnable poll = new Runnable() {
            public void run() {
                java.util.List<String[]> c = sAppCache;
                if (c != null) {
                    try { loading.dismiss(); } catch (Exception ignored) {}
                    onReady.accept(c);
                } else if (android.os.SystemClock.uptimeMillis() - t0 > 15_000) {
                    // Timeout: jangan biarkan dialog + polling menggantung
                    try { loading.dismiss(); } catch (Exception ignored) {}
                    onReady.accept(new java.util.ArrayList<>());
                } else {
                    ui.postDelayed(this, 300);
                }
            }
        };
        ui.post(poll);
    }

    private java.util.List<String[]> loadInstalledApps() {
        android.content.pm.PackageManager pm = getPackageManager();
        java.util.List<android.content.pm.ApplicationInfo> ais =
                pm.getInstalledApplications(0);
        java.util.Collections.sort(ais, (a, b2) -> {
            String la = String.valueOf(
                    a.loadLabel(pm)).toLowerCase(java.util.Locale.ROOT);
            String lb = String.valueOf(
                    b2.loadLabel(pm)).toLowerCase(java.util.Locale.ROOT);
            return la.compareTo(lb);
        });
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (android.content.pm.ApplicationInfo ai : ais) {
            out.add(new String[]{ai.packageName,
                    String.valueOf(ai.loadLabel(pm))});
        }
        return out;
    }

    // Dialog mode IP per aplikasi: tap tombol mode untuk putar
    // Global -> IPv4 -> IPv6 -> Global. Berlaku saat VPN connect berikutnya.
    // Searchbar di dalam list (header ListView). Filter cocokkan
    // label ATAU package name. Dipasang sebelum setAdapter.
    private android.widget.EditText addSearchHeader(
            android.widget.ListView lv,
            java.util.List<String[]> apps,
            java.util.List<String[]> shown,
            android.widget.BaseAdapter ad) {
        android.widget.EditText search = new android.widget.EditText(this);
        search.setHint("Cari aplikasi...");
        search.setSingleLine(true);
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);
        search.setPadding(pad, pad / 2, pad, pad / 2);
        lv.addHeaderView(search);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(
                    CharSequence cs, int a, int b, int c) {}
            public void onTextChanged(CharSequence cs, int a, int b, int c) {}
            public void afterTextChanged(Editable e) {
                String q = e.toString()
                        .toLowerCase(java.util.Locale.ROOT);
                shown.clear();
                for (String[] a : apps) {
                    if (a[1].toLowerCase(java.util.Locale.ROOT).contains(q)
                            || a[0].toLowerCase(java.util.Locale.ROOT)
                                    .contains(q)) {
                        shown.add(a);
                    }
                }
                ad.notifyDataSetChanged();
            }
        });
        return search;
    }

    private void showAppIpModePicker() {
        ensureAppCache(this::showAppIpModePickerNow);
    }

    private void showAppIpModePickerNow(java.util.List<String[]> apps) {
        android.content.SharedPreferences vp =
                getSharedPreferences("vpn", MODE_PRIVATE);
        java.util.List<String[]> shown =
                new java.util.ArrayList<>(apps);
        android.widget.ListView lv = new android.widget.ListView(this);
        android.widget.BaseAdapter ad = new android.widget.BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int p) { return shown.get(p); }
            public long getItemId(int p) { return p; }
            public android.view.View getView(int pos,
                    android.view.View cv, android.view.ViewGroup parent) {
                String pkg = shown.get(pos)[0];
                String label = shown.get(pos)[1];
                android.widget.LinearLayout row;
                android.widget.TextView tv;
                android.widget.Button btn;
                if (cv == null) {
                    row = new android.widget.LinearLayout(MainActivity.this);
                    row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
                    row.setPadding(24, 16, 24, 16);
                    tv = new android.widget.TextView(MainActivity.this);
                    tv.setLayoutParams(
                            new android.widget.LinearLayout.LayoutParams(0,
                                    android.view.ViewGroup.LayoutParams
                                            .WRAP_CONTENT, 1f));
                    tv.setTextSize(15);
                    btn = new android.widget.Button(MainActivity.this);
                    row.addView(tv);
                    row.addView(btn);
                    row.setTag(new android.view.View[]{tv, btn});
                } else {
                    row = (android.widget.LinearLayout) cv;
                    android.view.View[] t =
                            (android.view.View[]) row.getTag();
                    tv = (android.widget.TextView) t[0];
                    btn = (android.widget.Button) t[1];
                }
                tv.setText(label);
                String key = "vpn_app_ip_" + pkg;
                btn.setText(appIpModeLabel(vp.getString(key, "")));
                // cy6: baris dialog ikut sistem glitch (dedup di dalam).
                GlitchText.registerTree(row);
                GlitchText.installTouch(row);
                btn.setOnClickListener(v -> {
                    String cur = vp.getString(key, "");
                    String next = cur.isEmpty() ? "v4"
                            : cur.equals("v4") ? "v6" : "";
                    if (next.isEmpty()) vp.edit().remove(key).apply();
                    else vp.edit().putString(key, next).apply();
                    btn.setText(appIpModeLabel(next));
                    // cy7: mode per app berubah = HANYA tombol mode itu
                    // (label row tidak berubah -> tidak ikut glitch).
                    GlitchText.glitchNow(btn, GlitchText.MEDIUM);
                    updateVpnIpModeCount();
                });
                return row;
            }
        };
        addSearchHeader(lv, apps, shown, ad);
        lv.setAdapter(ad);
        // cy8: dialog "Mode IP per aplikasi" muncul KARENA GLITCH:
        // window flicker + scanline menyapu panel + baris list menyala
        // staggered + korupsi teks. Background aplikasi TIDAK tersentuh
        // (semua efek level window/panel).
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Mode IP per aplikasi")
                .setView(lv)
                .setPositiveButton("Tutup", null)
                .show();
        View dec = dlg.getWindow() != null
                ? dlg.getWindow().getDecorView() : lv;
        GlitchText.registerTree(dec);
        GlitchText.installTouch(dec);
        // Window animation di-guard animScale (0 = tanpa animasi).
        if (animScale() > 0f && dlg.getWindow() != null) {
            dlg.getWindow().setWindowAnimations(R.style.GlitchWindowAnim);
        }
        dec.post(() -> {
            if (dec.getWindowToken() == null) return; // dialog sudah tutup
            GlitchText.scanline(dec, 340);
            GlitchText.glitchTree(lv, GlitchText.MEDIUM);
            GlitchText.materializeStaggered(lv);
        });
    }


    // Dialog pilih aplikasi untuk per-app VPN.
    private void showAppPicker() {
        ensureAppCache(this::showAppPickerNow);
    }

    private void showAppPickerNow(java.util.List<String[]> apps) {
        java.util.Set<String> saved = getSharedPreferences("vpn", MODE_PRIVATE)
                .getStringSet("vpn_apps", new java.util.HashSet<>());
        java.util.Set<String> checked = new java.util.HashSet<>(saved);
        java.util.List<String[]> shown =
                new java.util.ArrayList<>(apps);
        android.widget.ListView lv = new android.widget.ListView(this);
        android.widget.BaseAdapter ad = new android.widget.BaseAdapter() {
            public int getCount() { return shown.size(); }
            public Object getItem(int p) { return shown.get(p); }
            public long getItemId(int p) { return p; }
            public android.view.View getView(int pos,
                    android.view.View cv, android.view.ViewGroup parent) {
                String pkg = shown.get(pos)[0];
                String label = shown.get(pos)[1];
                android.widget.CheckBox cb;
                if (cv == null) {
                    cb = new android.widget.CheckBox(MainActivity.this);
                    cb.setPadding(24, 16, 24, 16);
                    cb.setTextSize(15);
                } else {
                    cb = (android.widget.CheckBox) cv;
                }
                cb.setText(label);
                cb.setOnCheckedChangeListener(null);
                cb.setChecked(checked.contains(pkg));
                // cy6: baris dialog ikut sistem glitch (dedup di dalam).
                GlitchText.registerTree(cb);
                GlitchText.installTouch(cb);
                cb.setOnCheckedChangeListener((b, isC) -> {
                    if (isC) checked.add(pkg);
                    else checked.remove(pkg);
                    // cy8: pilihan aplikasi berubah = checkbox itu korupsi
                    // (span label + micro displacement), row lain diam.
                    GlitchText.glitchNow(cb, GlitchText.MINOR);
                    GlitchText.glitchJitter(cb, GlitchText.MINOR);
                });
                return cb;
            }
        };
        addSearchHeader(lv, apps, shown, ad);
        lv.setAdapter(ad);
        // cy8: dialog "Pilih aplikasi" (Split Tunnel) muncul KARENA GLITCH:
        // window flicker + scanline menyapu panel + item list materialize
        // staggered. Search field & checkbox di dalamnya mengikuti aturan
        // event-driven (watcher region + listener per-checkbox).
        android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setTitle("Pilih aplikasi")
                .setView(lv)
                .setPositiveButton("OK", (d, w) -> {
                    getSharedPreferences("vpn", MODE_PRIVATE).edit()
                            .putStringSet("vpn_apps", checked).apply();
                    updateVpnAppCount();
                    log("per-app: " + checked.size() + " aplikasi dipilih");
                })
                .setNegativeButton("Batal", null)
                .show();
        View dec = dlg.getWindow() != null
                ? dlg.getWindow().getDecorView() : lv;
        GlitchText.registerTree(dec);
        GlitchText.installTouch(dec);
        if (animScale() > 0f && dlg.getWindow() != null) {
            dlg.getWindow().setWindowAnimations(R.style.GlitchWindowAnim);
        }
        dec.post(() -> {
            if (dec.getWindowToken() == null) return; // dialog sudah tutup
            GlitchText.scanline(dec, 340);
            GlitchText.glitchTree(lv, GlitchText.MEDIUM);
            GlitchText.materializeStaggered(lv);
        });
    }


    // Glitchcore cy3: HUD in-app pengganti Toast - teks prefix "> " ala
    // terminal, auto-hide 2.4 detik; fallback ke Toast kalau dipanggil
    // sebelum binding layout selesai.
    private void hud(final String msg) {
        if (hudToast == null) {
            android.widget.Toast.makeText(this, msg,
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        ui.post(() -> {
            hudGen++;
            final int gen = hudGen;
            hudToast.setText("> " + msg);
            hudToast.setVisibility(android.view.View.VISIBLE);
            // cy7: HUD muncul KARENA GLITCH (materialize), bukan fade.
            GlitchText.glitchAppear(hudToast);
            ui.postDelayed(() -> hideHud(gen), 2400);
        });
    }

    // cy7: HUD hilang KARENA GLITCH (disintegrate) dengan guard generasi;
    // setelah GONE alpha dipastikan 1f (tidak ada state tertinggal).
    private void hideHud(final int gen) {
        if (gen != hudGen) return;
        GlitchText.glitchDisappear(hudToast);
        ui.postDelayed(() -> {
            if (gen == hudGen) {
                hudToast.setVisibility(android.view.View.GONE);
                hudToast.setAlpha(1f);
            }
        }, 110);
    }

    // Glitchcore cy3: kilat 4 langkah putih -> merah -> cyan -> putih
    // sebelum warna status final (kesan "sinyal kehilangan sinkron" saat
    // engine pindah state). Hormati pengaturan animasi (animScale 0 =
    // langsung warna final, tanpa kilat).
    private void glitchFlash(final TextView tv, final int finalColor) {
        if (animScale() <= 0f) {
            tv.setTextColor(finalColor);
            return;
        }
        tv.setTextColor(getColor(R.color.glitch_white));
        tv.postDelayed(() -> tv.setTextColor(getColor(R.color.glitch_shadow)), 45);
        tv.postDelayed(() -> tv.setTextColor(getColor(R.color.m3_primary)), 90);
        tv.postDelayed(() -> tv.setTextColor(getColor(R.color.glitch_white)), 135);
        tv.postDelayed(() -> tv.setTextColor(finalColor), 180);
    }

    private void toastDns(String msg) {
        hud(msg);
    }

    private void toggleVpn() {
        if (VpnEngine.running) {
            VpnEngine.disconnect(); // teardown sinkron (tutup TUN langsung)
            stopService(new Intent(this, VpnEngine.class)); // fallback framework
            log("VPN dimatikan");
            updateVpnUi(); // update UI segera, jangan tunggu broadcast
            return;
        }
        if (engineState != ST_RUNNING) {
            hud("Start engine dulu sebelum VPN");
            return;
        }
        String mode = dnsModeKey();
        String server = vpnDnsServer.getText().toString().trim();
        String dnsIp, dnsTarget; // dnsIp=iklan ke sistem, dnsTarget=upstream
        if (mode.equals("doh")) {
            // DoH: terima URL lengkap, hostname, atau IP.
            String url, host;
            if (server.startsWith("https://") || server.startsWith("http://")) {
                url = server;
                try {
                    host = new java.net.URL(url).getHost();
                } catch (Exception e) { host = null; }
                if (host == null || host.isEmpty()) {
                    toastDns("URL DoH tidak valid");
                    return;
                }
            } else if (validDns(server)) {
                url = dohUrlForIp(server);
                try {
                    host = new java.net.URL(url).getHost();
                } catch (Exception e) { host = null; }
            } else if (!server.isEmpty()) {
                host = server;
                url = "https://" + host + "/dns-query";
            } else {
                toastDns("Isi server DNS dulu");
                return;
            }
            try {
                // resolve sebelum VPN aktif agar lewat jalur langsung
                dnsIp = java.net.InetAddress.getByName(host).getHostAddress();
            } catch (Exception e) {
                toastDns("Gagal resolve " + host);
                return;
            }
            dnsTarget = url;
        } else {
            // plain/dot/doq: harus IP literal
            if (!validDns(server)) {
                toastDns("DNS tidak valid (pakai IP, mis. 1.1.1.1)");
                return;
            }
            dnsIp = server;
            dnsTarget = server;
        }
        // simpan preferensi (termasuk resolved ip/target untuk QS tile)
        getSharedPreferences("vpn", MODE_PRIVATE).edit()
                .putString("dns_mode", mode)
                .putString("dns_server", server)
                .putString("dns_ip", dnsIp)
                .putString("dns_target", dnsTarget).apply();
        Intent vpnIntent = new Intent(this, VpnEngine.class);
        vpnIntent.putExtra("dns_mode", mode);
        vpnIntent.putExtra("dns_ip", dnsIp);
        vpnIntent.putExtra("dns_target", dnsTarget);
        // per-app config ikut ke service
        android.content.SharedPreferences vp =
                getSharedPreferences("vpn", MODE_PRIVATE);
        vpnIntent.putExtra("vpn_app_mode",
                vp.getString("vpn_app_mode", "all"));
        java.util.Set<String> appSet =
                vp.getStringSet("vpn_apps", new java.util.HashSet<>());
        vpnIntent.putExtra("vpn_app_list", appSet.toArray(new String[0]));
        vpnIntent.putExtra("vpn_ip_mode",
                vp.getString("vpn_ip_mode", "dual"));
        try {
            Intent prep = VpnService.prepare(this);
            if (prep != null) {
                // simpan intent agar dipakai setelah izin diberikan
                pendingVpnIntent = vpnIntent;
                startActivityForResult(prep, VPN_REQUEST);
            } else {
                startService(vpnIntent);
                log("VPN dinyalakan (DNS " + mode + " " + dnsTarget + ")");
            }
        } catch (Exception e) {
            log(LV_ERROR, "gagal start VPN: " + e.getMessage());
        }
    }

    // Tombol upload aktif hanya saat engine IDLE dan profile < 5.
    private void updateAddBtn() {
        boolean en = engineState == ST_IDLE
                && profiles.size() < MAX_PROFILES;
        View addBtn = findViewById(R.id.addBtn);
        // cy8: enabled-state BERUBAH = tombol itu sendiri korupsi singkat
        // lalu merekonstruksi (dipanggil SEBELUM setEnabled).
        if (addBtn.isEnabled() != en) {
            GlitchText.glitchStateChange(addBtn, en);
        }
        addBtn.setEnabled(en);
        addBtn.setAlpha(en ? 1f : 0.4f);
    }

    // ---------- Config rows ----------
    // cy6: indeks profile yang BARU ditambahkan (di-set onActivityResult
    // sebelum rebuild) - row-nya di-glitch-appear spesifik; -1 = rebuild
    // biasa (hapus/onCreate) -> container direkonstruksi dgn glitch MAJOR.
    private int lastAddedProfileIdx = -1;

    private void rebuildConfigRows() {
        configContainer.removeAllViews();
        countFields.clear();
        configRows.clear();
        for (int i = 0; i < profiles.size(); i++) {
            final int idx = i;
            Profile pr = profiles.get(i);
            View row = LayoutInflater.from(this)
                    .inflate(R.layout.row_config, configContainer, false);
            GlitchText.registerTree(row); // Task 32: row dinamis ikut wander
            GlitchText.installTouch(row); // Task 33: tombol +/-/max/hapus glitch
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
                lastAddedProfileIdx = -1; // hapus -> rekonstruksi container
                // cy7: row yang dihapus DISINTEGRATE dulu (korupsi ->
                // fragment -> hilang), baru list direkonstruksi 120ms
                // kemudian - bukan hilang seketika.
                GlitchText.glitchDisappear(row);
                ui.postDelayed(() -> rebuildConfigRows(), 120);
                log("profile dihapus: " + rm.name);
            });
            configContainer.addView(row);
            configRows.add(row);
            // cy6: config baru "recovered karena glitch" pada ENTRY-nya
            // sendiri (bukan sekadar flicker seluruh list).
            if (idx == lastAddedProfileIdx) GlitchText.glitchAppear(row);
        }
        updateTotal();
        updateAddBtn();
        // cy8: HANYA row baru yang glitch (glitchAppear di atas). Rebuild
        // biasa (hapus/onCreate) TIDAK lagi menggelapkan seluruh container:
        // row yang dihapus sudah DISINTEGRATE sebelum rebuild dipanggil,
        // row lain isinya identik (tidak berubah = tidak ada glitch).
        lastAddedProfileIdx = -1;
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
        String[] bundled = {"wireproxy-1.conf"};
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
                log(LV_ERROR, "gagal salin bundled " + b + ": " + e.getMessage());
            }
        }
        // bersihkan sisa bundled lama yang sudah dihapus
        for (int i = 2; i <= 5; i++) {
            File old = new File(profilesDir(), "wireproxy-" + i + ".conf");
            if (old.exists()) old.delete();
        }
        File[] fs = profilesDir().listFiles();
        if (fs != null) {
            java.util.Arrays.sort(fs, (a, b2) -> a.getName().compareTo(b2.getName()));
            for (File f : fs) profiles.add(new Profile(f.getName(), f));
        }
    }

    // ---------- Proxy table ----------
    // cy8: tabel hanya glitch saat STATUS benar-benar berubah (engine
    // AKTIF <-> MATI) atau saat pertama dirender - rebuild akibat
    // pindah tab TIDAK glitch (tidak ada yang berubah).
    private String lastProxyStateKey = null;

    private void rebuildProxyTable() {
        // Status dari snapshot EngineClient (bukan Mobile.isRunning()).
        boolean running = EngineClient.get().snapshot().running;
        String key = running ? "on" : "off";
        boolean stateChanged = !key.equals(lastProxyStateKey);
        lastProxyStateKey = key;
        proxyTable.removeAllViews();
        addProxyRow("SOCKS5", "127.0.0.1:1080", running);
        addProxyRow("HTTP", "127.0.0.1:8080", running);
        // cy6/cy8: tabel "muncul kembali karena glitch" HANYA saat
        // statusnya berubah - bukan setiap pindah tab.
        if (stateChanged) GlitchText.glitchAppear(proxyTable);
        updateVpnUi(); // segarkan juga label proxy/VPN dari sumber yang sama
    }

    private void addProxyRow(String name, String addr, boolean running) {
        View row = LayoutInflater.from(this)
                .inflate(R.layout.row_proxy, proxyTable, false);
        GlitchText.registerTree(row); // Task 32: row dinamis ikut wander
        GlitchText.installTouch(row); // Task 33: tombol salin glitch
        ((TextView) row.findViewById(R.id.proxyName)).setText(name);
        ((TextView) row.findViewById(R.id.proxyAddr)).setText(addr);
        ((TextView) row.findViewById(R.id.proxyStatus))
                .setText(running ? "AKTIF" : "MATI");
        row.findViewById(R.id.proxyCopy).setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText(
                    "proxy", addr));
            log(LV_DEBUG, "proxy disalin: " + addr);
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
                log(LV_VERBOSE, "  " + e.getKey() + " x" + e.getValue());
            for (Map.Entry<String, Integer> e : errs.entrySet())
                log(LV_WARN, "  err: " + e.getKey() + " x" + e.getValue());
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
        if (monitorOn) return; // C2: jangan spawn thread monitor ganda
        monitorOn = true;
        new Thread(() -> {
            long prevTicks = readProcTicks();
            long prevTime = System.currentTimeMillis();
            long cachedDirSize = -1;
            int tick = 0;
            while (monitorOn) {
                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                tick++;
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
                final String fRam = ramStr, fCpu = cpuStr;
                updateHeader(fRam, fCpu);
                // Tick ringan (header saja) saat user tidak di halaman
                // Dashboard: GET_STATUS (binder + JSON), dirSize rekursif,
                // dan sessionStats utk 1200 sesi tidak murah; boros CPU
                // saat hasilnya tidak terlihat siapa pun.
                if (currentPage != R.id.pageHome) continue;
                // TAHAP 1: statistik engine diambil via GET_STATUS (binder
                // ke :goengine) — runtime Go tidak ada lagi di proses utama.
                // SATU panggilan status per tick; sessionStats (JSON besar)
                // hanya diminta saat dashboard terlihat (sudah dijamin oleh
                // guard currentPage di atas).
                EngineStatus es = EngineClient.get()
                        .fetchStatus(true, 1500);
                if (es == null) es = EngineClient.get().snapshot();
                // TAHAP 4: detail memori (GET_DEBUG_MEM) tiap 10 tick (20 dtk)
                // ditulis ke log untuk diagnostik.
                if (tick % 10 == 1) {
                    String dbg = EngineClient.get().getDebugMem(3000);
                    if (dbg != null) log(LV_DEBUG, "debug mem: " + dbg);
                }
                // Memori Go engine (wpmulti) vs sisa proses (apk).
                long goSysBytes = 0, goHeapBytes = 0, goHeapIdle = 0;
                try {
                    org.json.JSONObject m = new org.json.JSONObject(
                            es.memStats == null ? "{}" : es.memStats);
                    goSysBytes = m.optLong("sys");
                    goHeapBytes = m.optLong("heapAlloc");
                    goHeapIdle = m.optLong("heapIdle");
                } catch (Exception ignored) {}
                long apkKb = rssKb >= 0
                        ? Math.max(0, rssKb - goSysBytes / 1024) : -1;
                ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                long totalMb = mi.totalMem / 1048576;
                long availMb = mi.availMem / 1048576;
                // dirSize = traversal rekursif seluruh cache dir; mahal utk
                // cache besar. Refresh tiap 10 tick (20 detik), sisanya pakai
                // nilai cache.
                if (tick % 10 == 1 || cachedDirSize < 0)
                    cachedDirSize = dirSize(getCacheDir());
                long cacheKb = cachedDirSize / 1024;
                long goRoutines = es.goroutines;
                final String fGo = "wpmulti: " + fmtBytes(goSysBytes)
                        + " (heap " + fmtBytes(goHeapBytes)
                        + " idle " + fmtBytes(goHeapIdle)
                        + " goroutine " + goRoutines + ")";
                final String fApk = "apk: "
                        + (apkKb >= 0 ? fmtBytes(apkKb * 1024) : "-");
                final String fSys = "sistem " + (totalMb - availMb) + "/" + totalMb + " MB";
                final String fCache = "cache: " + cacheKb + " KB";
                // Statistik per sesi dari GET_STATUS (JSON via binder).
                // Fase 3: baris teks "#0 hs=- tx=0 B rx=0 B" diganti chip
                // (rebuildSessionChips); di sini cukup total + array-nya.
                long sessTx = 0, sessRx = 0;
                int nSess = 0;
                org.json.JSONArray arr = null;
                try {
                    arr = new org.json.JSONArray(
                            es.sessionStats == null ? "[]" : es.sessionStats);
                    nSess = arr.length();
                    for (int i = 0; i < nSess; i++) {
                        org.json.JSONObject o = arr.getJSONObject(i);
                        sessTx += o.optLong("tx_bytes");
                        sessRx += o.optLong("rx_bytes");
                    }
                } catch (Exception ignored) {}
                long rateTx = 0, rateRx = 0;
                if (prevSessT > 0 && curTime > prevSessT) {
                    double secs = (curTime - prevSessT) / 1000.0;
                    rateTx = Math.max(0,
                            (long) ((sessTx - prevSessTx) / secs));
                    rateRx = Math.max(0,
                            (long) ((sessRx - prevSessRx) / secs));
                }
                prevSessTx = sessTx;
                prevSessRx = sessRx;
                prevSessT = curTime;
                final long fRateTx = rateTx, fRateRx = rateRx;
                final long fSessTx = sessTx, fSessRx = sessRx;
                final int fNSess = nSess;
                final org.json.JSONArray fArr = arr;
                ui.post(() -> {
                    monGo.setText(fGo);
                    monRam.setText(fApk + " (" + fSys + ")");
                    monCpu.setText("CPU app: " + fCpu);
                    monCache.setText(fCache);
                    // Fase 3: throughput = angka besar; label sesi ringkas.
                    monRx.setText(fmtBytes(fRateRx) + "/s");
                    monRxTotal.setText("total " + fmtBytes(fSessRx));
                    monTx.setText(fmtBytes(fRateTx) + "/s");
                    monTxTotal.setText("total " + fmtBytes(fSessTx));
                    monSesi.setText("sesi aktif: " + fNSess);
                    rebuildSessionChips(fArr, 20);
                    trafficGraph.addSample(fRateRx, fRateTx);
                    if (VpnEngine.running) updateVpnUi();
                });
            }
        }).start();
    }

    // ---------- Fase 3: chip sesi ----------
    // Satu chip per sesi (maks showCap): dot warna status + judul mono.
    // Tap chip = buka/tutup detail. Chip di-update IN-PLACE (tanpa
    // inflate ulang) sehingga detail yang sedang di-expand tidak kolap
    // tiap tick 2 dtk; status expand dipersist di expandedSess.
    // Warna dot: hijau = trafik bertambah sejak tick lalu (aktif),
    // abu = hidup tapi idle, merah = belum pernah handshake (error).
    private void rebuildSessionChips(org.json.JSONArray arr, int showCap) {
        if (monSesiDetail == null) return;
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int n = arr == null ? 0 : Math.min(arr.length(), showCap);
        for (int i = 0; i < n; i++) {
            try {
                org.json.JSONObject o = arr.getJSONObject(i);
                final int idx = o.optInt("index", i);
                seen.add(idx);
                long tx = o.optLong("tx_bytes");
                long rx = o.optLong("rx_bytes");
                long hs = o.optLong("handshake_age_sec", -1);
                long act = tx + rx;
                Long prev = prevSessAct.get(idx);
                boolean active = prev != null && act > prev;
                prevSessAct.put(idx, act);
                View chip = chipViews.get(idx);
                if (chip == null) {
                    chip = LayoutInflater.from(this).inflate(
                            R.layout.row_session, monSesiDetail, false);
                    chipViews.put(idx, chip);
                    monSesiDetail.addView(chip);
                    GlitchText.registerTree(chip); // Task 32: chip sesi ikut wander
                    // cy6: chip baru "muncul karena glitch" (materialize).
                    GlitchText.glitchAppear(chip);
                    final View chipV = chip; // salinan effectively-final utk lambda
                    final TextView detailCh =
                            chip.findViewById(R.id.chipDetail);
                    chip.setOnClickListener(v -> {
                        boolean show = detailCh.getVisibility()
                                != View.VISIBLE;
                        detailCh.setVisibility(show ? View.VISIBLE
                                : View.GONE);
                        if (show) {
                            expandedSess.add(idx);
                            // cy6: detail ter-expand "reconstruct": teks
                            // detail glitch + chip tergemetrek MINOR.
                            GlitchText.glitchNow(detailCh);
                        }
                        GlitchText.glitchJitter(chipV, GlitchText.MINOR);
                    });
                }
                View dot = chip.findViewById(R.id.chipDot);
                TextView title = chip.findViewById(R.id.chipTitle);
                TextView detail = chip.findViewById(R.id.chipDetail);
                // Warna status via resource (cybercore) - getContext()
                // aman utk semua bentuk scope (lambda/anonymous class).
                int dotColor = hs < 0
                        ? dot.getContext().getColor(R.color.status_red)
                        : (active
                                ? dot.getContext().getColor(R.color.status_green)
                                : dot.getContext().getColor(R.color.status_gray));
                dot.getBackground().mutate().setTint(dotColor);
                title.setText(String.format("#%d hs=%s tx=%s rx=%s",
                        idx, hs < 0 ? "-" : hs + "s",
                        fmtBytes(tx), fmtBytes(rx)));
                detail.setText(String.format(
                        "sesi #%d - handshake %s lalu, total %s turun "
                                + "/ %s naik%s",
                        idx, hs < 0 ? "belum" : hs + " dtk",
                        fmtBytes(rx), fmtBytes(tx),
                        active ? " - aktif" : ""));
                detail.setVisibility(expandedSess.contains(idx)
                        ? View.VISIBLE : View.GONE);
            } catch (Exception ignored) {}
        }
        // buang chip sesi yang sudah tidak ada di snapshot terbaru
        java.util.Iterator<java.util.Map.Entry<Integer, View>> it =
                chipViews.entrySet().iterator();
        while (it.hasNext()) {
            java.util.Map.Entry<Integer, View> e = it.next();
            if (!seen.contains(e.getKey())) {
                // cy7: chip yang hilang DISINTEGRATE sendiri (bukan
                // container ikut flicker), lalu dilepas dari layout.
                View goneChip = e.getValue();
                GlitchText.glitchDisappear(goneChip);
                monSesiDetail.postDelayed(() ->
                        monSesiDetail.removeView(goneChip), 110);
                it.remove();
                prevSessAct.remove(e.getKey());
                expandedSess.remove(e.getKey());
            }
        }
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
        log(LV_DEBUG, "confs: " + total + " file");
        return total;
    }

    /**
     * Jalur sukses tunggal transisi ke RUNNING. Dipanggil dari EMPAT kanal
     * independen (siapa tiba lebih dulu):
     * 1. reply binder startEngine (kanal utama),
     * 2. broadcast ACTION_STATUS (setPackage) dari GoEngineService,
     * 3. StatusListener.onReady di :goengine -> broadcast yang sama,
     * 4. poll GET_STATUS via binder (beginStartSync, timeout 60 dtk).
     * Idempoten: hanya berlaku saat STARTING, atau IDLE yang perlu
     * sinkron (engine ternyata jalan setelah timeout UI).
     */
    private void engineReady(long sessions) {
        int st = engineState;
        if (st != ST_STARTING && st != ST_IDLE) return;
        stopStartSync();
        startBusy.set(false);
        log("engine running, sesi=" + sessions);
        ui.post(() -> rebuildProxyTable());
        // C1: tahan prioritas foreground (notif tunggal).
        ProxyKeepaliveService.start(this);
        startMonitor();
        setEngineState(ST_RUNNING);
    }

    /**
     * Watchdog START: selama STARTING, poll GET_STATUS via binder tiap
     * 1 dtk sebagai sumber kebenaran (TAHAP 1), dengan timeout 60 dtk agar
     * UI tidak pernah macet di "MEMULAI...".
     */
    private void beginStartSync(final int epoch) {
        stopStartSync();
        final long t0 = android.os.SystemClock.uptimeMillis();
        final Runnable[] holder = new Runnable[1];
        holder[0] = new Runnable() {
            @Override public void run() {
                if (engineState != ST_STARTING || startEpoch != epoch) {
                    return; // attempt sudah berakhir (sukses/gagal/timeout)
                }
                final Runnable poll = this;
                // GET_STATUS via binder: awaitConnected bisa blokir sampai
                // timeout -> DILARANG di main thread (v1.3:
                // IllegalMonitorStateException + risiko ANR). Poll di thread
                // latar; keputusan tetap dievaluasi di main (ui.post) dengan
                // guard state/epoch yang sama.
                new Thread(() -> {
                    EngineStatus es =
                            EngineClient.get().fetchStatus(false, 1500);
                    if (es == null) es = EngineClient.get().snapshot();
                    final boolean up = es.running;
                    final long sessions = es.sessions;
                    ui.post(() -> {
                        if (engineState != ST_STARTING
                                || startEpoch != epoch) {
                            return; // attempt sudah berakhir
                        }
                        if (up) {
                            engineReady(sessions);
                            return;
                        }
                        if (android.os.SystemClock.uptimeMillis() - t0
                                >= START_TIMEOUT_MS) {
                            startEpoch++; // batalkan attempt ini
                            startBusy.set(false);
                            log(LV_ERROR, "start timeout (60 dtk): engine tidak "
                                    + "merespons, kembali IDLE");
                            setEngineState(ST_IDLE);
                            return;
                        }
                        ui.postDelayed(poll, 1000);
                    });
                }, "startSync").start();
            }
        };
        startSync = holder[0];
        ui.postDelayed(holder[0], 1000);
    }

    private void stopStartSync() {
        if (startSync != null) {
            ui.removeCallbacks(startSync);
            startSync = null;
        }
    }

    private void doStart() {
        // C2/M5: jangan start ganda. Button memang disable saat STARTING/
        // STOPPING, tapi guard eksplisit menutup celah race-nya.
        if (engineState == ST_STARTING || engineState == ST_STOPPING) {
            log(LV_WARN, "start diabaikan: engine sedang transisi");
            return;
        }
        if (!startBusy.compareAndSet(false, true)) {
            log(LV_WARN, "start diabaikan: percobaan start masih berjalan");
            return;
        }
        // C2: sinkronisasi ke ground truth. Menangani engine hidup tapi UI
        // IDLE (activity dibuat ulang / start sebelumnya timeout tapi
        // akhirnya berhasil). Ground truth = GET_STATUS via binder.
        // DIPINDAH ke thread latar: fetchStatus -> awaitConnected bisa
        // blokir, DILARANG di main thread (v1.3: IllegalMonitorState
        // Exception + risiko ANR).
        final int epoch = ++startEpoch;
        setEngineState(ST_STARTING);
        // M1 (revisi): watchdog + poll sinkronisasi — lihat beginStartSync().
        beginStartSync(epoch);
        new Thread(() -> {
            try {
                log("=== START ===");
                EngineStatus gs = EngineClient.get().fetchStatus(false, 2000);
                if (gs != null && gs.running) {
                    log("engine sudah berjalan, sinkronisasi UI");
                    engineReady(gs.sessions);
                    return;
                }
                if (startEpoch != epoch) {
                    // Attempt sudah dibatalkan selama pengecekan status
                    // (stop/timeout) -> jangan lanjut menyalakan engine.
                    log(LV_WARN, "hasil start diabaikan (attempt kedaluwarsa)");
                    return;
                }
                File confDir = new File(getFilesDir(), "confs");
                int total = buildConfs(confDir);
                if (total == 0) {
                    log(LV_WARN, "set jumlah sesi dulu");
                    setEngineState(ST_IDLE);
                    return;
                }
                log("start " + total + " sesi via IEngineControl.startEngine "
                        + "(binder ke :goengine)...");
                // TAHAP 1: START(configDir) via AIDL — blokir sampai engine
                // siap; timeout total dijaga START_TIMEOUT_MS. setTempDir,
                // setLogFile, dan setStatusListener kini milik service.
                String err = EngineClient.get().startBlocking(
                        confDir.getAbsolutePath(), START_TIMEOUT_MS);
                if (startEpoch != epoch) {
                    // Watchdog sudah timeout: hasil attempt ini diabaikan
                    // agar tidak menimpa state baru.
                    log(LV_WARN, "hasil start diabaikan (attempt kedaluwarsa)");
                    return;
                }
                if (err != null && !err.isEmpty()) {
                    log(LV_ERROR, "GoEngine error: " + err);
                    setEngineState(ST_IDLE);
                    return;
                }
                // Reply binder "" = engine sudah siap (Mobile.start baru
                // kembali setelah semua sesi up). Kanal poll/broadcast
                // tetap ada sebagai cadangan (idempoten).
                EngineStatus es = EngineClient.get().snapshot();
                engineReady(es != null ? es.sessions : 0);
            } catch (Exception e) {
                if (startEpoch != epoch) return;
                log(LV_ERROR, "exception: " + e);
                setEngineState(ST_IDLE);
            } finally {
                startBusy.set(false);
            }
        }).start();
    }

    private void doStop() {
        setEngineState(ST_STOPPING);
        log("stopping...");
        // matikan VPN dulu sebelum engine berhenti
        if (VpnEngine.running) {
            VpnEngine.disconnect();
            stopService(new Intent(this, VpnEngine.class));
            log("VPN dimatikan otomatis");
        }
        // watchdog: paksa IDLE setelah 10 detik biar UI tidak stuck selamanya
        ui.postDelayed(() -> {
            if (engineState == ST_STOPPING) {
                log(LV_WARN, "stop timeout (10s), paksa berhenti");
                monitorOn = false;
                // C1: turunkan juga keepalive agar tidak ada notifikasi
                // "proxy berjalan" yang menggantung (idempoten).
                ProxyKeepaliveService.stop(MainActivity.this);
                setEngineState(ST_IDLE);
            }
        }, 10000);
        new Thread(() -> {
            monitorOn = false;
            // TAHAP 3: STOP via AIDL — proses :goengine di-hard-kill.
            // userRequestedStop diset oleh EngineClient SEBELUM perintah
            // dikirim, jadi kematian proses tampil sebagai berhenti normal.
            EngineClient.get().stop();
            log("STOP dikirim (hard kill :goengine)");
            // C1: matikan keepalive setelah engine berhenti (idempoten).
            ProxyKeepaliveService.stop(MainActivity.this);

            ui.post(() -> {
                verifyView.setText("verifikasi: belum dites");
                rebuildProxyTable();
                updateHeader("-", "-");
                trafficGraph.clear();
                // Fase 3: monSesiDetail kini container chip - kosongkan
                // dengan removeAllViews (setText tidak kompile), reset
                // state chip + angka throughput.
                monSesiDetail.removeAllViews();
                chipViews.clear();
                prevSessAct.clear();
                expandedSess.clear();
                monRx.setText("-");
                monTx.setText("-");
                monRxTotal.setText("total -");
                monTxTotal.setText("total -");
                prevSessTx = -1;
                prevSessRx = -1;
                prevSessT = 0;
            });
            setEngineState(ST_IDLE);
        }).start();
    }

    private BroadcastReceiver goStatusReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // (Log Go dan StatusListener kini di GoEngineService — proses
        // :goengine; bukan lagi tanggung jawab proses utama.)
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
        // Hangatkan cache daftar aplikasi di background agar dialog
        // pilihan aplikasi tidak lag saat pertama dibuka.
        // (Flag loading di-set agar ensureAppCache tidak menyalakan thread
        // kedua yang tumpang tindih saat user buru-buru buka dialog.)
        if (sAppCache == null && !sAppCacheLoading) {
            sAppCacheLoading = true;
            new Thread(() -> {
                try { sAppCache = loadInstalledApps(); }
                catch (Exception ignored) {
                    sAppCache = new java.util.ArrayList<>();
                }
                sAppCacheLoading = false;
            }).start();
        }
        super.onCreate(savedInstanceState);
        // TAHAP 1: EngineClient — SATU pintu kontrol engine. Inisialisasi
        // + listener status (ganti setStatusListener lama yang split-brain).
        EngineClient.get().init(this);
        EngineClient.get().addListener(clientListener);
        // Receiver untuk status engine (broadcast transisi; kanal async
        // tambahan di samping reply binder + poll GET_STATUS).
        goStatusReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                boolean running = intent.getBooleanExtra(GoEngineService.EXTRA_RUNNING, false);
                int sessions = intent.getIntExtra(GoEngineService.EXTRA_SESSIONS, 0);
                String err = intent.getStringExtra(GoEngineService.EXTRA_ERROR);
                if (err != null && !err.isEmpty()) {
                    log(LV_ERROR, "GoEngine error: " + err);
                    setEngineState(ST_IDLE);
                } else if (running) {
                    engineReady(sessions);
                } else {
                    setEngineState(ST_IDLE);
                }
                EngineClient.get().refreshAsync(); // segarkan snapshot
            }
        };
        registerReceiver(goStatusReceiver, new IntentFilter(GoEngineService.ACTION_STATUS), Context.RECEIVER_NOT_EXPORTED);
        setContentView(R.layout.activity_main);

        // TAHAP 2 + fix pill melayang: WindowInsets diterapkan SEKALI di
        // sini (SATU-satunya tempat; targetSdk 36 edge-to-edge, minSdk 34
        // -> API WindowInsets.Type tanpa androidx).
        // - Inset atas: padding TOP root (status bar + cutout, aman notch).
        // - Inset BAWAH TIDAK lagi menempel root: konten harus lewat di
        //   belakang pill sampai tepi bawah layar. Dipakai untuk:
        //   (1) margin bawah pill = inset nav + 4dp -> pill kecil ringkas
        //       di tengah bawah, tidak menabrak gesture navigation;
        //   (2) clearance bawah container scroll = token nav_pill_clearance
        //       + inset -> item terakhir tetap bisa digeser ke atas pill
        //       (kombinasi dengan clipToPadding=false di layout).
        View rootMain = findViewById(R.id.rootMain);
        rootMain.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(v.getPaddingLeft(), bars.top,
                    v.getPaddingRight(), 0);
            int base = getResources().getDimensionPixelSize(
                    R.dimen.nav_pill_clearance);
            View pill = findViewById(R.id.navPill);
            if (pill != null) {
                android.widget.FrameLayout.LayoutParams lp =
                        (android.widget.FrameLayout.LayoutParams)
                                pill.getLayoutParams();
                lp.bottomMargin = bars.bottom + (int) (4 * getResources()
                        .getDisplayMetrics().density);
                pill.setLayoutParams(lp);
            }
            // HUD notifikasi: tepat di atas pill (inset + 96dp).
            View hudView = findViewById(R.id.hudToast);
            if (hudView != null) {
                android.widget.FrameLayout.LayoutParams hp =
                        (android.widget.FrameLayout.LayoutParams)
                                hudView.getLayoutParams();
                hp.bottomMargin = bars.bottom + (int) (96 * getResources()
                        .getDisplayMetrics().density);
                hudView.setLayoutParams(hp);
            }
            int[] scrolls = {R.id.pageHome, R.id.pageSesi, R.id.logScroll,
                    R.id.pageSetting};
            for (int id : scrolls) {
                View s = findViewById(id);
                if (s != null) {
                    s.setPadding(s.getPaddingLeft(), s.getPaddingTop(),
                            s.getPaddingRight(), base + bars.bottom);
                }
            }
            return android.view.WindowInsets.CONSUMED;
        });

        headerStats = findViewById(R.id.headerStats);
        statusBar = findViewById(R.id.statusBar);
        // Glitchcore: ghost RGB-split - bayangan merah 70% offset 3dp ke
        // kanan, blur tipis 1dp (tanpa custom view/blur). Warna teks status
        // tetap dinamis via glitchFlash/status_*; ghost merah di belakangnya
        // konsisten sebagai sisi R dari pasangan chromatic (vs cyan UI).
        float gShadowD = getResources().getDisplayMetrics().density;
        statusBar.setShadowLayer(1f * gShadowD, 3f * gShadowD, 0f,
                getColor(R.color.glitch_shadow));
        // Task 32: daftarkan ghost kustom ini sbg baseline di GlitchText
        // supaya wander burst tidak menghapusnya saat restore.
        GlitchText.registerCustom(statusBar, 3f * gShadowD,
                getColor(R.color.glitch_shadow));
        heroLoading = findViewById(R.id.heroLoading);
        logView = findViewById(R.id.logView);
        hudToast = findViewById(R.id.hudToast);
        totalView = findViewById(R.id.totalView);
        verifyView = findViewById(R.id.verifyView);
        testResult = findViewById(R.id.testResult);
        monRam = findViewById(R.id.monRam);
        monGo = findViewById(R.id.monGo);
        // Glitchcore: sisi C dari pasangan chromatic - ghost cyan offset
        // KIRI di subtitle engine (kebalikan arah ghost merah statusBar).
        monGo.setShadowLayer(1f * gShadowD, -2f * gShadowD, 0f,
                getColor(R.color.glitch_shadow_cyan));
        // Task 32: baseline cyan kiri juga dipelihara via GlitchText.
        GlitchText.registerCustom(monGo, -2f * gShadowD,
                getColor(R.color.glitch_shadow_cyan));
        monCpu = findViewById(R.id.monCpu);
        monCache = findViewById(R.id.monCache);
        monSesi = findViewById(R.id.monSesi);
        monSesiDetail = findViewById(R.id.monSesiDetail);
        monRx = findViewById(R.id.monRx);
        monTx = findViewById(R.id.monTx);
        monRxTotal = findViewById(R.id.monRxTotal);
        monTxTotal = findViewById(R.id.monTxTotal);
        proxyStatusView = findViewById(R.id.proxyStatus);
        // Long-press pada teks CPU untuk dump goroutine stack
        monCpu.setOnLongClickListener(v -> {
            new Thread(() -> {
                try {
                    java.io.File extDir = getExternalFilesDir(null);
                    String path = (extDir != null ? extDir.getAbsolutePath()
                        : getCacheDir().getAbsolutePath()) + "/goroutine.txt";
                    // TAHAP 1: dump harus dari PROSES ENGINE (binder).
                    String err = EngineClient.get()
                            .writeProfile(false, path, 15000);
                    String msg = err == null || err.isEmpty()
                        ? "goroutine dump OK: " + path
                        : "goroutine dump gagal: " + err;
                    log(msg);
                    runOnUiThread(() -> hud(msg));
                } catch (Exception e) {
                    log("goroutine dump error: " + e);
                }
            }).start();
            return true;
        });
        // Long-press pada teks memori Go untuk dump heap profile
        monGo.setOnLongClickListener(v -> {
            new Thread(() -> {
                try {
                    java.io.File extDir = getExternalFilesDir(null);
                    String path = (extDir != null ? extDir.getAbsolutePath()
                        : getCacheDir().getAbsolutePath()) + "/heap.prof";
                    // TAHAP 1: dump harus dari PROSES ENGINE (binder).
                    String err = EngineClient.get()
                            .writeProfile(true, path, 15000);
                    String msg = err == null || err.isEmpty()
                        ? "heap dump OK: " + path
                        : "heap dump gagal: " + err;
                    log(msg);
                    runOnUiThread(() -> hud(msg));
                } catch (Exception e) {
                    log("heap dump error: " + e);
                }
            }).start();
            return true;
        });
        trafficGraph = findViewById(R.id.trafficGraph);
        vpnStatusView = findViewById(R.id.vpnStatus);
        vpnStatsView = findViewById(R.id.vpnStats);
        vpnToggleBtn = (MaterialButton) findViewById(R.id.vpnToggleBtn);
        vpnDnsMode = findViewById(R.id.vpnDnsMode);
        vpnDnsServer = findViewById(R.id.vpnDnsServer);
        SharedPreferences vprefs = getSharedPreferences("vpn", MODE_PRIVATE);
        String[] modes = {"DoH (HTTPS)", "DoT (TLS)", "DoQ (QUIC)", "Plain DNS"};
        android.widget.ArrayAdapter<String> dnsAd =
                new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, modes);
        dnsAd.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        vpnDnsMode.setAdapter(dnsAd);
        // mapping spinner position -> mode key
        String savedMode = vprefs.getString("dns_mode", "doh");
        int pos = 0;
        if (savedMode.equals("dot")) pos = 1;
        else if (savedMode.equals("doq")) pos = 2;
        else if (savedMode.equals("plain")) pos = 3;
        vpnDnsMode.setSelection(pos);
        vpnDnsServer.setText(vprefs.getString("dns_server", "1.1.1.1"));
        android.widget.CheckBox vpnAutoReconnect =
                findViewById(R.id.vpnAutoReconnect);
        this.vpnAutoReconnect = vpnAutoReconnect;
        vpnAutoReconnect.setChecked(vprefs.getBoolean("auto_reconnect", true));
        vpnAutoReconnect.setOnCheckedChangeListener((b, checked) -> {
                // cy8: checkbox BERUBAH = checkbox itu korupsi (span label
                // + micro displacement) - cursor & row tidak tersentuh.
                GlitchText.glitchNow((TextView) b, GlitchText.MINOR);
                GlitchText.glitchJitter(b, GlitchText.MINOR);
                getSharedPreferences("vpn", MODE_PRIVATE).edit()
                        .putBoolean("auto_reconnect", checked).apply();
        });
        vpnAppMode = findViewById(R.id.vpnAppMode);
        vpnAppCount = findViewById(R.id.vpnAppCount);
        String[] appModes = {"Semua aplikasi", "Hanya yang dipilih",
                "Kecuali yang dipilih"};
        android.widget.ArrayAdapter<String> appAd =
                new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, appModes);
        appAd.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        vpnAppMode.setAdapter(appAd);
        String savedAppMode = vprefs.getString("vpn_app_mode", "all");
        int appPos = savedAppMode.equals("allow") ? 1
                : savedAppMode.equals("deny") ? 2 : 0;
        vpnAppMode.setSelection(appPos);
        vpnAppMode.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> pa,
                                       android.view.View vw, int ps, long id) {
                String mk = ps == 1 ? "allow" : ps == 2 ? "deny" : "all";
                getSharedPreferences("vpn", MODE_PRIVATE).edit()
                        .putString("vpn_app_mode", mk).apply();
                // cy7: pindah mode split tunnel = spinner + counter itu
                // SAJA (bukan section/card, bukan seluruh halaman).
                GlitchText.glitchView(vpnAppMode, GlitchText.MINOR);
                GlitchText.glitchNow(vpnAppCount, GlitchText.MINOR);
                updateVpnAppCount();
            }
            public void onNothingSelected(android.widget.AdapterView<?> pa) {}
        });
        updateVpnAppCount();
        // Mode IP global: dual / bypass IPv4 / bypass IPv6.
        vpnIpMode = findViewById(R.id.vpnIpMode);
        String[] ipModes = {"Dual-stack (IPv4 + IPv6)",
                "IPv6 saja (bypass IPv4)", "IPv4 saja (bypass IPv6)"};
        android.widget.ArrayAdapter<String> ipAd =
                new android.widget.ArrayAdapter<>(this,
                        android.R.layout.simple_spinner_item, ipModes);
        ipAd.setDropDownViewResource(
                android.R.layout.simple_spinner_dropdown_item);
        vpnIpMode.setAdapter(ipAd);
        String savedIpMode = vprefs.getString("vpn_ip_mode", "dual");
        int ipPos = savedIpMode.equals("v6only") ? 1
                : savedIpMode.equals("v4only") ? 2 : 0;
        vpnIpMode.setSelection(ipPos);
        vpnIpMode.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> pa,
                                       android.view.View vw, int ps, long id) {
                String mk = ps == 1 ? "v6only" : ps == 2 ? "v4only" : "dual";
                getSharedPreferences("vpn", MODE_PRIVATE).edit()
                        .putString("vpn_ip_mode", mk).apply();
                // cy7: mode per IP global pindah = spinner + counter saja.
                GlitchText.glitchView(vpnIpMode, GlitchText.MINOR);
                GlitchText.glitchNow(vpnIpModeAppCount, GlitchText.MINOR);
                updateVpnIpModeCount();
            }
            public void onNothingSelected(android.widget.AdapterView<?> pa) {}
        });
        findViewById(R.id.vpnPickAppsBtn).setOnClickListener(vp -> showAppPicker());
        vpnIpModeAppCount = findViewById(R.id.vpnIpModeAppCount);
        updateVpnIpModeCount();
        findViewById(R.id.vpnPickIpModeBtn).setOnClickListener(
                vp -> showAppIpModePicker());
        findViewById(R.id.vpnSysSettingsBtn).setOnClickListener(v -> {
            try {
                startActivity(new android.content.Intent(
                        android.provider.Settings.ACTION_VPN_SETTINGS));
            } catch (Exception e) {
                log(LV_WARN, "tidak bisa buka pengaturan VPN: " + e.getMessage());
            }
        });
        vpnDnsMode.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p,
                                       android.view.View v, int ps, long id) {
                // cy7: dropdown DNS diganti = spinner itu saja; hint field
                // berubah di updateDnsHint (teks berubah = watcher sendiri).
                GlitchText.glitchView(vpnDnsMode, GlitchText.MINOR);
                updateDnsHint();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });
        updateDnsHint();
        configContainer = findViewById(R.id.configList);
        proxyTable = findViewById(R.id.proxyTable);
        logFilter = findViewById(R.id.logFilter);
        logLevel = findViewById(R.id.logLevel);

        // Task 32 (GlitchText "banyak tapi tipis"): daftarkan SELURUH
        // TextView di bawah rootMain (dashboard, sesi, log, setelan) ke
        // mesin glitch - ghost tipis baseline + wander burst span neon.
        // Input user (EditText) dan logView span dikecualikan di dalamnya.
        GlitchText.registerTree(findViewById(R.id.rootMain));
        // Task 33: SEMUA Button (glitch saat ditekan) + Spinner (glitch saat
        // dropdown dibuka) di seluruh halaman.
        GlitchText.installTouch(findViewById(R.id.rootMain));

        // bottom navigation (4 tab) — pengganti navigasi drawer lama
        BottomNavigationView bnv = findViewById(R.id.bottomNav);
        // cy8 FIX ARTIFACT KOTAK: pill nav ber-elevation 6dp TIDAK BOLEH
        // di-alpha-flicker (glitchView) - alpha < 1 memaksa offscreen layer
        // yang ter-clip bounds persegi sehingga shadow elevation rusak
        // berbentuk KOTAK di sekitar pill (persis artifact di screenshot).
        // Press feedback pada area nav = displacement murni (glitchJitter)
        // + glitch per-item lewat glitchNavItems di bawah.
        bnv.setOnTouchListener((v, ev) -> {
            if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                View pillGlitch = findViewById(R.id.navPill);
                if (pillGlitch != null) {
                    GlitchText.glitchJitter(pillGlitch, GlitchText.MINOR);
                }
            }
            return false; // tidak dikonsumsi: pilihan item tetap normal
        });
        bnv.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            // cy8: perubahan active state navbar = ITEM yang kehilangan
            // active state korupsi singkat + item yang MENDAPATkannya
            // merekonstruksi - MASING-MASING, bukan seluruh navbar.
            // (glitchNavItems sudah ada sejak cy7 tapi belum pernah
            // dipanggil - dead code; kini benar-benar di-wire.)
            glitchNavItems(bnv, lastNavItemId, id);
            lastNavItemId = id;
            if (id == R.id.navHome) {
                rebuildProxyTable();
                updateVpnUi();
                showPage(R.id.pageHome);
            } else if (id == R.id.navSesi) {
                rebuildProxyTable();
                showPage(R.id.pageSesi);
            } else if (id == R.id.navLog) {
                showPage(R.id.pageLog);
            } else if (id == R.id.navSetelan) {
                showPage(R.id.pageSetting);
            }
            return true;
        });
        showPage(R.id.pageHome);
        bnv.setSelectedItemId(R.id.navHome);
        lastNavItemId = R.id.navHome;
        // cy8: label item nav di-inflate lazy -> daftarkan ke registry
        // glitch SETELAH layout pertama (ikut ambient wander; span korupsi
        // glitchNavItems juga tak lagi bergantung pada pemanggilan awal).
        bnv.post(() -> GlitchText.registerTree(bnv));

        // log level spinner
        ArrayAdapter<String> ad = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Semua", "Verbose", "Debug", "Info", "Warn", "Error"});
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        logLevel.setAdapter(ad);
        logLevel.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                logLevelSel = (String) p.getItemAtPosition(pos);
                GlitchText.glitchView(logLevel, GlitchText.MINOR); // cy7: spinner saja
                renderLog();
            }
            public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        // cy9: MODE EFEK GLITCH (Auto / Selalu aktif / Mati) di halaman
        // Setelan. Default: Selalu aktif - glitch tetap jalan walau skala
        // animator sistem = 0 (semua penggerak waktu efek sudah
        // Handler/Choreographer sendiri, hasil visual tidak berubah).
        // Satu fungsi pusat GlitchText.isGlitchEnabled() dipakai SEMUA
        // efek; glitch hanya lapisan visual - state UI & input normal.
        Spinner glitchModeSp = findViewById(R.id.glitchMode);
        ArrayAdapter<String> gmAd = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"Auto (ikuti sistem)", "Selalu aktif", "Mati"});
        gmAd.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        glitchModeSp.setAdapter(gmAd);
        int gmode = getSharedPreferences("ui", MODE_PRIVATE)
                .getInt("glitch_mode", GlitchText.MODE_ALWAYS_ON);
        GlitchText.setMode(gmode);
        glitchModeSp.setSelection(gmode == GlitchText.MODE_AUTO ? 0
                : gmode == GlitchText.MODE_OFF ? 2 : 1);
        glitchModeSp.setOnItemSelectedListener(
                new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> p,
                                       View v, int pos, long id) {
                int m = pos == 0 ? GlitchText.MODE_AUTO
                        : pos == 2 ? GlitchText.MODE_OFF
                        : GlitchText.MODE_ALWAYS_ON;
                if (m == GlitchText.getMode()) return; // pilihan awal: no-op
                getSharedPreferences("ui", MODE_PRIVATE).edit()
                        .putInt("glitch_mode", m).apply();
                GlitchText.setMode(m);
                // denyut dot status ikut mode (mulai/berhenti seketika);
                // morph/bounce pendek self-terminating & berakhir di nilai
                // final - tidak ada state tertinggal saat pindah mode.
                updateDotPulse(engineState);
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
            log(LV_DEBUG, "log disalin ke clipboard");
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
        // C2: jangan asumsikan IDLE saat activity dibuat. Rotasi /
        // swipe-reopen membuat ulang activity saat engine masih berjalan.
        // Ground truth = GET_STATUS via binder; sinkronisasi dilakukan
        // asinkron oleh clientListener.onStatus() (binder call tidak boleh
        // diblokir di onCreate).
        setEngineState(ST_IDLE);
        EngineClient.get().refreshAsync();

        // vpn
        findViewById(R.id.vpnToggleBtn).setOnClickListener(v -> toggleVpn());
        findViewById(R.id.pingTestBtn).setOnClickListener(v -> runPingTest());
        vpnDropReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent it) {
                log(LV_WARN, "KILL SWITCH: VPN putus tak terduga!");
                hud("VPN putus! Trafik tidak aman.");
                updateVpnUi();
            }
        };
        // minSdk 34: RECEIVER_NOT_EXPORTED selalu tersedia -> guard <33
        // (dead code) dihapus.
        registerReceiver(vpnDropReceiver,
                new IntentFilter(VpnEngine.ACTION_VPN_DROP),
                Context.RECEIVER_NOT_EXPORTED);
        vpnReceiver = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent it) {
                boolean r = it.getBooleanExtra(VpnEngine.EXTRA_RUNNING, false);
                log(r ? "VPN aktif" : "VPN mati");
                updateVpnUi();
            }
        };
        registerReceiver(vpnReceiver,
                new IntentFilter(VpnEngine.ACTION_VPN_STATE),
                Context.RECEIVER_NOT_EXPORTED);
        updateVpnUi();

        // proxy test
        findViewById(R.id.test1Btn).setOnClickListener(v -> testIp(1));
        findViewById(R.id.test20Btn).setOnClickListener(v -> testIp(20));

        // setting
        ((TextView) findViewById(R.id.settingVer)).setText(
                "wpmulti-test v" + BuildConfig.VERSION_NAME
                        + " (build " + BuildConfig.VERSION_CODE + ")");
        fillSettingInfo();
        findViewById(R.id.exportLogBtn).setOnClickListener(v -> new Thread(() -> {
            try {
                File dir = getExternalFilesDir(null);
                if (dir != null) {
                    if (!dir.exists()) dir.mkdirs();
                    String name = "wpmulti-log-"
                            + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",
                                    java.util.Locale.US).format(new java.util.Date())
                            + ".txt";
                    File out = new File(dir, name);
                    try (java.io.PrintWriter pw = new java.io.PrintWriter(out)) {
                        for (LogEntry e : logLines) pw.println(e.text);
                    }
                    log("log diekspor: " + out.getAbsolutePath());
                    hud("Log tersimpan: " + name);
                }
            } catch (Exception e) {
                log(LV_ERROR, "gagal ekspor log: " + e.getMessage());
            }
        }).start());
        findViewById(R.id.clearCacheBtn).setOnClickListener(v -> new Thread(() -> {
            deleteDir(getCacheDir());
            log("cache dihapus");
        }).start());

        // TAHAP 1: listener status dari EngineClient (menggantikan
        // Mobile.setStatusListener yang dulu didaftarkan di proses utama —
        // split-brain). Semua label diturunkan dari EngineStatus yang sama.
        // (Field clientListener didefinisikan di bawah.)

        rebuildProxyTable();
        updateHeader("-", "-");
    }

    /**
     * Listener status engine: satu-satunya tempat UI merespons perubahan
     * status dari :goengine (hasil GET_STATUS atau kematian proses).
     */
    private final EngineClient.Listener clientListener = new EngineClient.Listener() {
        @Override public void onStatus(EngineStatus s) {
            ui.post(() -> {
                if (s.running) {
                    // Resync saat activity baru dibuat saat engine jalan,
                    // atau konfirmasi start yang tertunda.
                    if (engineState == ST_IDLE || engineState == ST_STARTING) {
                        engineReady(s.sessions);
                    }
                } else if (engineState == ST_RUNNING) {
                    // Status bilang mati tapi UI masih RUNNING (mis. engine
                    // mati tanpa terdeteksi listener lain).
                    setEngineState(ST_IDLE);
                }
                updateVpnUi();
            });
        }

        @Override public void onEngineGone(boolean userRequested) {
            ui.post(() -> {
                if (userRequested) {
                    // TAHAP 3: stop yang disengaja — BUKAN crash.
                    if (engineState == ST_STOPPING || engineState == ST_RUNNING) {
                        log("engine berhenti (permintaan user)");
                        monitorOn = false;
                        ProxyKeepaliveService.stop(MainActivity.this);
                        setEngineState(ST_IDLE);
                    }
                } else {
                    log(LV_ERROR, "proses engine (:goengine) mati tak terduga");
                    // VPN yang masih hidup akan menyalurkan trafik ke proxy
                    // mati -> matikan VPN juga (kill-switch lokal).
                    if (VpnEngine.running) {
                        VpnEngine.disconnect();
                        stopService(new Intent(MainActivity.this, VpnEngine.class));
                        log("VPN ikut dimatikan (engine mati)");
                    }
                    monitorOn = false;
                    ProxyKeepaliveService.stop(MainActivity.this);
                    setEngineState(ST_IDLE);
                }
                updateVpnUi();
            });
        }
    };

    private void deleteDir(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) {
            if (f.isDirectory()) deleteDir(f);
            f.delete();
        }
    }

    private void fillSettingInfo() {
        try {
            ActivityManager am = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            String dev = android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL
                    + "\nAndroid " + android.os.Build.VERSION.RELEASE
                    + " (SDK " + android.os.Build.VERSION.SDK_INT + ")"
                    + "\nCPU: " + Runtime.getRuntime().availableProcessors() + " core"
                    + "\nRAM total: " + (mi.totalMem / 1048576) + " MB";
            ((TextView) findViewById(R.id.settingDevice)).setText(dev);
            String paths = "files: " + getFilesDir().getAbsolutePath()
                    + "\ncache: " + getCacheDir().getAbsolutePath();
            File ext = getExternalFilesDir(null);
            if (ext != null) paths += "\nexternal: " + ext.getAbsolutePath();
            ((TextView) findViewById(R.id.settingPaths)).setText(paths);
        } catch (Exception e) {
            log(LV_ERROR, "gagal baca info device: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == VPN_REQUEST) {
            if (res == RESULT_OK) {
                Intent vi = pendingVpnIntent != null ? pendingVpnIntent
                        : new Intent(this, VpnEngine.class);
                pendingVpnIntent = null;
                startService(vi);
                log("VPN dinyalakan (izin diberikan)");
            } else {
                log(LV_WARN, "izin VPN ditolak user");
            }
            return;
        }
        if (req == PICK_CONF && res == RESULT_OK && data != null) {
            if (profiles.size() >= MAX_PROFILES) {
                log(LV_WARN, "maksimal " + MAX_PROFILES + " profile");
                hud("Maksimal " + MAX_PROFILES + " profile");
                return;
            }
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
                // cy6: tandai row baru -> rebuildConfigRows memberi glitch
                // appear spesifik pada entry yang baru dibuat.
                lastAddedProfileIdx = profiles.size() - 1;
                rebuildConfigRows();
                log("profile ditambah: " + name);
            } catch (Exception e) {
                log(LV_ERROR, "gagal upload profile: " + e.getMessage());
            }
        }
    }

    @Override
    protected void onDestroy() {
        if (goStatusReceiver != null) {
            try { unregisterReceiver(goStatusReceiver); } catch (Exception ignored) {}
        }
        // Lepas listener agar activity lama tidak bocor di daftar listener.
        EngineClient.get().removeListener(clientListener);
        monitorOn = false;
        try {
            if (vpnReceiver != null) unregisterReceiver(vpnReceiver);
            if (vpnDropReceiver != null) unregisterReceiver(vpnDropReceiver);
        } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override
    protected void onPause() {
        // Fase 4 (revisi review): denyut dot tidak perlu saat activity
        // tidak terlihat - hentikan agar tidak boros CPU/baterai.
        stopDotPulse();
        // Task 32: loop wander GlitchText berhenti + semua kilatan
        // dipulihkan saat activity tidak terlihat (hemat CPU/baterai).
        GlitchText.stop();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // cy9: skala animator sistem hanya berpengaruh pada mode AUTO
        // ("ikuti sistem"); mode Selalu aktif (default) tetap menjalankan
        // glitch walau skala 0 - semua penggerak waktu efek adalah
        // Handler/Choreographer sendiri (step()/SelfAnim).
        GlitchText.setAnimScale(animScale());
        // Task 32: nyalakan lagi wander glitch (registry dipertahankan;
        // ref mati di-purge di dalam start()).
        GlitchText.start(this);
        if (engineState == ST_RUNNING) {
            // pasang ulang dot lalu denyut lagi (drawable bisa
            // tertinggal alpha rendah sebelum onPause)
            statusBar.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_dot_green, 0, 0, 0);
            updateDotPulse(ST_RUNNING);
        }
    }

    private void runPingTest() {
        android.widget.TextView tv = findViewById(R.id.pingResult);
        // Fase 3: pingResult GONE saat kosong (XML), VISIBLE saat dipakai.
        tv.setVisibility(View.VISIBLE);
        tv.setText("testing...");
        new Thread(() -> {
            StringBuilder sb = new StringBuilder();
            if (!com.wpmulti.test.VpnEngine.running) {
                runOnUiThread(() -> tv.setText("VPN belum jalan"));
                return;
            }
            String[] reason = new String[1];
            long ms4 = com.wpmulti.test.VpnEngine.testPing(false, "1.1.1.1", reason);
            sb.append("ping 1.1.1.1: ").append(ms4 >= 0 ? ("OK " + ms4 + " ms (" + reason[0] + ")") : ("GAGAL: " + reason[0])).append("\n");
            String[] reason6 = new String[1];
            long ms6 = com.wpmulti.test.VpnEngine.testPing(true, "2606:4700:4700::1111", reason6);
            sb.append("ping 2606:4700:4700::1111: ").append(ms6 >= 0 ? ("OK " + ms6 + " ms (" + reason6[0] + ")") : ("GAGAL: " + reason6[0]));
            sb.append("\n--- diagnostik TUN ---\n");
            sb.append("paket ICMP via TUN: ").append(com.wpmulti.test.VpnEngine.icmpTunCount).append("\n");
            sb.append("reply Go->TUN: ").append(com.wpmulti.test.VpnEngine.icmpGoOkCount).append("\n");
            sb.append("hex terakhir: ").append(com.wpmulti.test.VpnEngine.icmpLastHex);
            String out = sb.toString();
            runOnUiThread(() -> tv.setText(out));
        }).start();
    }
}