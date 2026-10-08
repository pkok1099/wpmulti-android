package com.wpmulti.test;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

/**
 * GlitchText v4 (fase cy8) - glitch EVENT-DRIVEN tepat sasaran +
 * mekanisme perubahan UI, bukan dekorasi.
 *
 * PRINSIP UTAMA: "Sesuatu yang berubah - sesuatu itulah yang glitch."
 * Bukan seluruh UI. Frekuensi event = TINGGI, visual noise = RENDAH.
 *
 * PERBAIKAN cy8 (di atas cy7):
 *
 *  1. FIX DROPDOWN DEAD CODE: cabang Spinner di installTouch dulu berada
 *     SETELAH cabang ViewGroup (Spinner ADALAH ViewGroup) -> tidak pernah
 *     dijalankan -> yang terbuka popup platform biasa. Kini Spinner
 *     dicek PERTAMA, GlitchDropdown benar-benar aktif.
 *
 *  2. GUARD ELEVATION (fix artifact KOTAK di pill nav / HUD): semua efek
 *     berbasis alpha (glitchView/glitchAppear/glitchDisappear/materialize
 *     staggered/page transition) MENOLKAN elevation view -> 0 selama
 *     efek, lalu memulihkannya deterministik (BASE kini menyimpan
 *     {alpha, tx, scaleX, ELEVATION}). Alpha < 1 pada view ber-elevation
 *     memaksa offscreen layer yang ter-clip bounds persegi = shadow
 *     rusak berbentuk KOTAK (terlihat di sekitar floating navbar).
 *
 *  3. INPUT CORRUPTION PER KARAKTER: ketik/hapus pada EditText kini
 *     meng-span REGION yang berubah (insert: [start, start+count),
 *     hapus: "seam" [start-1, start+1)) via Editable.setSpan -
 *     TANPA setText, cursor/selection/IME/focus tak tersentuh. Span
 *     dilepas terjadwal 120-180ms, tracked per-view, dibersihkan stop().
 *
 *  4. SCANLINE OVERLAY: lapisan scanline sementara via ViewOverlay
 *     (tidak menyentuh layout) - dipakai dropdown saat materialize/
 *     disintegrate, dialog, dan page transition (MAJOR).
 *
 *  5. PAGE TRANSITION REWORK: 7 fase - micro displacement -> korupsi ->
 *     kehilangan struktur -> pecah + swap (scanline menyapu) -> fragmen
 *     B menyala staggered -> rekonstruksi teks -> stabil. Elevation kartu
 *     dijaga (tidak ada kotak), logView DIKECUALIKAN dari fragmen
 *     (permukaan log selalu stabil).
 *
 *  6. SEMUA efek lewat step() yang terlacak: micro-squeeze & shadow-swap
 *     MAJOR yang dulu pakai postDelayed liar kini ikut framework
 *     (cancelFor/stop memulihkan SEMUA state).
 *
 *  7. MODE EFEK (cy9): isGlitchEnabled() = SATU fungsi pusat keputusan
 *     utk SEMUA efek (glitch + elemen hidup app). Mode: AUTO (ikut
 *     skala animator sistem - perilaku lama), ALWAYS_ON (DEFAULT -
 *     efek tetap jalan walau animator scale sistem = 0, karena SEMUA
 *     penggerak waktu efek adalah Handler/Choreographer sendiri, bukan
 *     Animator sistem), OFF (mati total; state UI tetap berubah normal).
 *     Beralih ke OFF membersihkan semua alpha/transform/shadow/overlay.
 *
 * logView 100% BERSIH (walk/collect/burst/fragmen melewatinya).
 * Aksesibilitas: mode OFF (atau AUTO saat animator sistem 0) mematikan
 * SEMUA efek dan semua jalur berakhir langsung di state final tanpa
 * alpha/transform/shadow nyangkut; mode ALWAYS_ON sengaja TIDAK membaca
 * skala animator sistem (itulah tujuannya).
 */
public final class GlitchText {

    // ---------------- hierarki intensitas ----------------
    /** Kecil: ketikan, teks dinamis, press tombol, checkbox, nav item. */
    public static final int MINOR = 0;
    /** Sedang: dropdown, dialog, config, split tunnel, panel muncul. */
    public static final int MEDIUM = 1;
    /** Besar: page navigation, state engine, rekonstruksi list besar. */
    public static final int MAJOR = 2;

    // ---------------- mode efek (cy9) ----------------

    /** Auto: ikuti skala animator sistem (aktif hanya bila > 0). */
    public static final int MODE_AUTO = 0;
    /** Selalu aktif (DEFAULT): efek jalan walau animator scale sistem 0. */
    public static final int MODE_ALWAYS_ON = 1;
    /** Mati: tanpa efek; UI tetap berfungsi & state tetap berubah. */
    public static final int MODE_OFF = 2;

    private static volatile int sMode = MODE_ALWAYS_ON;

    /** Skala animator sistem - HANYA dipakai mode MODE_AUTO. */
    private static volatile float sAnimScale = 1f;

    /**
     * Set skala animator sistem dari activity (onResume) - hanya
     * berpengaruh pada mode MODE_AUTO ("ikuti sistem").
     */
    public static void setAnimScale(float scale) { sAnimScale = scale; }

    /**
     * Mode efek: MODE_AUTO / MODE_ALWAYS_ON / MODE_OFF. Beralih ke OFF
     * di tengah efek membersihkan SEMUA state visual (alpha/transform/
     * shadow/overlay) - tidak ada yang tertinggal. Idempotent.
     */
    public static void setMode(int mode) {
        int m = mode == MODE_AUTO || mode == MODE_OFF ? mode : MODE_ALWAYS_ON;
        if (sMode == m) return;
        sMode = m;
        if (m == MODE_OFF) purgeEffects();
    }

    public static int getMode() { return sMode; }

    /**
     * SATU FUNGSI PUSAT yang menentukan aktif/tidaknya SEMUA efek
     * (GlitchText, GlitchDropdown, elemen hidup MainActivity):
     *  - MODE_OFF       -> false (UI tetap normal, state tetap berubah,
     *    glitch hanya lapisan visual - tidak pernah memblokir input)
     *  - MODE_ALWAYS_ON -> true WALAU skala animator sistem = 0: semua
     *    penggerak waktu efek adalah Handler/Choreographer sendiri
     *    (SelfAnim), bukan Animator sistem, jadi "hapus animasi" sistem
     *    tidak mematikannya
     *  - MODE_AUTO      -> ikuti sistem (skala animator > 0 = aktif)
     */
    public static boolean isGlitchEnabled() {
        if (!running || sMode == MODE_OFF) return false;
        if (sMode == MODE_ALWAYS_ON) return true;
        return sAnimScale > 0f;
    }

    /** Random int [0,bound) utk komponen pendamping (pulse dropdown). */
    public static int rndInt(int bound) { return RND.nextInt(bound); }

    // ---------------- event priority ----------------
    /** uptimeMillis glitch event terakhir; ambient menunggu 1.5 dtk. */
    private static volatile long sLastEvent = 0;
    private static final long AMBIENT_COOLDOWN_MS = 1500;

    /** Tandai glitch EVENT baru - ambient wander akan dijeda. */
    private static void markEvent() { sLastEvent = SystemClock.uptimeMillis(); }

    /** Satu entri registry: ref lemah + baseline shadow untuk restore. */
    private static final class Node {
        final WeakReference<TextView> ref;
        final boolean hot;       // judul bold / teks utama -> lebih sering
        final boolean spannable; // false utk input & logView
        final boolean input;     // true = EditText (korupsi region saat ketik)
        final boolean hasShadow; // baseline shadow ada (false = EditText)
        final float baseRadius, baseDx, baseDy;
        final int baseShadowColor;
        long lastBurst;          // throttle watcher (uptimeMillis)

        Node(WeakReference<TextView> ref, boolean hot, boolean spannable,
             boolean input, boolean hasShadow,
             float baseRadius, float baseDx, float baseDy, int baseShadowColor) {
            this.ref = ref;
            this.hot = hot;
            this.spannable = spannable;
            this.input = input;
            this.hasShadow = hasShadow;
            this.baseRadius = baseRadius;
            this.baseDx = baseDx;
            this.baseDy = baseDy;
            this.baseShadowColor = baseShadowColor;
        }
    }

    private static final ArrayList<Node> NODES = new ArrayList<>();
    /** TextView yang sedang kilat span -> teks dasar utk restore. */
    private static final IdentityHashMap<TextView, CharSequence> PENDING =
            new IdentityHashMap<>();
    /** TextView yang sedang kilat shadow -> node baseline-nya. */
    private static final IdentityHashMap<TextView, Node> SHADOWED =
            new IdentityHashMap<>();
    /** Span korupsi sementara pada EditText yang sedang diketik. */
    private static final WeakHashMap<EditText, ArrayList<Object>> INPUT_SPANS =
            new WeakHashMap<>();
    /** Runnable pelepas span input (dibatalkan saat span baru dipasang). */
    private static final WeakHashMap<EditText, Runnable> INPUT_CLEAR =
            new WeakHashMap<>();
    /** Scanline overlay aktif per host (dilepas terjadwal / clearScanline). */
    private static final WeakHashMap<View, Drawable> SCANLINES =
            new WeakHashMap<>();
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Random RND = new Random();

    private static int[] PALETTE;          // warna neon dari resource
    private static int THIN_SHADOW;        // ghost baseline red 20%
    private static int BURST_SHADOW_RED;   // kilat kanan (sisi R)
    private static int BURST_SHADOW_CYAN;  // kilat kiri (sisi C)
    private static int BLOCK_BG;           // blok datamosh acid transparan
    private static int SCAN_COLOR;         // garis scanline (cyan 30%)
    private static float DENSITY;
    private static boolean running;
    private static boolean tickQueued;
    private static boolean restoreQueued;
    /** Guard reentrant: setText dari burst/restore TIDAK boleh memicu
     *  watcher lagi (TextWatcher onTextChanged -> burst -> setText ...). */
    private static boolean sApplying;

    // ---------------- cleanup framework (cy7, diperluas cy8) ----------------
    /** Baseline view yang sedang berefek:
     *  {alpha, translationX, scaleX, ELEVATION} - elevation ikut
     *  dipulihkan (guard artifact kotak pada view ber-elevation). */
    private static final WeakHashMap<View, float[]> BASE = new WeakHashMap<>();
    /** Langkah tertunda per view (bisa dibatalkan per view). */
    private static final WeakHashMap<View, ArrayList<Runnable>> POSTED =
            new WeakHashMap<>();

    private GlitchText() {}

    /** Ambil warna dari resource sekali (idempotent, dipanggil start()). */
    public static void init(Context ctx) {
        if (PALETTE != null) return;
        DENSITY = ctx.getResources().getDisplayMetrics().density;
        PALETTE = new int[]{
                ctx.getColor(R.color.m3_primary),    // cyan neon
                ctx.getColor(R.color.m3_tertiary),   // magenta neon
                ctx.getColor(R.color.status_amber),  // acid #D4FF3F
                ctx.getColor(R.color.status_red)     // glitch red
        };
        THIN_SHADOW = ctx.getColor(R.color.glitch_shadow_thin);
        BURST_SHADOW_RED = ctx.getColor(R.color.glitch_shadow);
        BURST_SHADOW_CYAN = ctx.getColor(R.color.glitch_shadow_cyan);
        BLOCK_BG = ctx.getColor(R.color.glitch_block);
        SCAN_COLOR = ctx.getColor(R.color.glitch_scan);
    }

    // ---------------- registry ----------------

    /**
     * Daftarkan TextView dengan ghost shadow KUSTOM (statusBar merah
     * dx 3dp, monGo cyan dx -2dp) - baseline ini yang dipulihkan saat
     * restore, bukan ghost tipis standar. Idempotent per referensi.
     */
    public static void registerCustom(TextView tv, float dxDp, int shadowColor) {
        if (tv == null || findNode(tv) != null) return;
        NODES.add(new Node(new WeakReference<>(tv), true, true, false, true,
                1f * DENSITY, dxDp * DENSITY, 0f, shadowColor));
    }

    /**
     * Traverse pohon view: SEMUA TextView jadi anggota registry. EditText
     * masuk mode INPUT (korupsi region saat ketik - cy8: span per karakter
     * pada region yang berubah, cursor/IME aman), logView dilewati BERSIH.
     * Panggil ulang aman (dedup per referensi).
     */
    public static void registerTree(View root) {
        if (root != null) walk(root);
    }

    /**
     * Pasang glitch touch di SELURUH tree (cy8):
     *  - Spinner: sentuh = buka GlitchDropdown (popup custom) - dropdown
     *    materialize/disintegrate via glitch, BUKAN popup platform.
     *    FIX: cabang Spinner dicek SEBELUM ViewGroup (Spinner ADALAH
     *    ViewGroup - dulu cabang ini dead code, popup platform biasa
     *    yang terbuka).
     *  - Button/CheckBox: press = glitchNow MINOR + micro jitter
     *    (feedback tepat sasaran pada tombol yang ditekan).
     * Panggil ulang aman (listener menimpa dirinya sendiri).
     */
    public static void installTouch(View root) {
        if (root == null) return;
        // FIX cy8: Spinner HARUS dicek sebelum ViewGroup (Spinner
        // extends AbsSpinner extends AdapterView extends ViewGroup).
        if (root instanceof Spinner) {
            final Spinner sp = (Spinner) root;
            sp.setOnTouchListener((v, ev) -> {
                int a = ev.getActionMasked();
                if (a == MotionEvent.ACTION_DOWN) {
                    // press feedback pada spinner itu sendiri
                    glitchJitter(sp, MINOR);
                } else if (a == MotionEvent.ACTION_UP
                        && ev.getX() >= 0 && ev.getX() <= v.getWidth()
                        && ev.getY() >= 0 && ev.getY() <= v.getHeight()) {
                    GlitchDropdown.show(sp);
                }
                return true; // konsumsi: popup platform tidak dibuka
            });
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++)
                installTouch(g.getChildAt(i));
            return;
        }
        if (root instanceof Button) {
            TextView b = (TextView) root;
            b.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    // cy8: press = korupsi teks tombol + micro displacement
                    // (ripple tetap jalan - listener tidak konsumsi).
                    glitchNow((TextView) v, MINOR);
                    glitchJitter(v, MINOR);
                }
                return false;
            });
        }
    }

    private static void walk(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (findNode(tv) != null) return; // dedup

            // logView dibersihkan total - kejelasan log > estetika
            if (tv.getId() == R.id.logView) return;

            if (tv instanceof EditText) {
                // INPUT: korupsi span region yang berubah saat ketik/hapus
                // (cursor & IME aman - span tidak mengubah isi text).
                Node n = new Node(new WeakReference<>(tv), false, false,
                        true, false, 0f, 0f, 0f, 0);
                NODES.add(n);
                tv.addTextChangedListener(new GlitchWatcher(n));
                return;
            }

            float d = DENSITY;
            // Baseline ghost tipis di SEMUA TextView (misregister permanen).
            tv.setShadowLayer(1f * d, 1f * d, 0f, THIN_SHADOW);
            boolean hot = tv.getTypeface() != null && tv.getTypeface().isBold();
            Node n = new Node(new WeakReference<>(tv), hot, true, false,
                    true, 1f * d, 1f * d, 0f, THIN_SHADOW);
            NODES.add(n);
            // teks berubah (status, counter, hint, ...) = glitch MINOR
            // pada REGION yang berubah (lihat GlitchWatcher).
            tv.addTextChangedListener(new GlitchWatcher(n));
            return; // TextView tidak punya anak view
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
        }
    }

    // ---------------- step framework (semua efek lewat sini) ----------------

    /**
     * Jadwalkan satu langkah efek pada view. Langkah pertama utk sebuah
     * view mencatat baseline {alpha, translationX, scaleX, ELEVATION} -
     * dijamin dipulihkan oleh cancelFor/stop/restoreAllBase/stepFinish.
     * Panggilan ini harus SEBELUM mutasi pertama agar baseline murni.
     */
    private static void step(View v, long delay, Runnable r) {
        captureBase(v);
        ArrayList<Runnable> list = POSTED.get(v);
        if (list == null) {
            list = new ArrayList<>();
            POSTED.put(v, list);
        }
        Runnable wrap = new Runnable() {
            @Override public void run() {
                ArrayList<Runnable> l = POSTED.get(v);
                if (l != null) l.remove(this);
                // Langkah yang sudah terjadwal SELALU berjalan sampai
                // nilai finalnya - rantai efek berakhir deterministik di
                // baseline (alpha 1f, translation 0) walau fx dimatikan
                // di tengah jalan; tidak ada state stuck.
                r.run();
            }
        };
        list.add(wrap);
        H.postDelayed(wrap, delay);
    }

    private static void captureBase(View v) {
        if (BASE.containsKey(v)) return;
        BASE.put(v, new float[]{v.getAlpha(), v.getTranslationX(),
                v.getScaleX(), v.getElevation()});
    }

    /**
     * Langkah penutup efek transform: pulihkan alpha/translationX/scaleX/
     * ELEVATION dari baseline lalu bersihkan entri. Semua efek alpha-based
     * WAJIB berakhir di sini (deterministik, anti state nyangkut).
     */
    private static void stepFinish(View v, long delay) {
        step(v, delay, () -> {
            float[] b = BASE.remove(v);
            v.setAlpha(b != null ? b[0] : 1f);
            v.setTranslationX(b != null ? b[1] : v.getTranslationX());
            v.setScaleX(b != null ? b[2] : 1f);
            if (b != null) v.setElevation(b[3]);
        });
    }

    /**
     * Guard artifact KOTAK (cy8): panggil SEBELUM mutasi alpha pertama.
     * View ber-elevation yang di-alpha < 1 dipaksa ke offscreen layer -
     * shadow-nya ter-clip bounds persegi (kotak di sekitar pill nav/HUD).
     * Elevation dinolkan selama efek; stepFinish/cancelFor/stop
     * memulihkannya dari BASE.
     */
    private static void guardElevation(View v) {
        captureBase(v);
        if (v.getElevation() > 0f) v.setElevation(0f);
    }

    /** Batalkan SEMUA langkah tertunda satu view + pulihkan baseline. */
    public static void cancelFor(View v) {
        if (v == null) return;
        ArrayList<Runnable> list = POSTED.remove(v);
        if (list != null) {
            for (Runnable r : list) H.removeCallbacks(r);
        }
        float[] b = BASE.remove(v);
        if (b != null) {
            v.setAlpha(b[0]);
            v.setTranslationX(b[1]);
            v.setScaleX(b[2]);
            v.setElevation(b[3]);
        }
    }

    /** Pulihkan baseline SEMUA view yang berefek (stop / aksesibilitas). */
    private static void restoreAllBase() {
        for (Map.Entry<View, float[]> e : BASE.entrySet()) {
            View v = e.getKey();
            float[] b = e.getValue();
            if (v == null || b == null) continue;
            v.setAlpha(b[0]);
            v.setTranslationX(b[1]);
            v.setScaleX(b[2]);
            v.setElevation(b[3]);
        }
        BASE.clear();
        POSTED.clear();
    }

    // ---------------- EVENT API ----------------

    /** Glitch sekali pada satu TextView (level MEDIUM). */
    public static void glitchNow(TextView tv) {
        glitchNow(tv, MEDIUM);
    }

    /** Glitch sekali pada satu TextView dengan level intensitas. */
    public static void glitchNow(TextView tv, int level) {
        if (tv == null || !isGlitchEnabled()) return;
        markEvent();
        Node n = findNode(tv);
        if (n != null) {
            burst(n, level);
        } else {
            // Belum terdaftar (mis. view dinamis): burst ad-hoc span saja.
            CharSequence cur = tv.getText();
            if (cur == null || cur.length() == 0) return;
            SpannableString sp = new SpannableString(cur);
            int start = RND.nextInt(cur.length());
            int end = Math.min(cur.length(), start + 1 + RND.nextInt(2));
            sp.setSpan(new ForegroundColorSpan(
                            PALETTE[RND.nextInt(PALETTE.length)]),
                    start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sApplying = true;
            tv.setText(sp);
            sApplying = false;
            PENDING.put(tv, cur);
            float d = DENSITY;
            tv.setShadowLayer(1.2f * d, 2f * d, 0f, BURST_SHADOW_RED);
            SHADOWED.put(tv, new Node(new WeakReference<>(tv), false, true,
                    false, false, 1.2f * d, 2f * d, 0f, BURST_SHADOW_RED));
        }
        scheduleRestore(restoreFor(level));
    }

    /** Glitch SEKALI pada semua TextView di bawah root (level MEDIUM). */
    public static void glitchTree(View root) {
        glitchTree(root, MEDIUM);
    }

    /**
     * Glitch SEKALI pada semua TextView terdaftar di bawah root dengan
     * level. View GONE/skorup dilewati. TARGET EKSPLISIT: pemanggil yang
     * menentukan root sekecil mungkin (elemen yang berubah, bukan layar).
     */
    public static void glitchTree(View root, int level) {
        if (root == null || !isGlitchEnabled()) return;
        markEvent();
        ArrayList<Node> targets = new ArrayList<>();
        collect(root, targets);
        for (Node n : targets) burst(n, level);
        if (!targets.isEmpty()) scheduleRestore(restoreFor(level));
    }

    /**
     * Glitch MAJOR pada sebuah region: burst MAJOR semua teks terdaftar +
     * displacement container (translationX SAJA - tidak menyentuh alpha,
     * aman utk kartu ber-elevation). Hanya utk perubahan BESAR (state
     * engine, rekonstruksi list) - BUKAN event kecil.
     */
    public static void glitchMajor(View root) {
        if (root == null || !isGlitchEnabled()) return;
        glitchTree(root, MAJOR);
        glitchJitter(root, MAJOR);
    }

    /**
     * Flicker alpha + displacement pada elemen non-teks / container.
     * GUARD ELEVATION (cy8): elevation view dinolkan selama flicker lalu
     * dipulihkan stepFinish - view ber-elevation (HUD, kartu) tidak lagi
     * meninggalkan artifact kotak. Rantai berakhir PASTI di baseline.
     */
    public static void glitchView(View v, int level) {
        if (v == null || !isGlitchEnabled()) return;
        markEvent();
        v.animate().cancel();
        guardElevation(v);
        long t = 0;
        float[] seq;
        switch (level) {
            case MAJOR:
                seq = new float[]{0.1f, 0.85f, 0.25f, 0.9f, 0.4f};
                break;
            case MEDIUM:
                seq = new float[]{0.2f, 0.8f, 0.35f};
                break;
            default:
                seq = new float[]{0.35f, 0.8f};
        }
        for (float a : seq) {
            t += 38 + RND.nextInt(22);
            final float alpha = a;
            step(v, t, () -> v.setAlpha(alpha));
        }
        t += 45;
        stepFinish(v, t);
        glitchJitter(v, level);
    }

    /**
     * Displacement murni (translationX) - TIDAK memaksa layer, TIDAK
     * menyentuh alpha: aman utk view ber-elevation (pill nav). Rantai
     * deterministik: langkah terakhir SELALU mengembalikan translationX
     * baseline (tidak ada layout shift, tidak ada state nyangkut).
     */
    public static void glitchJitter(View v, int level) {
        if (v == null || !isGlitchEnabled()) return;
        markEvent();
        float d = DENSITY;
        float amp = level == MAJOR ? 3.5f : level == MEDIUM ? 2f : 1f;
        final float ox = v.getTranslationX();
        float[] seq = {amp, -amp * 0.7f, amp * 0.45f, -amp * 0.2f};
        int n = level == MINOR ? 2 : level == MEDIUM ? 3 : seq.length;
        long t = 0;
        for (int i = 0; i < n; i++) {
            t += 32 + RND.nextInt(26);
            final float off = seq[i] * d;
            step(v, t, () -> v.setTranslationX(ox + off));
        }
        step(v, t + 45, () -> {
            // pulihkan dari baseline bila ada (lebih tahan terhadap
            // efek yang saling tumpang tindih), selain itu posisi awal.
            float[] b = BASE.get(v);
            v.setTranslationX(b != null ? b[1] : ox);
        });
    }

    /**
     * "Muncul karena glitch": flicker alpha + squeeze-in + jitter + burst
     * MEDIUM subtree - dipakai saat row config/chip sesi/HUD baru dirender.
     * GUARD ELEVATION: HUD (elevation 8dp) muncul tanpa artifact kotak.
     * Berakhir PASTI di alpha/scale/elevation baseline.
     */
    public static void glitchAppear(View v) {
        if (v == null || !isGlitchEnabled()) return;
        markEvent();
        glitchTree(v, MEDIUM);
        v.animate().cancel();
        guardElevation(v);
        v.setAlpha(0f);
        v.setScaleX(0.97f);
        step(v, 40,  () -> v.setAlpha(0.9f));
        step(v, 85,  () -> v.setAlpha(0.15f));
        step(v, 130, () -> v.setAlpha(0.75f));
        step(v, 170, () -> v.setAlpha(0.3f));
        stepFinish(v, 215);
        glitchJitter(v, MEDIUM);
    }

    /**
     * "Hilang karena glitch": flicker alpha turun + jitter MINOR. Pemanggil
     * yang menyembunyikan elemen SETELAH efek (postDelayed + guard kondisi
     * terbaru) - API ini sendiri tidak mengubah visibility.
     */
    public static void glitchDisappear(View v) {
        if (v == null || !isGlitchEnabled()) return;
        markEvent();
        glitchTree(v, MINOR);
        v.animate().cancel();
        guardElevation(v);
        v.setAlpha(0.25f);
        step(v, 35,  () -> v.setAlpha(0.8f));
        step(v, 70,  () -> v.setAlpha(0.1f));
        step(v, 105, () -> v.setAlpha(0f));
        glitchJitter(v, MINOR);
    }

    /**
     * State elemen berubah (enabled/disabled tombol, dst) - elemen itu
     * sendiri korupsi singkat lalu merekonstruksi ke state barunya.
     * Dipanggil SEBELUM setEnabled agar urutan visualnya
     * corrupt -> state baru (bukan sebaliknya).
     */
    public static void glitchStateChange(View v, boolean enabled) {
        if (v == null || !isGlitchEnabled()) return;
        markEvent();
        glitchView(v, enabled ? MEDIUM : MINOR);
        if (v instanceof TextView) {
            glitchNow((TextView) v, enabled ? MEDIUM : MINOR);
        }
        glitchJitter(v, MINOR);
    }

    // ---------------- staggered fragment (dropdown & page) ----------------

    /**
     * Materialize staggered utk ANAK-ANAK container (item dropdown /
     * baris dialog): tiap anak flicker-up + displacement mikro bergeser
     * waktu 16ms - kesan "fragment berkumpul membentuk elemen".
     * Elevation tiap anak dijaga (guard kotak).
     */
    public static void materializeStaggered(ViewGroup g) {
        if (g == null || !isGlitchEnabled()) return;
        markEvent();
        for (int i = 0; i < g.getChildCount(); i++) {
            final View c = g.getChildAt(i);
            final int d = i * 16;
            guardElevation(c);
            step(c, d,      () -> c.setAlpha(0.15f));
            step(c, d + 26, () -> c.setAlpha(0.7f));
            step(c, d + 52, () -> c.setAlpha(0.3f));
            stepFinish(c, d + 78);
            if (i % 2 == 0) {
                step(c, d + 10, () -> c.setTranslationX(3f * DENSITY));
                step(c, d + 40, () -> c.setTranslationX(-2f * DENSITY));
            }
        }
    }

    /**
     * Vanish staggered utk ANAK-ANAK container: flicker-down bergeser -
     * kesan "element pecah jadi fragment lalu hilang".
     */
    public static void vanishStaggered(ViewGroup g) {
        if (g == null || !isGlitchEnabled()) return;
        markEvent();
        for (int i = 0; i < g.getChildCount(); i++) {
            final View c = g.getChildAt(i);
            final int d = i * 12;
            guardElevation(c);
            step(c, d,      () -> c.setAlpha(0.5f));
            step(c, d + 24, () -> c.setAlpha(0.9f));
            step(c, d + 48, () -> c.setAlpha(0f));
        }
    }

    /**
     * Satu denyut mikro pada SATU anak acak container yang terlihat -
     * dipakai GlitchDropdown sbg "pulse" berkala selama dropdown terbuka
     * (dropdown terasa unstable tanpa mengganggu pemilihan).
     * Mengembalikan true bila ada anak yang dikenai efek.
     */
    public static boolean dropdownPulse(ViewGroup g) {
        if (g == null || !isGlitchEnabled()) return false;
        ArrayList<View> vis = new ArrayList<>();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE) vis.add(c);
        }
        if (vis.isEmpty()) return false;
        View target = vis.get(RND.nextInt(vis.size()));
        glitchView(target, MINOR);
        glitchJitter(target, MINOR); // micro displacement: "unstable"
        if (target instanceof ViewGroup) {
            // satu TextView di dalam item ikut korupsi (span neon kecil)
            ArrayList<TextView> tvs = new ArrayList<>();
            collectTvs(target, tvs);
            if (!tvs.isEmpty()) {
                burst(findNode(tvs.get(0)) != null
                        ? findNode(tvs.get(0))
                        : new Node(new WeakReference<>(tvs.get(0)),
                                false, true, false, false,
                                0f, 0f, 0f, 0), MINOR);
                scheduleRestore(restoreFor(MINOR));
            }
        }
        return true;
    }

    // ---------------- scanline overlay (cy8) ----------------

    /**
     * Lapisan scanline sementara DI ATAS host via ViewOverlay - tidak
     * menyentuh layout, tidak mengganggu input, tidak meninggalkan apa
     * pun setelah dilepas. Dipakai dropdown (materialize/disintegrate),
     * dialog, dan page transition (MAJOR). Idempotent per host;
     * guarantee: terlepas setelah durationMs ATAU clearScanline/stop().
     */
    public static void scanline(View host, int durationMs) {
        scanline(host, durationMs, 100);
    }

    /** Scanline dengan alpha garis khusus (0-255). */
    public static void scanline(View host, int durationMs, int lineAlpha) {
        if (host == null || !isGlitchEnabled()) return;
        clearScanline(host); // idempotent - tidak menumpuk
        int w = host.getWidth();
        int h = host.getHeight();
        if (w <= 0 || h <= 0) return; // belum layout: lewati diam-diam
        Bitmap b = Bitmap.createBitmap(4, 3, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint();
        p.setColor(SCAN_COLOR);
        c.drawRect(0f, 0f, 4f, 1f, p);
        BitmapDrawable d = new BitmapDrawable(host.getResources(), b);
        d.setTileModeXY(Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        d.setAlpha(Math.max(0, Math.min(255, lineAlpha)));
        d.setBounds(0, 0, w, h);
        try {
            host.getOverlay().add(d);
        } catch (Exception ignored) {
            return; // overlay tidak didukung: efek dilewati, bukan crash
        }
        SCANLINES.put(host, d);
        H.postDelayed(() -> clearScanline(host), durationMs);
    }

    /** Lepas scanline host (idempotent, aman utk host yang sudah mati). */
    public static void clearScanline(View host) {
        if (host == null) return;
        Drawable d = SCANLINES.remove(host);
        if (d == null) return;
        try {
            host.getOverlay().remove(d);
        } catch (Exception ignored) {}
    }

    // ---------------- page transition (cy7, rework cy8) ----------------

    /** Fragment halaman = anak-anak konten scroll (bukan scroll itu).
     *  logView & logScroll DIKECUALIKAN - permukaan log selalu stabil
     *  & readable (permintaan eksplisit: log 100% bebas glitch). */
    private static List<View> fragments(View page) {
        List<View> out = new ArrayList<>();
        if (page instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) page;
            if (g.getChildCount() == 1 && g.getChildAt(0) instanceof ViewGroup) {
                ViewGroup inner = (ViewGroup) g.getChildAt(0);
                for (int i = 0; i < inner.getChildCount(); i++) {
                    View c = inner.getChildAt(i);
                    if (isLogSurface(c)) continue; // log stabil
                    out.add(c);
                }
            } else {
                for (int i = 0; i < g.getChildCount(); i++) {
                    View c = g.getChildAt(i);
                    if (isLogSurface(c)) continue; // log stabil
                    out.add(c);
                }
            }
        }
        return out;
    }

    /** Halaman Log: logScroll (berisi logView) tidak ikut transisi. */
    private static boolean isLogSurface(View v) {
        int id = v.getId();
        return id == R.id.logView || id == R.id.logScroll;
    }

    /** Generation counter transisi - transisi baru membatalkan yang lama. */
    private static long sPageGen = 0;

    /** Persiapan fragmen utk fase alpha: baseline + guard elevation. */
    private static void prepareFrag(View f) {
        guardElevation(f);
    }

    /** Persiapan fragmen gelap (page masuk): baseline + elev 0 + alpha 0. */
    private static void prepareFragDark(View f) {
        guardElevation(f);
        f.setAlpha(0f);
    }

    /**
     * Paksa fragmen ke state final bersih: alpha 1, translation 0,
     * scale 1, elevation baseline, langkah tertunda dibatalkan.
     */
    private static void finishFrag(View f) {
        float[] b = BASE.remove(f);
        f.setAlpha(1f);
        f.setTranslationX(0f);
        f.setScaleX(1f);
        if (b != null) f.setElevation(b[3]);
        ArrayList<Runnable> list = POSTED.remove(f);
        if (list != null) {
            for (Runnable r : list) H.removeCallbacks(r);
        }
    }

    private static void resetFrags(List<View> frags) {
        for (View f : frags) finishFrag(f);
    }

    /**
     * Transisi halaman "A terkorosi sampai menjadi B" (rework cy8) -
     * BUKAN slide, BUKAN fade, BUKAN shake kecil:
     *
     *   FASE 1  0-90ms    A mulai rusak: micro displacement per fragmen
     *                      + korupsi teks MAJOR serentak.
     *   FASE 2  90-200ms  bagian A kehilangan struktur: alpha jatuh
     *                      staggered + displacement membesar.
     *   FASE 3  200-280ms A pecah total (alpha nyaris 0) -> swap:
     *                      A GONE, state fragmennya di-reset bersih.
     *   FASE 4  280-300ms dead-air: scanline menyapu area konten,
     *                      fragmen B disiapkan gelap.
     *   FASE 5  300-470ms fragmen B menyala staggered dari kegelapan
     *                      (flicker + displacement bergantian arah).
     *   FASE 6  470-560ms rekonstruksi: teks B terkorupsi lalu pulih.
     *   FASE 7  settle    B stabil: alpha/translation/elevation normal,
     *                      scanline dilepas, tidak ada sisa apa pun.
     *
     * Kartu ber-elevation dijaga (tidak ada kotak); logView dikecualikan
     * (log selalu stabil). CEPAT BERGANTI TAB: generation counter -
     * langkah gen lama no-op, state visibility sudah diatur pemanggil.
     * animScale 0 -> langsung final tanpa efek dan tanpa sisa state.
     */
    public static void pageTransition(View out, View in) {
        if (in == null) return;
        if (out == in || out == null) {
            resetFrags(fragments(in));
            return;
        }
        // Status visibility: in sudah VISIBLE oleh pemanggil; out masih
        // VISIBLE - penyembunyiannya MILIK transisi ini (setelah fase
        // korupsi). Di luar efek, hanya jamin state final bersih.
        if (!isGlitchEnabled()) {
            out.setVisibility(View.GONE);
            resetFrags(fragments(out));
            resetFrags(fragments(in));
            return;
        }
        markEvent();
        final long gen = ++sPageGen;
        final List<View> outFrags = fragments(out);
        final List<View> inFrags = fragments(in);
        // Host scanline = parent konten (melayang di atas kedua halaman);
        // transisi baru membatalkan overlay transisi lama.
        final ViewGroup host = in.getParent() instanceof ViewGroup
                ? (ViewGroup) in.getParent() : null;
        if (host != null) clearScanline(host);

        // FASE 1 - A mulai rusak: micro displacement per fragmen.
        glitchTree(out, MAJOR);
        int i = 0;
        for (View f : outFrags) {
            final View ff = f;
            final int d = i * 15;
            step(ff, d,      () -> { if (sPageGen == gen)
                    ff.setTranslationX(2f * DENSITY); });
            step(ff, d + 45, () -> { if (sPageGen == gen)
                    ff.setTranslationX(-1.5f * DENSITY); });
            i++;
        }

        // FASE 2 - A kehilangan struktur: alpha jatuh staggered +
        // displacement membesar (elevation dinolkan dulu: anti kotak).
        i = 0;
        for (View f : outFrags) {
            final View ff = f;
            prepareFrag(ff);
            final int d = 90 + i * 18;
            step(ff, d,      () -> { if (sPageGen == gen) {
                ff.setAlpha(0.55f);
                ff.setTranslationX(3.5f * DENSITY);
            }});
            step(ff, d + 40, () -> { if (sPageGen == gen) {
                ff.setAlpha(0.2f);
                ff.setTranslationX(-2.5f * DENSITY);
            }});
            step(ff, d + 80, () -> { if (sPageGen == gen)
                    ff.setAlpha(0.65f); });
            i++;
        }

        // FASE 3 - A pecah total: korupsi pamungkas lalu swap.
        step(out, 205, () -> { if (sPageGen == gen)
                glitchTree(out, MAJOR); });
        i = 0;
        for (View f : outFrags) {
            final View ff = f;
            final int d = 200 + i * 14;
            step(ff, d, () -> { if (sPageGen == gen) {
                ff.setAlpha(0.1f);
                ff.setTranslationX(5f * DENSITY);
            }});
            i++;
        }
        step(out, 280, () -> {
            if (sPageGen != gen) return;
            out.setVisibility(View.GONE);
            for (View f : outFrags) finishFrag(f);
        });

        // FASE 4 - dead-air: scanline menyapu, B disiapkan gelap.
        if (host != null) {
            final ViewGroup h = host;
            step(in, 285, () -> { if (sPageGen == gen)
                    scanline(h, 250, 120); });
        }
        for (View f : inFrags) prepareFragDark(f);

        // FASE 5 - fragmen B menyala staggered dari kegelapan.
        int j = 0;
        for (View f : inFrags) {
            final View ff = f;
            final int d = 300 + j * 26;
            step(ff, d,       () -> { if (sPageGen == gen) {
                ff.setAlpha(0.4f);
                ff.setTranslationX(3f * DENSITY);
            }});
            step(ff, d + 45,  () -> { if (sPageGen == gen) {
                ff.setAlpha(0.85f);
                ff.setTranslationX(-2f * DENSITY);
            }});
            step(ff, d + 90,  () -> { if (sPageGen == gen) {
                ff.setAlpha(0.25f);
                ff.setTranslationX(1.5f * DENSITY);
            }});
            step(ff, d + 130, () -> { if (sPageGen == gen) {
                ff.setAlpha(1f);
                ff.setTranslationX(0f);
            }});
            j++;
        }

        // FASE 6 - rekonstruksi: teks B terkorupsi lalu pulih sendiri.
        step(in, 470, () -> { if (sPageGen == gen)
                glitchTree(in, MAJOR); });

        // FASE 7 - B stabil: semua kembali normal, scanline dilepas.
        final long settleT = 300 + Math.max(0, j - 1) * 26 + 130 + 50;
        step(in, settleT, () -> {
            if (sPageGen != gen) return;
            for (View f : inFrags) finishFrag(f);
            if (host != null) clearScanline(host);
        });
    }

    // ---------------- traversal & restore ----------------

    private static void collect(View v, ArrayList<Node> out) {
        if (v.getVisibility() != View.VISIBLE) return; // halaman GONE: lewati
        if (v instanceof TextView) {
            Node n = findNode((TextView) v);
            if (n != null) out.add(n);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
                collect(g.getChildAt(i), out);
        }
    }

    private static void collectTvs(View v, ArrayList<TextView> out) {
        if (v.getVisibility() != View.VISIBLE) return;
        if (v instanceof TextView) {
            out.add((TextView) v);
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++)
                collectTvs(g.getChildAt(i), out);
        }
    }

    /** Durasi restore per level (MAJOR tampil sedikit lebih lama). */
    private static long restoreFor(int level) {
        if (level == MAJOR) return 170 + RND.nextInt(90);
        if (level == MEDIUM) return 140 + RND.nextInt(70);
        return 110 + RND.nextInt(60);
    }

    // ---------------- WANDER LOOP (ambien, prioritas RENDAH) ----------------

    /** Mulai loop wander (panggil di onResume). Idempotent. */
    public static void start(Context ctx) {
        init(ctx);
        purge();
        if (running) return;
        running = true;
        scheduleTick(500 + RND.nextInt(500));
    }

    /** Hentikan loop + bersihkan SEMUA efek & transform (onPause). */
    public static void stop() {
        running = false;
        H.removeCallbacksAndMessages(null);
        tickQueued = false;
        restoreQueued = false;
        POSTED.clear();
        // scanline overlay: pastikan tidak ada lapisan tertinggal
        if (!SCANLINES.isEmpty()) {
            ArrayList<View> hosts = new ArrayList<>(SCANLINES.keySet());
            for (View v : hosts) clearScanline(v);
        }
        // span korupsi input: lepas semua dari EditText aktif
        if (!INPUT_SPANS.isEmpty()) {
            ArrayList<EditText> ets = new ArrayList<>(INPUT_SPANS.keySet());
            for (EditText et : ets) clearInputSpans(et, true);
        }
        INPUT_CLEAR.clear();
        restoreNow();
        restoreAllBase();
    }

    /**
     * Mode berubah ke OFF di tengah efek berjalan (cy9): batalkan semua
     * langkah tertunda + pulihkan baseline + lepas overlay/span kilat -
     * deterministik, TIDAK ADA alpha/transform/shadow/overlay yang
     * tertinggal. Berbeda dari stop(): loop wander (TICK) tetap hidup,
     * hanya berhenti berefek (cek isGlitchEnabled di dalamnya) sehingga
     * mode bisa diaktifkan lagi tanpa onResume ulang.
     */
    private static void purgeEffects() {
        // 1. hapus langkah tertunda dari handler SEBELUM restore
        //    (jangan hanya bersihkan map - runnable yang masih antre
        //    bisa memutasikan view SETELAH restore -> state nyangkut).
        try {
            for (ArrayList<Runnable> list : POSTED.values()) {
                if (list == null) continue;
                for (Runnable r : list) H.removeCallbacks(r);
            }
        } catch (Exception ignored) {}
        // 2. pulihkan {alpha, translationX, scaleX, ELEVATION} baseline
        restoreAllBase();
        // 3. scanline overlay (ViewOverlay) dilepas
        try {
            if (!SCANLINES.isEmpty()) {
                ArrayList<View> hosts = new ArrayList<>(SCANLINES.keySet());
                for (View v : hosts) clearScanline(v);
            }
        } catch (Exception ignored) {}
        // 4. span korupsi input dilepas dari EditText aktif
        try {
            if (!INPUT_SPANS.isEmpty()) {
                ArrayList<EditText> ets = new ArrayList<>(INPUT_SPANS.keySet());
                for (EditText et : ets) clearInputSpans(et, true);
            }
        } catch (Exception ignored) {}
        // 5. kilatan teks (span) & shadow RGB dipulihkan
        restoreNow();
    }

    private static void scheduleTick(long delay) {
        if (tickQueued) return;
        tickQueued = true;
        H.postDelayed(TICK, delay);
    }

    private static void scheduleRestore(long delay) {
        if (restoreQueued) return;
        restoreQueued = true;
        H.postDelayed(RESTORE, delay);
    }

    private static final Runnable TICK = new Runnable() {
        @Override public void run() {
            tickQueued = false;
            if (!running) return;
            purge();
            // ambient DIJEDA setelah event glitch - event adalah
            // bintangnya, ambient hanya atmosfer (1 target / 950-1500ms).
            long now = SystemClock.uptimeMillis();
            if (now - sLastEvent >= AMBIENT_COOLDOWN_MS && isGlitchEnabled()) {
                ArrayList<Node> alive = new ArrayList<>();
                ArrayList<Node> aliveHot = new ArrayList<>();
                for (Node n : NODES) {
                    TextView tv = n.ref.get();
                    if (tv == null || n.input) continue;
                    alive.add(n);
                    if (n.hot) aliveHot.add(n);
                }
                if (!alive.isEmpty()) {
                    Node n = pick(alive, aliveHot);
                    if (n != null) burst(n, MINOR);
                }
            }
            scheduleTick(950 + RND.nextInt(550));
            scheduleRestore(120 + RND.nextInt(70));
        }
    };

    private static final Runnable RESTORE = new Runnable() {
        @Override public void run() {
            restoreQueued = false;
            restoreNow();
        }
    };

    private static Node pick(ArrayList<Node> alive, ArrayList<Node> hot) {
        // 65% dari pool judul bila ada -> glitch terasa "disengaja".
        if (!hot.isEmpty() && RND.nextInt(100) < 65) {
            return hot.get(RND.nextInt(hot.size()));
        }
        return alive.get(RND.nextInt(alive.size()));
    }

    // ---------------- BURST INTI ----------------

    /**
     * Burst dengan range acak (wander, event tanpa info region).
     * Semua variasi glitch teks ada di sini.
     */
    private static void burst(Node node, int level) {
        burst(node, level, -1, -1);
    }

    /**
     * Burst dgn REGION eksplisit (inti "tepat sasaran"): rs/re =
     * range karakter yang BERUBAH (dari onTextChanged). Hanya region itu
     * yang di-span; sisanya tidak disentuh. rs < 0 = range acak (ambien).
     * Deletion (region kosong) -> "seam" di sekitar posisi hapus.
     */
    private static void burst(Node node, int level, int rs, int re) {
        TextView tv = node.ref.get();
        if (tv == null || tv.getVisibility() != View.VISIBLE) return;
        CharSequence cur = tv.getText();
        String s = cur == null ? "" : cur.toString();
        if (s.trim().isEmpty()) return;

        if (node.spannable) {
            // Base = teks dasar (bukan kilatan sebelumnya) agar span
            // berulang tidak menumpuk di atas span lama.
            CharSequence base = PENDING.containsKey(tv)
                    ? PENDING.get(tv) : cur;
            int len = base.length();
            if (len > 0) {
                SpannableString sp = new SpannableString(base);
                int from, to;
                if (rs >= 0) {
                    from = Math.max(0, Math.min(rs, len - 1));
                    to = Math.max(from + 1, Math.min(re, len));
                } else {
                    from = RND.nextInt(len);
                    to = Math.min(len, from + 1
                            + (RND.nextInt(3) == 0 ? 1 : 0));
                }
                sp.setSpan(new ForegroundColorSpan(
                                PALETTE[RND.nextInt(PALETTE.length)]),
                        from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                // Sesekali efek tambahan pada range yang sama:
                // strike-through khas "teks rusak" ...
                if (RND.nextInt(level == MAJOR ? 3
                        : level == MEDIUM ? 4 : 5) == 0) {
                    sp.setSpan(new StrikethroughSpan(),
                            from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                // ... atau blok highlight acid (datamosh block).
                if (RND.nextInt(level == MAJOR ? 3
                        : level == MEDIUM ? 4 : 6) == 0) {
                    sp.setSpan(new BackgroundColorSpan(BLOCK_BG),
                            from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                sApplying = true;
                tv.setText(sp);
                sApplying = false;
                PENDING.put(tv, base);
            }
        }

        float d = DENSITY;
        if (level == MAJOR) {
            // Double-flicker RGB: merah kanan -> cyan kiri -> (restore).
            // cy8: swap ikut framework step() - dibatalkan bersih oleh
            // stop() (dulu postDelayed liar pada view).
            tv.setShadowLayer(1.2f * d, 2f * d, 0f, BURST_SHADOW_RED);
            final TextView ftv = tv;
            final Node fn = node;
            step(ftv, 45, () -> {
                if (SHADOWED.get(ftv) == fn) {
                    ftv.setShadowLayer(1.2f * d, -2f * d, 0f,
                            BURST_SHADOW_CYAN);
                }
            });
            SHADOWED.put(tv, node);
        } else {
            // Kilat shadow RGB bergantian arah: kiri = cyan, kanan = merah.
            boolean left = RND.nextBoolean();
            tv.setShadowLayer(1.2f * d, (left ? -2f : 2f) * d, 0f,
                    left ? BURST_SHADOW_CYAN : BURST_SHADOW_RED);
            SHADOWED.put(tv, node);
        }

        // Variasi: micro squeeze (MEDIUM 1/4, MAJOR 1/3) - pulih ke
        // baseline deterministik via step() (cy8: dulu postDelayed liar);
        // hanya TextView spannable (EditText & tombol aman).
        if (node.spannable && level >= MEDIUM
                && RND.nextInt(level == MAJOR ? 3 : 4) == 0) {
            final TextView stv = tv;
            captureBase(stv);
            stv.setScaleX(RND.nextBoolean() ? 1.015f : 0.985f);
            step(stv, 90 + RND.nextInt(60), () -> {
                float[] b = BASE.get(stv);
                stv.setScaleX(b != null ? b[2] : 1f);
            });
        }
    }

    /**
     * Ghost kilat shadow pada EditText (merah = penghapusan/disintegrate,
     * neon = pengetikan) - TANPA setText (cursor, selection, IME aman).
     */
    private static void inputFlash(TextView tv, boolean deletion) {
        float d = DENSITY;
        int color = deletion ? BURST_SHADOW_RED
                : PALETTE[RND.nextInt(PALETTE.length)];
        float dx = deletion ? 2.5f : 2f;
        tv.setShadowLayer(1.4f * d, dx * d, 0f, color);
        SHADOWED.put(tv, new Node(new WeakReference<>(tv), false, false,
                true, false, 0f, 0f, 0f, 0));
        scheduleRestore(restoreFor(MINOR));
    }

    /**
     * KORUPSI INPUT PER KARAKTER (cy8 - presisi yang diminta):
     *  - insert  : span neon + blok acid HANYA pada [start, start+count)
     *  - hapus   : "seam" korosi merah pada [start-1, start+1) +
     *              strike-through (karakter yang hilang terkorosi)
     *  - paste   : region besar -> level terasa lebih kuat
     * Semua via Editable.setSpan pada teks yang ADA - setText tidak
     * pernah dipanggil: cursor, selection, posisi, IME, focus, dan
     * ukuran view TIDAK tersentuh. Span dilepas 120-180ms kemudian
     * (terjadwal & terlacak; dibersihkan juga oleh stop()).
     */
    private static void inputCorrupt(TextView tv, int start, int count,
            int before) {
        final boolean deletion = count == 0 && before > 0;
        final EditText et = (EditText) tv;
        Editable e = et.getEditableText();
        if (e != null && e.length() > 0) {
            // lepas span kilatan sebelumnya (deterministik, tanpa tumpukan)
            clearInputSpans(et, true);
            int from, to;
            if (count > 0) {
                from = Math.max(0, start);
                to = Math.min(e.length(), start + count);
            } else {
                // seam di sekitar posisi hapus (region yang "terkorosi")
                from = Math.max(0, start - 1);
                to = Math.min(e.length(), start + 1);
            }
            if (to > from) {
                ArrayList<Object> spans = new ArrayList<>(3);
                int color = deletion
                        ? BURST_SHADOW_RED
                        : PALETTE[RND.nextInt(PALETTE.length)];
                Object fg = new ForegroundColorSpan(color);
                e.setSpan(fg, from, to, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                spans.add(fg);
                // blok datamosh: selalu utk deletion (disintegrate),
                // kadang utk insert (merdekat regional)
                if (deletion || RND.nextInt(3) == 0) {
                    Object bg = new BackgroundColorSpan(BLOCK_BG);
                    e.setSpan(bg, from, to,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spans.add(bg);
                }
                // strike-through: karakter yang berubah terasa "rusak"
                if (deletion && RND.nextInt(2) == 0) {
                    Object strike = new StrikethroughSpan();
                    e.setSpan(strike, from, to,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    spans.add(strike);
                }
                INPUT_SPANS.put(et, spans);
                // jadwalkan pelepasan (region pulih = rekonstruksi)
                Runnable prev = INPUT_CLEAR.remove(et);
                if (prev != null) H.removeCallbacks(prev);
                final Runnable clear = new Runnable() {
                    @Override public void run() {
                        INPUT_CLEAR.remove(et);
                        clearInputSpans(et, false);
                    }
                };
                INPUT_CLEAR.put(et, clear);
                H.postDelayed(clear, 120 + RND.nextInt(60));
            }
        }
        // ghost shadow RGB menyertai span (merah utk penghapusan)
        inputFlash(tv, deletion);
    }

    /**
     * Lepas span korupsi input dari sebuah EditText.
     * cancelScheduled=true juga membatalkan runnable pelepasan tertunda.
     */
    private static void clearInputSpans(EditText et, boolean cancelScheduled) {
        if (et == null) return;
        if (cancelScheduled) {
            Runnable c = INPUT_CLEAR.remove(et);
            if (c != null) H.removeCallbacks(c);
        }
        ArrayList<Object> spans = INPUT_SPANS.remove(et);
        if (spans == null) return;
        Editable e = et.getEditableText();
        if (e == null) return;
        for (Object s : spans) {
            try {
                e.removeSpan(s);
            } catch (Exception ignored) {}
        }
    }

    private static void restoreNow() {
        if (!PENDING.isEmpty()) {
            for (Iterator<Map.Entry<TextView, CharSequence>> it =
                    PENDING.entrySet().iterator(); it.hasNext();) {
                Map.Entry<TextView, CharSequence> e = it.next();
                TextView tv = e.getKey();
                CharSequence base = e.getValue();
                it.remove();
                if (tv == null) continue;
                // Guard teks dinamis: hanya pulihkan bila karakternya
                // masih persis teks dasar (kilatan tidak mengubah char).
                if (TextUtils.equals(tv.getText(), base)) {
                    sApplying = true;
                    tv.setText(base);
                    sApplying = false;
                }
            }
        }
        if (!SHADOWED.isEmpty()) {
            for (Iterator<Map.Entry<TextView, Node>> it =
                    SHADOWED.entrySet().iterator(); it.hasNext();) {
                Map.Entry<TextView, Node> e = it.next();
                TextView tv = e.getKey();
                Node n = e.getValue();
                it.remove();
                if (tv == null || n == null) continue;
                float d = DENSITY;
                if (n.hasShadow) {
                    tv.setShadowLayer(n.baseRadius, n.baseDx, n.baseDy,
                            n.baseShadowColor);
                } else {
                    // EditText: tanpa baseline shadow - matikan ghost.
                    tv.setShadowLayer(0f, 0f, 0f, 0);
                }
            }
        }
    }

    /** Buang entri mati (GC) dari registry; dipanggil tiap start/tick. */
    private static void purge() {
        for (Iterator<Node> it = NODES.iterator(); it.hasNext();) {
            if (it.next().ref.get() == null) it.remove();
        }
    }

    private static Node findNode(TextView tv) {
        for (Node n : NODES) {
            if (n.ref.get() == tv) return n;
        }
        return null;
    }

    // ---------------- WATCHER (region-targeted) ----------------

    // Token bucket global: batasi stampede burst watcher (chip sesi 20x
    // per tick, counter monitor) - API eksplisit TIDAK dibatasi bucket.
    private static int sBucket = 0;
    private static long sBucketStart = 0;
    private static final int BUCKET_MAX = 8;
    private static final long BUCKET_WINDOW = 200;

    private static boolean bucketTake() {
        long now = SystemClock.uptimeMillis();
        if (now - sBucketStart > BUCKET_WINDOW) {
            sBucketStart = now;
            sBucket = 0;
        }
        if (sBucket >= BUCKET_MAX) return false;
        sBucket++;
        return true;
    }

    /**
     * Watcher massal - TEPAT SASARAN:
     *  - EditText  : korupsi span PER REGION yang berubah pada EditText
     *                itu saja (insert = karakter baru; hapus = seam korosi
     *                merah). TANPA parent/section. Cursor & IME aman.
     *  - TextView  : span HANYA pada region yang berubah (start..start+count
     *                utk insert; "seam" sekitar posisi hapus utk deletion).
     *                Paste/replace besar (count > 3) = MEDIUM.
     * Throttle per-node 300ms + token bucket global mencegah stampede;
     * sApplying mencegah loop; isGlitchEnabled mencegah burst saat efek
     * mati (mode OFF) atau tak terlihat.
     */
    private static final class GlitchWatcher implements TextWatcher {
        private final Node node;

        GlitchWatcher(Node node) { this.node = node; }

        @Override public void beforeTextChanged(
                CharSequence s, int a, int b, int c) {}

        @Override public void onTextChanged(
                CharSequence s, int start, int before, int count) {
            if (sApplying || !isGlitchEnabled()) return;
            final int st = start, bf = before, ct = count;
            // Post ringan: biarkan layout selesai dulu baru glitch.
            H.postDelayed(() -> {
                if (sApplying || !isGlitchEnabled()) return;
                TextView tv = node.ref.get();
                if (tv == null) return;
                boolean deletion = ct == 0 && bf > 0;
                if (node.input) {
                    // Ketik/hapus = korupsi region pada EditText ITU saja.
                    inputCorrupt(tv, st, ct, bf);
                    markEvent();
                    return;
                }
                // Throttle: teks yang sama-sering tidak menumpuk burst.
                long now = SystemClock.uptimeMillis();
                if (now - node.lastBurst < 300) return;
                if (!bucketTake()) return;
                node.lastBurst = now;
                int level = ct > 3 ? MEDIUM : MINOR;
                if (ct > 0) {
                    burst(node, level, st, st + ct);
                } else if (deletion) {
                    // Seam di sekitar posisi hapus: terasa terkorosi.
                    int from = Math.max(0, st - 1);
                    int len = tv.length();
                    burst(node, MINOR, from,
                            Math.min(len, st + 1));
                } else {
                    burst(node, MINOR);
                }
                markEvent();
                scheduleRestore(restoreFor(level));
            }, 20);
        }

        @Override public void afterTextChanged(
                android.text.Editable s) {}
    }
}
