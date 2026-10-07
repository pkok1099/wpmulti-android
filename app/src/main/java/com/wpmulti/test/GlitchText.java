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
 * GlitchText - mesin glitch "banyak tapi tipis" (Task 32, dieskalasi
 * Task 33 jadi EVENT-DRIVEN di semua interaksi).
 *
 * Dua jalur glitch:
 *  A) WANDER (Task 32): loop acak 240-560ms pilih 2-4 TextView (bias
 *     judul bold) untuk kilatan 130-190ms - ghost baseline tipis red 20%
 *     di SEMUA TextView membuat kesan misregister permanen.
 *  B) EVENT (Task 33): glitch dipecat PADA PERUBAHAN, bukan hanya acak:
 *     - pindah halaman: glitchTree(page) dari showPage()
 *     - teks berubah: TextWatcher massal di SEMUA TextView terdaftar
 *       (status, throughput, counter split tunnel, proxy status, ...)
 *       memicu burst otomatis - guard reentrant mencegah loop.
 *     - mengetik: EditText terdaftar mode INPUT - tiap perubahan teks
 *       memicu kilat ghost merah PADA teks yang diketik (tanpa span/
 *       setText supaya cursor & IME aman) + glitch di section sekitar.
 *     - dropdown dibuka: Spinner + Button dipasang touch listener
 *       (installTouch) yang memicu glitch di section-nya - terasa
 *       dropdown "muncul karena glitch".
 *     - setting berubah (spinner/checkbox): listener existing memanggil
 *       glitchTree(section).
 *     - sesuatu muncul (row config/proxy baru): glitchAppear() -
 *       flicker alpha 4 langkah + burst span, seolah "muncul karena
 *       glitch".
 *
 * Restorasi AMAN: guard TextUtils.equals - teks dinamis yang berubah di
 * tengah kilatan tidak pernah tertimpa teks lama. logView DIBIARKAN
 * BERSIH total (permintaan Task 33: kejelasan log di atas estetika).
 * Registry WeakReference (anti-leak), registerTree idempotent, warna
 * 100% resource, tanpa emoji/custom view/blur, token sudut tak disentuh.
 */
public final class GlitchText {

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
     * sesi, log, setelan sekaligus. EditText masuk mode INPUT (glitch saat
     * mengetik), logView dilewati BERSIH. Panggil ulang aman (dedup).
     */
    public static void registerTree(View root) {
        if (root != null) walk(root);
    }

    /**
     * Pasang glitch touch: SEMUA Button di tree -> glitchNow saat ditekan
     * (return false = click listener tetap jalan normal); SEMUA Spinner ->
     * glitchTree(section-nya) saat disentuh (dropdown "muncul karena
     * glitch"). Panggil ulang aman (listener menimpa dirinya sendiri).
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
                    glitchTree(p);
                }
                return false;
            });
            return;
        }
        if (root instanceof Button && root instanceof TextView) {
            TextView b = (TextView) root;
            b.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN)
                    glitchNow((TextView) v);
                return false;
            });
        }
    }

    private static void walk(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (findNode(tv) != null) return; // dedup

            // logView dibersihkan total - kejelasan log > estetika (Task 33)
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
            // Task 33: teks berubah (status, counter, hint, ...) = glitch.
            tv.addTextChangedListener(new GlitchWatcher(n));
            return; // TextView tidak punya anak view
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
        }
    }

    // ---------------- EVENT API (Task 33) ----------------

    /** Glitch sekali pada satu TextView (span + ghost kilat, auto-restore). */
    public static void glitchNow(TextView tv) {
        if (tv == null) return;
        Node n = findNode(tv);
        if (n != null) {
            burst(n);
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
        scheduleRestore(130 + RND.nextInt(90));
    }

    /**
     * Glitch SEKALI pada semua TextView di bawah root - dipakai saat
     * pindah halaman, dropdown dibuka, setting berubah, row muncul.
     */
    public static void glitchTree(View root) {
        if (root == null || !running) return;
        ArrayList<Node> targets = new ArrayList<>();
        collect(root, targets);
        for (Node n : targets) burst(n);
        if (!targets.isEmpty()) scheduleRestore(150 + RND.nextInt(80));
    }

    /**
     * "Muncul karena glitch": flicker alpha 4 langkah + burst span di
     * seluruh subtree - dipakai saat row config baru dirender.
     */
    public static void glitchAppear(View v) {
        if (v == null || !running) return;
        glitchTree(v);
        v.setAlpha(0f);
        v.postDelayed(() -> v.setAlpha(1f), 45);
        v.postDelayed(() -> v.setAlpha(0.25f), 95);
        v.postDelayed(() -> v.setAlpha(0.7f), 140);
        v.postDelayed(() -> v.setAlpha(1f), 185);
    }

    private static void collect(View v, ArrayList<Node> out) {
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

    // ---------------- WANDER LOOP (Task 32, diedit Task 33) ----------------

    /** Mulai loop wander (panggil di onResume). Idempotent. */
    public static void start(Context ctx) {
        init(ctx);
        purge();
        if (running) return;
        running = true;
        scheduleTick(200 + RND.nextInt(250));
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
            if (!alive.isEmpty()) {
                // Task 33: lebih sering & lebih banyak (2..4 target/tick).
                int bursts = 2 + RND.nextInt(3);
                for (int i = 0; i < bursts; i++) {
                    Node n = pick(alive, aliveHot);
                    if (n != null) burst(n);
                }
            }
            scheduleTick(240 + RND.nextInt(320));
            scheduleRestore(130 + RND.nextInt(60));
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

    private static void burst(Node node) {
        TextView tv = node.ref.get();
        if (tv == null) return;
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
                int ranges = len > 14 ? 2 + RND.nextInt(2) : 1;
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
                    if (RND.nextInt(4) == 0) {
                        sp.setSpan(new StrikethroughSpan(),
                                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                    // ... atau blok highlight acid (datamosh block).
                    if (RND.nextInt(4) == 0) {
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

        // Kilat shadow RGB bergantian arah: kiri = cyan, kanan = merah.
        // EditText (input=true) juga kena - ghost pada teks yang diketik.
        boolean left = RND.nextBoolean();
        float d = DENSITY;
        tv.setShadowLayer(1.2f * d, (left ? -2f : 2f) * d, 0f,
                left ? BURST_SHADOW_CYAN : BURST_SHADOW_RED);
        SHADOWED.put(tv, node);
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
     * Watcher massal (Task 33): teks berubah -> burst otomatis.
     * - TextView biasa: span + ghost (restorasi guard TextUtils.equals).
     * - EditText (input): ghost saja, tanpa menyentuh teks ketikan.
     * Guard sApplying mencegah loop; guard running mencegah burst saat
     * activity tidak terlihat (PENDING tak pernah tertinggal nyangkut).
     */
    private static final class GlitchWatcher implements TextWatcher {
        private final Node node;

        GlitchWatcher(Node node) { this.node = node; }

        @Override public void beforeTextChanged(
                CharSequence s, int a, int b, int c) {}

        @Override public void onTextChanged(
                CharSequence s, int start, int before, int count) {
            if (sApplying || !running) return;
            // Post ringan: biarkan layout selesai dulu baru glitch.
            H.postDelayed(() -> {
                if (sApplying || !running) return;
                TextView tv = node.ref.get();
                if (tv == null) return;
                burst(node);
                if (node.input) {
                    // Saat mengetik: sekitarnya juga ikut "rusak".
                    View p = tv.getParent() instanceof View
                            ? (View) tv.getParent() : null;
                    if (p != null) glitchTree(p);
                }
                scheduleRestore(120 + RND.nextInt(80));
            }, 30 + RND.nextInt(50));
        }

        @Override public void afterTextChanged(
                android.text.Editable s) {}
    }
}
