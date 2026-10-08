package com.wpmulti.test;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
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
import java.util.Map;
import java.util.Random;

/**
 * GlitchText v2 (fase cy6) - glitch sebagai IDENTITAS UI, bukan dekorasi
 * sesekali. Prinsip: "jika ada perubahan state atau perubahan visual yang
 * terlihat oleh pengguna, berikan glitch feedback."
 *
 * HIERARKI INTENSITAS (cy6) - tiga level untuk memetakan besaran perubahan:
 *  - {@link #MINOR}  : ketikan, teks dinamis kecil, press tombol, wander
 *                      ambien. 1-2 span, jitter 1dp, restore cepat.
 *  - {@link #MEDIUM} : dropdown buka/tutup, setting berubah, Split Tunnel /
 *                      mode per IP berubah, config ditambahkan, elemen kecil
 *                      muncul. 2-3 span + RGB shadow + flicker alpha.
 *  - {@link #MAJOR}  : pindah halaman, state engine berubah, panel/section
 *      &nbsp;               besar muncul, list direkonstruksi. 3-4 span +
 *                      double-flicker RGB + displacement container.
 *
 * API EVENT (dipakai ulang di seluruh UI - JANGAN tambah glitch manual
 * per-komponen di luar sistem ini):
 *  - {@link #glitchNow(TextView)}    : kilat satu teks.
 *  - {@link #glitchNow(TextView,int)}: kilat satu teks dgn level.
 *  - {@link #glitchTree(View)} / {@link #glitchTree(View,int)}: semua teks
 *    terdaftar di subtree.
 *  - {@link #glitchMajor(View)}      : tree MAJOR + displacement root
 *    (translationX SAJA - aman dipakai bersama transisi fade/slide halaman
 *    yang memakai alpha/translationY).
 *  - {@link #glitchView(View,int)}   : elemen non-teks / container - flicker
 *    alpha + jitter (muncul/tekan/pindah).
 *  - {@link #glitchAppear(View)}     : "materialize through glitch" untuk
 *    elemen yang baru muncul (row config, chip, HUD, dialog).
 *  - {@link #glitchDisappear(View)}  : "de-rez" singkat sebelum elemen
 *    disembunyikan/dibuang (pemanggil yang menyembunyikan, dengan guard).
 *  - {@link #glitchJitter(View,int)} : displacement murni (item berpindah/
 *    di-sort/rebuild; list "tergemetrek" saat direkonstruksi).
 *  - {@link #spinnerPulse(Spinner)}  : glitch berkala halus SELAMA dropdown
 *    terbuka (dipicu dari installTouch saat spinner disentuh).
 *
 * Dua jalur glitch:
 *  A) WANDER (ambien): loop acak 170-370ms memilih 2-5 TextView (bias judul
 *     bold) untuk kilatan MINOR - UI tidak pernah "diam total".
 *  B) EVENT (cy5/cy6): glitch dipecat PADA PERUBAHAN:
 *     - pindah halaman: glitchMajor(page) dari showPage()
 *     - teks berubah: TextWatcher massal di semua TextView terdaftar
 *       (status, throughput, counter, hint, ...) memicu burst MINOR
 *       otomatis - guard reentrant mencegah loop.
 *     - mengetik: EditText terdaftar mode INPUT - tiap perubahan memicu
 *       ghost merah kilat PADA teks yang diketik (tanpa span/setText
 *       supaya cursor & IME aman) + glitch MINOR di section sekitar.
 *     - dropdown: Spinner + semua Button/CheckBox dipasang touch listener
 *       (installTouch) - press = MINOR, dropdown buka = MEDIUM + pulse.
 *     - setting berubah (spinner/checkbox): listener existing memanggil
 *       glitchTree(section, MEDIUM).
 *     - elemen muncul: glitchAppear() - flicker alpha 5 langkah + squeeze
 *       + jitter + burst MEDIUM.
 *
 * Restorasi AMAN: guard TextUtils.equals - teks dinamis yang berubah di
 * tengah kilatan tidak pernah tertimpa teks lama. Alpha/scale/translation
 * dipulihkan oleh rantai postDelayed deterministik (langkah terakhir selalu
 * mengembalikan nilai asli) sehingga tidak ada state nyangkut walau stop()
 * dipanggil di tengah efek. logView DIBIARKAN BERSIH total (kejelasan log
 * di atas estetika) - collect()/walk()/burst() melewatinya.
 * Registry WeakReference (anti-leak), registerTree idempotent, warna 100%
 * resource, tanpa emoji/custom view/blur, token sudut tak disentuh,
 * animasi hanya alpha/scale/shadow/translation (TANPA layout shift).
 * Aksesibilitas: setAnimScale(0) (skala animator sistem "hapus animasi")
 * mematikan seluruh efek - dipanggil MainActivity.onResume().
 */
public final class GlitchText {

    // ---------------- hierarki intensitas (cy6) ----------------
    /** Kecil: ketikan, teks dinamis, press tombol, wander ambien. */
    public static final int MINOR = 0;
    /** Sedang: dropdown, setting, split tunnel, mode per IP, config baru. */
    public static final int MEDIUM = 1;
    /** Besar: pindah halaman, state engine, rekonstruksi list/panel. */
    public static final int MAJOR = 2;

    /** Skala animator sistem (0 = "hapus animasi" -> semua efek mati). */
    private static volatile float sAnimScale = 1f;

    /** Set skala animator dari activity (onResume). 0 = nonaktif total. */
    public static void setAnimScale(float scale) { sAnimScale = scale; }

    private static boolean fxAllowed() {
        return running && sAnimScale > 0f;
    }

    /** Satu entri registry: ref lemah + baseline shadow untuk restore. */
    private static final class Node {
        final WeakReference<TextView> ref;
        final boolean hot;       // judul bold / teks utama -> lebih sering
        final boolean spannable; // false utk input & logView
        final boolean input;     // true = EditText (ghost kilat saat ketik)
        final boolean hasShadow; // baseline shadow ada (false = EditText)
        final float baseRadius, baseDx, baseDy;
        final int baseShadowColor;

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
     * (glitch saat mengetik), logView dilewati BERSIH. Panggil ulang aman
     * (dedup per referensi).
     */
    public static void registerTree(View root) {
        if (root != null) walk(root);
    }

    /**
     * Pasang glitch touch di SELURUH tree (cy6):
     *  - Button (termasuk MaterialButton/CheckBox/CompoundButton): press =
     *    glitchNow MINOR - feedback singkat, click listener tetap jalan.
     *  - Spinner: sentuh = glitchTree(section) MEDIUM ("dropdown muncul
     *    karena glitch") + spinnerPulse (glitch berkala selama terbuka).
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
            Spinner sp = (Spinner) root;
            sp.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    View p = v.getParent() instanceof View
                            ? (View) v.getParent() : null;
                    glitchTree(p, MEDIUM);
                    spinnerPulse(sp);
                }
                return false;
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

            // logView dibersihkan total - kejelasan log > estetika (cy5)
            if (tv.getId() == R.id.logView) return;

            if (tv instanceof EditText) {
                // INPUT: tanpa span/setText (cursor & IME aman); watcher
                // menembak ghost merah kilat pada teks yang diketik.
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
            // teks berubah (status, counter, hint, ...) = glitch MINOR.
            tv.addTextChangedListener(new GlitchWatcher(n));
            return; // TextView tidak punya anak view
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
        }
    }

    // ---------------- EVENT API (cy5 + level cy6) ----------------

    /** Glitch sekali pada satu TextView (level MEDIUM - counter setting). */
    public static void glitchNow(TextView tv) {
        glitchNow(tv, MEDIUM);
    }

    /** Glitch sekali pada satu TextView dengan level intensitas. */
    public static void glitchNow(TextView tv, int level) {
        if (tv == null || !fxAllowed()) return;
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
     * level: dropdown buka, setting berubah (MEDIUM); pindah halaman,
     * rekonstruksi list (MAJOR). View GONE/skorup dilewati (tidak ada
     * kilatan tersembunyi yang menumpuk PENDING).
     */
    public static void glitchTree(View root, int level) {
        if (root == null || !fxAllowed()) return;
        ArrayList<Node> targets = new ArrayList<>();
        collect(root, targets);
        for (Node n : targets) burst(n, level);
        if (!targets.isEmpty()) scheduleRestore(restoreFor(level));
    }

    /**
     * Glitch MAJOR pada sebuah region: burst MAJOR semua teks terdaftar +
     * displacement container (translationX SAJA). Aman dipanggil pada root
     * halaman yang sedang dianimasikan fade+translationY - dua sumbu itu
     * tidak disentuh di sini.
     */
    public static void glitchMajor(View root) {
        if (root == null || !fxAllowed()) return;
        glitchTree(root, MAJOR);
        glitchJitter(root, MAJOR);
    }

    /**
     * Flicker alpha + displacement pada elemen non-teks / container dengan
     * level: press nav (MINOR), elemen kecil muncul (MEDIUM), panel besar
     * (MAJOR). Tidak menyentuh teks & tidak menyentuh translationY/alpha
     * punya animator lain lebih lama dari rantai efek ini.
     */
    public static void glitchView(View v, int level) {
        if (v == null || !fxAllowed()) return;
        v.animate().cancel();
        // Pola flicker alpha per level (berakhir PASTI di 1f).
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
            v.postDelayed(() -> v.setAlpha(alpha), t);
        }
        t += 45;
        v.postDelayed(() -> v.setAlpha(1f), t);
        glitchJitter(v, level);
    }

    /**
     * Displacement murni (translationX) - untuk elemen yang berpindah,
     * di-sort, atau list yang direkonstruksi. Rantai postDelayed
     * deterministik: langkah terakhir SELALU mengembalikan translationX
     * asli (tidak ada layout shift, tidak ada state nyangkut).
     */
    public static void glitchJitter(View v, int level) {
        if (v == null || !fxAllowed()) return;
        float d = DENSITY;
        float amp = level == MAJOR ? 3.5f : level == MEDIUM ? 2f : 1f;
        final float ox = v.getTranslationX();
        float[] seq = {amp, -amp * 0.7f, amp * 0.45f, -amp * 0.2f};
        int n = level == MINOR ? 2 : level == MEDIUM ? 3 : seq.length;
        long t = 0;
        for (int i = 0; i < n; i++) {
            t += 32 + RND.nextInt(26);
            final float off = seq[i] * d;
            v.postDelayed(() -> v.setTranslationX(ox + off), t);
        }
        v.postDelayed(() -> v.setTranslationX(ox), t + 45);
    }

    /**
     * "Muncul karena glitch": flicker alpha 5 langkah + squeeze-in + jitter
     * + burst MEDIUM subtree - dipakai saat row config/chip sesi/HUD/dialog/
     * panel baru dirender. Berakhir PASTI di alpha 1f, scale 1f.
     */
    public static void glitchAppear(View v) {
        if (v == null || !fxAllowed()) return;
        glitchTree(v, MEDIUM);
        v.animate().cancel();
        v.setAlpha(0f);
        v.postDelayed(() -> v.setAlpha(0.9f), 40);
        v.postDelayed(() -> v.setAlpha(0.15f), 85);
        v.postDelayed(() -> v.setAlpha(0.75f), 130);
        v.postDelayed(() -> v.setAlpha(0.3f), 170);
        v.postDelayed(() -> v.setAlpha(1f), 215);
        v.setScaleX(0.97f);
        v.postDelayed(() -> v.setScaleX(1f), 215);
        glitchJitter(v, MEDIUM);
    }

    /**
     * "Hilang karena glitch": flicker alpha turun + jitter MINOR. Pemanggil
     * yang menyembunyikan elemen SETELAH efek ini (postDelayed + guard
     * kondisi terbaru) - API ini sendiri tidak mengubah visibility.
     */
    public static void glitchDisappear(View v) {
        if (v == null || !fxAllowed()) return;
        glitchTree(v, MINOR);
        v.animate().cancel();
        v.setAlpha(0.25f);
        v.postDelayed(() -> v.setAlpha(0.8f), 35);
        v.postDelayed(() -> v.setAlpha(0.1f), 70);
        v.postDelayed(() -> v.setAlpha(0f), 105);
        glitchJitter(v, MINOR);
    }

    /**
     * Glitch berkala HALUS selama dropdown spinner terbuka (cy6): 4 mini
     * glitchView MINOR pada spinner itu sendiri dalam ~0.8 dtk - dropdown
     * terasa "hidup terganggu" selama terbuka tanpa mengganggu pilihan.
     */
    public static void spinnerPulse(Spinner sp) {
        if (sp == null || !fxAllowed()) return;
        for (int i = 1; i <= 4; i++) {
            final long delay = i * 170L + RND.nextInt(60);
            sp.postDelayed(() -> glitchView(sp, MINOR), delay);
        }
    }

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

    /** Durasi restore per level (MAJOR tampil sedikit lebih lama). */
    private static long restoreFor(int level) {
        if (level == MAJOR) return 170 + RND.nextInt(90);
        if (level == MEDIUM) return 140 + RND.nextInt(70);
        return 110 + RND.nextInt(60);
    }

    // ---------------- WANDER LOOP (ambien, diedit cy6) ----------------

    /** Mulai loop wander (panggil di onResume). Idempotent. */
    public static void start(Context ctx) {
        init(ctx);
        purge();
        if (running) return;
        running = true;
        scheduleTick(180 + RND.nextInt(220));
    }

    /** Hentikan loop + bersihkan semua kilatan aktif (onPause). */
    public static void stop() {
        running = false;
        H.removeCallbacksAndMessages(null);
        tickQueued = false;
        restoreQueued = false;
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
            ArrayList<Node> alive = new ArrayList<>();
            ArrayList<Node> aliveHot = new ArrayList<>();
            for (Node n : NODES) {
                TextView tv = n.ref.get();
                if (tv == null) continue;
                alive.add(n);
                if (n.hot) aliveHot.add(n);
            }
            if (!alive.isEmpty() && fxAllowed()) {
                // cy6: 2..5 target/tick, interval 170-370ms - UI ambien
                // tidak pernah "mati", tapi tetap tipis (MINOR).
                int bursts = 2 + RND.nextInt(4);
                for (int i = 0; i < bursts; i++) {
                    Node n = pick(alive, aliveHot);
                    if (n != null) burst(n, MINOR);
                }
            }
            scheduleTick(170 + RND.nextInt(200));
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

    /**
     * Burst inti - semua variasi glitch teks ada di sini (cy6):
     *  - jumlah range span mengikuti level (1-2 minor, 2-3 medium, 3-4 major)
     *  - probabilitas strike/block naik dgn level
     *  - MAJOR: double-flicker RGB (merah -> cyan sebelum restore)
     *  - MEDIUM/MAJOR: sesekali micro squeeze scaleX (identitas "rusak",
     *    pulih ke 1f pasti - tidak menggeser layout)
     * View GONE / teks kosong dilewati.
     */
    private static void burst(Node node, int level) {
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
                int ranges = level == MAJOR ? 3 + RND.nextInt(2)
                        : level == MEDIUM ? 2 + RND.nextInt(2)
                        : 1 + RND.nextInt(2);
                if (len < 8 && ranges > 1) ranges = 1;
                int strikeP = level == MAJOR ? 3
                        : level == MEDIUM ? 4 : 5;
                int blockP = level == MAJOR ? 3
                        : level == MEDIUM ? 4 : 6;
                int made = 0;
                for (int r = 0; r < ranges && made < ranges; r++) {
                    int start = RND.nextInt(len);
                    int end = Math.min(len,
                            start + 1 + (RND.nextInt(3) == 0 ? 1 : 0));
                    if (end <= start) continue;
                    sp.setSpan(new ForegroundColorSpan(
                                    PALETTE[RND.nextInt(PALETTE.length)]),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    // Sesekali efek tambahan pada range yang sama:
                    // strike-through khas "teks rusak" ...
                    if (RND.nextInt(strikeP) == 0) {
                        sp.setSpan(new StrikethroughSpan(),
                                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    // ... atau blok highlight acid (datamosh block).
                    if (RND.nextInt(blockP) == 0) {
                        sp.setSpan(new BackgroundColorSpan(BLOCK_BG),
                                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    made++;
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

    /**
     * Watcher massal: teks berubah -> burst otomatis MINOR (cy6:
     * perubahan teks = perubahan kecil -> MINOR, lebih sering & ringan).
     * - TextView biasa: span + ghost (restorasi guard TextUtils.equals).
     * - EditText (input): ghost saja di teks ketikan + glitch MINOR di
     *   section sekitarnya (jangan berat per karakter).
     * Guard sApplying mencegah loop; guard running/fxAllowed mencegah
     * burst saat activity tidak terlihat / animasi dimatikan.
     */
    private static final class GlitchWatcher implements TextWatcher {
        private final Node node;

        GlitchWatcher(Node node) { this.node = node; }

        @Override public void beforeTextChanged(
                CharSequence s, int a, int b, int c) {}

        @Override public void onTextChanged(
                CharSequence s, int start, int before, int count) {
            if (sApplying || !fxAllowed()) return;
            // Post ringan: biarkan layout selesai dulu baru glitch.
            H.postDelayed(() -> {
                if (sApplying || !fxAllowed()) return;
                TextView tv = node.ref.get();
                if (tv == null) return;
                burst(node, MINOR);
                if (node.input) {
                    // Saat mengetik: sekitarnya juga ikut "rusak" (MINOR).
                    View p = tv.getParent() instanceof View
                            ? (View) tv.getParent() : null;
                    if (p != null) glitchTree(p, MINOR);
                }
                scheduleRestore(restoreFor(MINOR));
            }, 25 + RND.nextInt(45));
        }

        @Override public void afterTextChanged(
                android.text.Editable s) {}
    }
}
