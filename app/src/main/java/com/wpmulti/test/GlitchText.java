package com.wpmulti.test;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StrikethroughSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Random;

/**
 * GlitchText - mesin glitch "banyak tapi tipis" (Task 32, 70% glitchcore).
 *
 * Filosofi: BUKAN shadow tebal di satu teks, tapi BANYAK instansi kecil
 * yang berpindah-pindah di SELURUH teks UI (dashboard, sesi, log, setelan,
 * termasuk row config/proxy/sesi dinamis). Tiga lapisan:
 *
 *   1. BASELINE - semua TextView terdaftar dapat ghost tipis 1dp red 20%
 *      (glitch_shadow_thin) -> kesan "print misregister" permanen di semua
 *      teks, tanpa mengganggu keterbacaan.
 *   2. WANDER BURST - tiap 380-800ms dipilih 1-3 TextView acak (bias ke
 *      judul bold) untuk kilatan 130-190ms: 1-3 karakter acak diberi warna
 *      neon (cyan/magenta/acid/red via ForegroundColorSpan), sesekali
 *      strike-through (StrikethroughSpan) dan blok highlight acid
 *      (BackgroundColorSpan = "datamosh block"), plus ghost shadow RGB
 *      2dp yang arahnya bergantian (kiri cyan / kanan merah).
 *   3. RESTORE AMAN - kilatan di-restore ke teks dasar; jika ada update
 *      teks dinamis (throughput, status) terjadi di tengah kilatan,
 *      restore di-skip (guard TextUtils.equals) supaya teks baru tidak
 *      pernah tertimpa teks lama.
 *
 * Disiplin yang dijaga: tanpa emoji, tanpa custom view/blur (semua via
 * shadowLayer + span bawaan platform), warna 100% dari resource,
 * tanpa menyentuh token sudut. Registry memakai WeakReference sehingga
 * rotasi activity tidak bocor; registerTree() idempotent (dedup by
 * referensi) dan boleh dipanggil ulang setiap row dinamis dibangun.
 * Status glow khusus (statusBar merah dx 3dp, monGo cyan dx -2dp dari
 * fase cy3) didaftarkan via registerCustom() agar baseline-nya tetap
 * dipelihara saat restore.
 */
public final class GlitchText {

    /** Satu entri registry: ref lemah + baseline shadow untuk restore. */
    private static final class Node {
        final WeakReference<TextView> ref;
        final boolean hot;       // judul bold / teks utama -> lebih sering
        final boolean spannable; // false utk logView besar (shadow only)
        final float baseRadius, baseDx, baseDy;
        final int baseShadowColor;

        Node(WeakReference<TextView> ref, boolean hot, boolean spannable,
             float baseRadius, float baseDx, float baseDy, int baseShadowColor) {
            this.ref = ref;
            this.hot = hot;
            this.spannable = spannable;
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
     * Daftarkan TextView dengan ghost shadow KUSTOM (mis. statusBar merah
     * dx 3dp, monGo cyan dx -2dp) - baseline ini yang dipulihkan saat
     * restore, bukan ghost tipis standar. Idempotent per referensi.
     */
    public static void registerCustom(TextView tv, float dxDp, int shadowColor) {
        if (tv == null || findNode(tv) != null) return;
        NODES.add(new Node(new WeakReference<>(tv), true, true,
                1f * DENSITY, dxDp * DENSITY, 0f, shadowColor));
    }

    /**
     * Traverse pohon view: SEMUA TextView (bukan EditText/input) jadi
     * anggota registry - dashboard, sesi, log, setelan sekaligus.
     * Panggil ulang aman (dedup); panggil setiap row dinamis dibangun.
     */
    public static void registerTree(View root) {
        if (root != null) walk(root);
    }

    private static void walk(View v) {
        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            // Input user tidak pernah di-glitch (kebacaan & editing).
            if (tv instanceof EditText || findNode(tv) != null) return;
            float d = DENSITY;
            tv.setShadowLayer(1f * d, 1f * d, 0f, THIN_SHADOW);
            // logView bisa ribuan baris & di-update per baris -> cukup
            // ghost baseline, tanpa span wander (hemat & anti-jump scroll).
            boolean isLog = tv.getId() == R.id.logView;
            // Judul bold = target "hot" (65% peluang dipilih).
            boolean hot = tv.getTypeface() != null && tv.getTypeface().isBold();
            NODES.add(new Node(new WeakReference<>(tv), hot, !isLog,
                    1f * d, 1f * d, 0f, THIN_SHADOW));
            return; // TextView tidak punya anak view
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
        }
    }

    /** Mulai loop wander (panggil di onResume). Idempotent. */
    public static void start(Context ctx) {
        init(ctx);
        purge();
        if (running) return;
        running = true;
        scheduleTick(250 + RND.nextInt(300));
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
                int bursts = 1 + RND.nextInt(3); // 1..3 target per tick
                for (int i = 0; i < bursts; i++) {
                    Node n = pick(alive, aliveHot);
                    if (n != null) burst(n);
                }
            }
            scheduleTick(380 + RND.nextInt(420));
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
                tv.setText(sp);
                PENDING.put(tv, base);
            }
        }

        // Kilat shadow RGB bergantian arah: kiri = cyan, kanan = merah.
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
                if (TextUtils.equals(tv.getText(), base)) tv.setText(base);
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
                tv.setShadowLayer(n.baseRadius, n.baseDx, n.baseDy,
                        n.baseShadowColor);
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
}
