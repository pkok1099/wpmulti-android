package com.wpmulti.test;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
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
 * GlitchText v3 (fase cy7) - glitch EVENT-DRIVEN yang TEPAT SASARAN.
 *
 * PRINSIP UTAMA: "Sesuatu yang berubah - sesuatu itulah yang glitch."
 * Bukan seluruh UI. Frekuensi event = TINGGI, visual noise = RENDAH.
 *
 * PRIORITAS (cy7):
 *  1. EVENT GLITCH - dipicu perubahan nyata, target = elemen yang berubah:
 *     - ketik/hapus  : HANYA ghost shadow pada EditText itu (cursor/IME
 *                      aman); TIDAK lagi mengglitch section sekitar.
 *     - teks dinamis : span HANYA pada region yang berubah (start,count
 *                      dari onTextChanged) - bukan acak di mana saja.
 *     - dropdown     : GlitchDropdown (popup custom) materialize /
 *                      disintegrate + pulse mikro selama terbuka.
 *     - dialog       : window animation flicker (materialize/disintegrate).
 *     - navbar       : item yang kehilangan & mendapat active state glitch
 *                      MASING-MASING; pill TIDAK di-alpha-flicker (artifact
 *                      kotak = layer clipping shadow elevation).
 *     - page         : pageTransition - A terkorosi per-fragment, B
 *                      direkonstruksi dari fragment (bukan slide/fade).
 *     - config/chip  : row/chip yang muncul/hilang, bukan list-nya.
 *  2. AMBIENT WANDER - 1 target / 950-1500ms, DIJEDA 1.5 dtk setelah
 *     event glitch (markEvent). Atmosfer saja, BUKAN pengganti event.
 *
 * CLEANUP KERAS (cy7): semua langkah efek lewat step() -> Handler H dengan
 * pelacakan per-view (POSTED) + baseline (BASE). stop()/cancelFor(view)
 * membatalkan langkah tertunda dan MENGEMBALIKAN alpha/translation/scale
 * baseline - tidak ada state nyangkut, tidak ada layer tertinggal.
 * Antar-langkah deterministik: rantai terakhir SELALU nilai final.
 *
 * THROTTLE: per-node 300ms (anti-stampede chip sesi/counter tick) + token
 * bucket global 8 burst / 200ms untuk jalur watcher (API eksplisit selalu
 * jalan). Guard TextUtils.equals melindungi teks dinamis saat restore.
 *
 * logView 100% BERSIH (walk/collect/burst melewatinya). Aksesibilitas:
 * setAnimScale(0) mematikan SEMUA efek dan pageTransition langsung final
 * tanpa meninggalkan alpha/transform.
 */
public final class GlitchText {

    // ---------------- hierarki intensitas ----------------
    /** Kecil: ketikan, teks dinamis, press tombol, checkbox, nav item. */
    public static final int MINOR = 0;
    /** Sedang: dropdown, dialog, config, split tunnel, panel muncul. */
    public static final int MEDIUM = 1;
    /** Besar: page navigation, state engine, rekonstruksi list besar. */
    public static final int MAJOR = 2;

    /** Skala animator sistem (0 = "hapus animasi" -> semua efek mati). */
    private static volatile float sAnimScale = 1f;

    /** Set skala animator dari activity (onResume). 0 = nonaktif total. */
    public static void setAnimScale(float scale) { sAnimScale = scale; }

    private static boolean fxAllowed() {
        return running && sAnimScale > 0f;
    }

    /** Aksesibilitas utk GlitchDropdown: efek boleh jalan? */
    public static boolean isFxAllowed() { return fxAllowed(); }

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
        final boolean input;     // true = EditText (ghost kilat saat ketik)
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
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Random RND = new Random();

    private static int[] PALETTE;          // warna neon dari resource
    private static int THIN_SHADOW;        // ghost baseline red 20%
    private static int BURST_SHADOW_RED;   // kilat kanan (sisi R)
    private static int BURST_SHADOW_CYAN;  // kilat kiri (sisi C)
    private static int BLOCK_BG;           // blok datamosh acid transparan
    private static float DENSITY;
    private static boolean running;
    private static boolean tickQueued;
    private static boolean restoreQueued;
    /** Guard reentrant: setText dari burst/restore TIDAK boleh memicu
     *  watcher lagi (TextWatcher onTextChanged -> burst -> setText ...). */
    private static boolean sApplying;

    // ---------------- cleanup framework (cy7) ----------------
    /** Baseline transform view yang sedang berefek: {alpha, tx, scaleX}. */
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
     * Traverse pohon view: SEMUA TextView jadi anggota registry - dashboard,
     * sesi, log, setelan, isi dialog sekaligus. EditText masuk mode INPUT
     * (ghost saat ketik - TANPA menyentuh parent, cy7), logView dilewati
     * BERSIH. Panggil ulang aman (dedup per referensi).
     */
    public static void registerTree(View root) {
        if (root != null) walk(root);
    }

    /**
     * Pasang glitch touch di SELURUH tree (cy7):
     *  - Button/CheckBox: press = glitchNow MINOR (feedback tepat sasaran).
     *  - Spinner: sentuh = buka GlitchDropdown (popup custom) - dropdown
     *    materialize/disintegrate via glitch, BUKAN popup platform.
     *  Panggil ulang aman (listener menimpa dirinya sendiri).
     */
    public static void installTouch(View root) {
        if (root == null) return;
        if (root instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) root;
            for (int i = 0; i < g.getChildCount(); i++)
                installTouch(g.getChildAt(i));
            return;
        }
        if (root instanceof Spinner) {
            final Spinner sp = (Spinner) root;
            sp.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_UP) {
                    GlitchDropdown.show(sp);
                }
                return true; // konsumsi: popup platform tidak dibuka
            });
            return;
        }
        if (root instanceof Button && root instanceof TextView) {
            TextView b = (TextView) root;
            b.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN)
                    glitchNow((TextView) v, MINOR);
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
                // INPUT: ghost shadow saja saat ketik/hapus (cursor & IME
                // aman). cy7: TIDAK menyentuh parent/section sekitar.
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
            // pada REGION yang berubah (cy7, lihat GlitchWatcher).
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
     * view mencatat baseline {alpha, translationX, scaleX} - dijamin
     * dipulihkan oleh cancelFor/stop/restoreAllBase. Panggilan ini harus
     * SEBELUM mutasi pertama agar baseline belum terubah.
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
                v.getScaleX()});
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
        if (tv == null || !fxAllowed()) return;
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
        if (root == null || !fxAllowed()) return;
        markEvent();
        ArrayList<Node> targets = new ArrayList<>();
        collect(root, targets);
        for (Node n : targets) burst(n, level);
        if (!targets.isEmpty()) scheduleRestore(restoreFor(level));
    }

    /**
     * Glitch MAJOR pada sebuah region: burst MAJOR semua teks terdaftar +
     * displacement container (translationX SAJA). Hanya utk perubahan
     * BESAR (state engine, rekonstruksi list) - BUKAN event kecil.
     */
    public static void glitchMajor(View root) {
        if (root == null || !fxAllowed()) return;
        glitchTree(root, MAJOR);
        glitchJitter(root, MAJOR);
    }

    /**
     * Flicker alpha + displacement pada elemen non-teks / container.
     * cy7: DIHINDARI pada view ber-elevation (pill nav, HUD) - alpha < 1
     * memaksa offscreen layer yang ter-clip di bounds persegi sehingga
     * shadow elevation terpotong = ARTIFACT KOTAK. Gunakan glitchJitter
     * untuk view itu. Rantai berakhir PASTI di alpha 1f.
     */
    public static void glitchView(View v, int level) {
        if (v == null || !fxAllowed()) return;
        markEvent();
        v.animate().cancel();
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
        step(v, t, () -> v.setAlpha(1f));
        glitchJitter(v, level);
    }

    /**
     * Displacement murni (translationX) - TIDAK memaksa layer, TIDAK
     * menyentuh alpha: aman utk view ber-elevation (pill nav). Rantai
     * deterministik: langkah terakhir SELALU mengembalikan translationX
     * asli (tidak ada layout shift, tidak ada state nyangkut).
     */
    public static void glitchJitter(View v, int level) {
        if (v == null || !fxAllowed()) return;
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
        step(v, t + 45, () -> v.setTranslationX(ox));
    }

    /**
     * "Muncul karena glitch": flicker alpha + squeeze-in + jitter + burst
     * MEDIUM subtree - dipakai saat row config/chip sesi/HUD baru dirender.
     * Berakhir PASTI di alpha 1f, scale 1f. JANGAN dipakai untuk view
     * ber-elevation (pakai glitchJitter + glitchTree saja).
     */
    public static void glitchAppear(View v) {
        if (v == null || !fxAllowed()) return;
        markEvent();
        glitchTree(v, MEDIUM);
        v.animate().cancel();
        captureBase(v);
        v.setAlpha(0f);
        v.setScaleX(0.97f);
        step(v, 40,  () -> v.setAlpha(0.9f));
        step(v, 85,  () -> v.setAlpha(0.15f));
        step(v, 130, () -> v.setAlpha(0.75f));
        step(v, 170, () -> v.setAlpha(0.3f));
        step(v, 215, () -> { v.setAlpha(1f); v.setScaleX(1f); });
        glitchJitter(v, MEDIUM);
    }

    /**
     * "Hilang karena glitch": flicker alpha turun + jitter MINOR. Pemanggil
     * yang menyembunyikan elemen SETELAH efek (postDelayed + guard kondisi
     * terbaru) - API ini sendiri tidak mengubah visibility.
     */
    public static void glitchDisappear(View v) {
        if (v == null || !fxAllowed()) return;
        markEvent();
        glitchTree(v, MINOR);
        v.animate().cancel();
        captureBase(v);
        v.setAlpha(0.25f);
        step(v, 35,  () -> v.setAlpha(0.8f));
        step(v, 70,  () -> v.setAlpha(0.1f));
        step(v, 105, () -> v.setAlpha(0f));
        glitchJitter(v, MINOR);
    }

    // ---------------- staggered fragment (dropdown & page) ----------------

    /**
     * Materialize staggered utk ANAK-ANAK container (item dropdown /
     * fragment halaman): tiap anak flicker-up + jitter mikro bergeser
     * waktu 16ms - kesan "fragment berkumpul membentuk elemen".
     */
    public static void materializeStaggered(ViewGroup g) {
        if (g == null || !fxAllowed()) return;
        markEvent();
        for (int i = 0; i < g.getChildCount(); i++) {
            final View c = g.getChildAt(i);
            final int d = i * 16;
            step(c, d, () -> c.setAlpha(0.15f));
            step(c, d + 26, () -> c.setAlpha(0.7f));
            step(c, d + 52, () -> c.setAlpha(0.3f));
            step(c, d + 78, () -> { c.setAlpha(1f); c.setTranslationX(0f); });
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
        if (g == null || !fxAllowed()) return;
        markEvent();
        for (int i = 0; i < g.getChildCount(); i++) {
            final View c = g.getChildAt(i);
            final int d = i * 12;
            step(c, d,      () -> c.setAlpha(0.5f));
            step(c, d + 24, () -> c.setAlpha(0.9f));
            step(c, d + 48, () -> c.setAlpha(0f));
        }
    }

    /**
     * Satu denyut mikro pada SATU anak acak container yang terlihat -
     * dipakai GlitchDropdown sbg "pulse" berkala selama dropdown terbuka.
     * Mengembalikan true bila ada anak yang dikenai efek.
     */
    public static boolean dropdownPulse(ViewGroup g) {
        if (g == null || !fxAllowed()) return false;
        ArrayList<View> vis = new ArrayList<>();
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (c.getVisibility() == View.VISIBLE) vis.add(c);
        }
        if (vis.isEmpty()) return false;
        View target = vis.get(RND.nextInt(vis.size()));
        glitchView(target, MINOR);
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

    // ---------------- page transition (cy7) ----------------

    /** Fragment halaman = anak-anak konten scroll (bukan scroll itu). */
    private static List<View> fragments(View page) {
        List<View> out = new ArrayList<>();
        if (page instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) page;
            if (g.getChildCount() == 1 && g.getChildAt(0) instanceof ViewGroup) {
                ViewGroup inner = (ViewGroup) g.getChildAt(0);
                for (int i = 0; i < inner.getChildCount(); i++)
                    out.add(inner.getChildAt(i));
            } else {
                for (int i = 0; i < g.getChildCount(); i++)
                    out.add(g.getChildAt(i));
            }
        }
        return out;
    }

    /** Generation counter transisi - transisi baru membatalkan yang lama. */
    private static long sPageGen = 0;

    private static void flickerDown(View v, long gen) {
        captureBase(v);
        step(v, 30,  () -> { if (sPageGen == gen) v.setAlpha(0.55f); });
        step(v, 60,  () -> { if (sPageGen == gen) v.setAlpha(0.15f); });
        step(v, 90,  () -> { if (sPageGen == gen) v.setAlpha(0.7f); });
        step(v, 120, () -> { if (sPageGen == gen) v.setAlpha(0.25f); });
        step(v, 150, () -> { if (sPageGen == gen) v.setAlpha(0f); });
    }

    private static void flickerUp(View v, long gen) {
        captureBase(v);
        step(v, 30,  () -> { if (sPageGen == gen) v.setAlpha(0.7f); });
        step(v, 60,  () -> { if (sPageGen == gen) v.setAlpha(0.2f); });
        step(v, 90,  () -> { if (sPageGen == gen) v.setAlpha(0.85f); });
        step(v, 120, () -> { if (sPageGen == gen) v.setAlpha(0.4f); });
        step(v, 150, () -> {
            if (sPageGen == gen) {
                v.setAlpha(1f);
                v.setTranslationX(0f);
            }
        });
        step(v, 34, () -> { if (sPageGen == gen)
                v.setTranslationX(2f * DENSITY); });
        step(v, 66, () -> { if (sPageGen == gen)
                v.setTranslationX(-1.5f * DENSITY); });
    }

    private static void resetFrags(List<View> frags) {
        for (View f : frags) {
            f.setAlpha(1f);
            f.setTranslationX(0f);
        }
    }

    /**
     * Transisi halaman "A terkorosi -> B direkonstruksi" (cy7):
     *   0-170ms  : A burst MAJOR + jitter, fragment-nya pecah staggered
     *   ~180ms   : A disembunyikan (alpha & transform fragment di-reset)
     *   200-540ms: fragment B menyala staggered + burst MAJOR di B
     *   560ms    : B stabil (semua alpha 1f, translation 0)
     * CEPAT BERGANTI TAB: generation counter - langkah gen lama no-op,
     * state visibility sudah diatur pemanggil. animScale 0 -> langsung
     * final tanpa efek dan tanpa state tertinggal.
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
        if (!fxAllowed()) {
            if (out != null && out != in) {
                out.setVisibility(View.GONE);
                resetFrags(fragments(out));
            }
            resetFrags(fragments(in));
            return;
        }
        markEvent();
        final long gen = ++sPageGen;
        final List<View> outFrags = fragments(out);
        final List<View> inFrags = fragments(in);

        // FASE 1 - A terkorosi: teks korup serentak + micro displacement,
        // lalu fragment pecah staggered (alpha turun bertahap per fragment).
        glitchTree(out, MAJOR);
        glitchJitter(out, MAJOR);
        int i = 0;
        for (View f : outFrags) {
            final View ff = f;
            final int d = i * 22;
            step(ff, d, () -> { if (sPageGen == gen) flickerDown(ff, gen); });
            i++;
        }
        // FASE 2 - swap: sembunyikan A, bersihkan sisa transformnya.
        step(out, 185, () -> {
            if (sPageGen != gen) return;
            out.setVisibility(View.GONE);
            resetFrags(outFrags);
            out.setTranslationX(0f);
        });

        // FASE 3 - B materialize dari fragment: anak-anak mulai gelap,
        // menyala staggered + burst MAJOR teks saat rekonstruksi.
        for (View f : inFrags) f.setAlpha(0f);
        int j = 0;
        for (View f : inFrags) {
            final View ff = f;
            final int d = 210 + j * 24;
            step(ff, d, () -> { if (sPageGen == gen) flickerUp(ff, gen); });
            j++;
        }
        step(in, 220, () -> { if (sPageGen == gen) {
            glitchTree(in, MAJOR);
            glitchJitter(in, MAJOR);
        }});
        // FASE 4 - stabil: semua alpha/translation kembali normal.
        step(in, 560, () -> {
            if (sPageGen != gen) return;
            resetFrags(inFrags);
            in.setTranslationX(0f);
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
        restoreNow();
        restoreAllBase();
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
            // cy7: ambient DIJEDA setelah event glitch - event adalah
            // bintangnya, ambient hanya atmosfer (1 target / 950-1500ms).
            long now = SystemClock.uptimeMillis();
            if (now - sLastEvent >= AMBIENT_COOLDOWN_MS && fxAllowed()) {
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
     * Burst dgn REGION eksplisit (cy7 - inti "tepat sasaran"): rs/re =
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
            // Guard SHADOWED.get(v2)==node: bila restore terkonsolidasi
            // sudah berlalu sebelum swap, swap DILEWATI agar tidak ada
            // bayangan cyan yang nyangkut setelah baseline dipulihkan.
            tv.setShadowLayer(1.2f * d, 2f * d, 0f, BURST_SHADOW_RED);
            final TextView ftv = tv;
            final Node fn = node;
            ftv.postDelayed(() -> {
                TextView v2 = fn.ref.get();
                if (v2 != null && SHADOWED.get(v2) == fn) {
                    v2.setShadowLayer(1.2f * d, -2f * d, 0f,
                            BURST_SHADOW_CYAN);
                }
            }, 45);
            SHADOWED.put(tv, node);
        } else {
            // Kilat shadow RGB bergantian arah: kiri = cyan, kanan = merah.
            boolean left = RND.nextBoolean();
            tv.setShadowLayer(1.2f * d, (left ? -2f : 2f) * d, 0f,
                    left ? BURST_SHADOW_CYAN : BURST_SHADOW_RED);
            SHADOWED.put(tv, node);
        }

        // Variasi: micro squeeze (MEDIUM 1/4, MAJOR 1/3) - pulih ke 1f
        // deterministik; hanya TextView spannable (EditText & tombol aman).
        if (node.spannable && level >= MEDIUM
                && RND.nextInt(level == MAJOR ? 3 : 4) == 0) {
            tv.setScaleX(RND.nextBoolean() ? 1.015f : 0.985f);
            final TextView stv = tv;
            stv.postDelayed(() -> stv.setScaleX(1f), 90 + RND.nextInt(60));
        }
    }

    /**
     * Ghost kilat pada EditText saat mengetik/menghapus (cy7) - TANPA
     * span/setText (cursor, selection, IME aman) dan TANPA menyentuh
     * parent. Merah = penghapusan (disintegrate), neon = pengetikan.
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

    // ---------------- WATCHER (region-targeted, cy7) ----------------

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
     * Watcher massal cy7 - TEPAT SASARAN:
     *  - TextView  : span HANYA pada region yang berubah (start..start+count
     *                utk insert; "seam" sekitar posisi hapus utk deletion).
     *                Paste/replace besar (count > 3) = MEDIUM.
     *  - EditText  : ghost kilat PADA EditText itu saja - deletion merah
     *                (disintegrate), ketik neon. TANPA parent/section.
     * Throttle per-node 300ms + token bucket global mencegah stampede;
     * sApplying mencegah loop; fxAllowed mencegah burst saat tak terlihat.
     */
    private static final class GlitchWatcher implements TextWatcher {
        private final Node node;

        GlitchWatcher(Node node) { this.node = node; }

        @Override public void beforeTextChanged(
                CharSequence s, int a, int b, int c) {}

        @Override public void onTextChanged(
                CharSequence s, int start, int before, int count) {
            if (sApplying || !fxAllowed()) return;
            final int st = start, bf = before, ct = count;
            // Post ringan: biarkan layout selesai dulu baru glitch.
            H.postDelayed(() -> {
                if (sApplying || !fxAllowed()) return;
                TextView tv = node.ref.get();
                if (tv == null) return;
                boolean deletion = ct == 0 && bf > 0;
                if (node.input) {
                    // Ketik/hapus = HANYA EditText itu (tepat sasaran).
                    inputFlash(tv, deletion);
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
