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
 * GlitchText v3 - Glitch sebagai mekanisme perubahan UI event-driven.
 *
 * Prinsip:
 * 1. "Sesuatu yang berubah -> sesuatu itulah yang mengalami glitch."
 * 2. Glitch tepat sasaran pada element/view target, bukan seluruh UI untuk perubahan kecil.
 * 3. Priority: Event-driven glitch (tinggi) vs Ambient wander glitch (subtle & sangat jarang).
 * 4. LogView 100% bebas dari glitch.
 * 5. Layout & state stabil: semua temporary transform/alpha/shadow dibersihkan total.
 * 6. Respect animation scale = 0 (accessibility).
 */
public final class GlitchText {

    public static final int MINOR = 0;
    public static final int MEDIUM = 1;
    public static final int MAJOR = 2;

    private static volatile float sAnimScale = 1f;

    public static void setAnimScale(float scale) { sAnimScale = scale; }

    public static boolean fxAllowed() {
        return running && sAnimScale > 0f;
    }

    private static final class Node {
        final WeakReference<TextView> ref;
        final boolean hot;
        final boolean spannable;
        final boolean input;
        final boolean hasShadow;
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
    private static final IdentityHashMap<TextView, CharSequence> PENDING = new IdentityHashMap<>();
    private static final IdentityHashMap<TextView, Node> SHADOWED = new IdentityHashMap<>();
    private static final Handler H = new Handler(Looper.getMainLooper());
    private static final Random RND = new Random();

    private static int[] PALETTE;
    private static int THIN_SHADOW;
    private static int BURST_SHADOW_RED;
    private static int BURST_SHADOW_CYAN;
    private static int BLOCK_BG;
    private static float DENSITY;
    private static boolean running;
    private static boolean tickQueued;
    private static boolean restoreQueued;
    private static boolean sApplying;

    private GlitchText() {}

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

    public static void registerCustom(TextView tv, float dxDp, int shadowColor) {
        if (tv == null || tv.getId() == R.id.logView || findNode(tv) != null) return;
        NODES.add(new Node(new WeakReference<>(tv), true, true, false, true,
                1f * DENSITY, dxDp * DENSITY, 0f, shadowColor));
    }

    public static void registerTree(View root) {
        if (root != null) walk(root);
    }

    public static void installTouch(View root) {
        if (root == null) return;
        if (root.getId() == R.id.logView) return;

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
                    glitchMedium(v);
                    spinnerPulse(sp);
                }
                return false;
            });
            return;
        }
        if (root instanceof Button || (root instanceof TextView && root.isClickable())) {
            View b = root;
            b.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    glitchMinor(v);
                }
                return false;
            });
        }
    }

    private static void walk(View v) {
        if (v == null || v.getId() == R.id.logView) return;

        if (v instanceof TextView) {
            TextView tv = (TextView) v;
            if (findNode(tv) != null) return;

            if (tv instanceof EditText) {
                Node n = new Node(new WeakReference<>(tv), false, false,
                        true, false, 0f, 0f, 0f, 0);
                NODES.add(n);
                tv.addTextChangedListener(new GlitchWatcher(n));
                return;
            }

            float d = DENSITY;
            tv.setShadowLayer(1f * d, 1f * d, 0f, THIN_SHADOW);
            boolean hot = tv.getTypeface() != null && tv.getTypeface().isBold();
            Node n = new Node(new WeakReference<>(tv), hot, true, false,
                    true, 1f * d, 1f * d, 0f, THIN_SHADOW);
            NODES.add(n);
            tv.addTextChangedListener(new GlitchWatcher(n));
            return;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
        }
    }

    // ---------------- EXPLICIT REUSABLE TARGETED API ----------------

    public static void glitchMinor(View v) {
        if (v == null) return;
        if (!fxAllowed()) {
            cleanView(v);
            return;
        }
        if (v instanceof TextView) {
            glitchNow((TextView) v, MINOR);
        } else {
            glitchView(v, MINOR);
        }
    }

    public static void glitchMedium(View v) {
        if (v == null) return;
        if (!fxAllowed()) {
            cleanView(v);
            return;
        }
        if (v instanceof TextView) {
            glitchNow((TextView) v, MEDIUM);
        } else {
            glitchTree(v, MEDIUM);
            glitchView(v, MEDIUM);
        }
    }

    public static void glitchMajor(View v) {
        if (v == null) return;
        if (!fxAllowed()) {
            cleanView(v);
            return;
        }
        glitchTree(v, MAJOR);
        glitchJitter(v, MAJOR);
    }

    public static void glitchNow(TextView tv) {
        glitchNow(tv, MEDIUM);
    }

    public static void glitchNow(TextView tv, int level) {
        if (tv == null || tv.getId() == R.id.logView) return;
        if (!fxAllowed()) {
            cleanView(tv);
            return;
        }
        Node n = findNode(tv);
        if (n != null) {
            burst(n, level);
        } else {
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

    public static void glitchTree(View root) {
        glitchTree(root, MEDIUM);
    }

    public static void glitchTree(View root, int level) {
        if (root == null || root.getId() == R.id.logView) return;
        if (!fxAllowed()) {
            cleanView(root);
            return;
        }
        ArrayList<Node> targets = new ArrayList<>();
        collect(root, targets);
        for (Node n : targets) burst(n, level);
        if (!targets.isEmpty()) scheduleRestore(restoreFor(level));
    }

    public static void glitchView(View v, int level) {
        if (v == null || v.getId() == R.id.logView) return;
        if (!fxAllowed()) {
            cleanView(v);
            return;
        }
        v.animate().cancel();
        long t = 0;
        float[] seq;
        switch (level) {
            case MAJOR:
                seq = new float[]{0.15f, 0.85f, 0.3f, 0.9f, 0.5f};
                break;
            case MEDIUM:
                seq = new float[]{0.25f, 0.85f, 0.4f};
                break;
            default:
                seq = new float[]{0.4f, 0.85f};
        }
        for (float a : seq) {
            t += 30 + RND.nextInt(20);
            final float alpha = a;
            v.postDelayed(() -> v.setAlpha(alpha), t);
        }
        t += 35;
        v.postDelayed(() -> v.setAlpha(1f), t);
        glitchJitter(v, level);
    }

    public static void glitchJitter(View v, int level) {
        if (v == null || v.getId() == R.id.logView) return;
        if (!fxAllowed()) {
            cleanView(v);
            return;
        }
        float d = DENSITY;
        float amp = level == MAJOR ? 3.5f : level == MEDIUM ? 2f : 1f;
        final float ox = v.getTranslationX();
        float[] seq = {amp, -amp * 0.7f, amp * 0.45f, -amp * 0.2f};
        int n = level == MINOR ? 2 : level == MEDIUM ? 3 : seq.length;
        long t = 0;
        for (int i = 0; i < n; i++) {
            t += 25 + RND.nextInt(20);
            final float off = seq[i] * d;
            v.postDelayed(() -> v.setTranslationX(ox + off), t);
        }
        v.postDelayed(() -> v.setTranslationX(ox), t + 35);
    }

    public static void glitchAppear(View v) {
        if (v == null) return;
        if (!fxAllowed()) {
            v.setVisibility(View.VISIBLE);
            v.setAlpha(1f);
            cleanView(v);
            return;
        }
        v.setVisibility(View.VISIBLE);
        glitchTree(v, MEDIUM);
        v.animate().cancel();
        v.setAlpha(0f);
        v.postDelayed(() -> v.setAlpha(0.9f), 30);
        v.postDelayed(() -> v.setAlpha(0.15f), 70);
        v.postDelayed(() -> v.setAlpha(0.8f), 110);
        v.postDelayed(() -> v.setAlpha(0.35f), 150);
        v.postDelayed(() -> v.setAlpha(1f), 190);
        v.setScaleX(0.97f);
        v.postDelayed(() -> v.setScaleX(1f), 190);
        glitchJitter(v, MEDIUM);
    }

    public static void glitchDisappear(View v) {
        glitchDisappear(v, null);
    }

    public static void glitchDisappear(View v, Runnable onDone) {
        if (v == null) {
            if (onDone != null) onDone.run();
            return;
        }
        if (!fxAllowed()) {
            v.setVisibility(View.GONE);
            cleanView(v);
            if (onDone != null) onDone.run();
            return;
        }
        glitchTree(v, MINOR);
        v.animate().cancel();
        v.setAlpha(0.3f);
        v.postDelayed(() -> v.setAlpha(0.7f), 30);
        v.postDelayed(() -> v.setAlpha(0.1f), 65);
        v.postDelayed(() -> {
            v.setAlpha(0f);
            v.setVisibility(View.GONE);
            cleanView(v);
            if (onDone != null) onDone.run();
        }, 100);
        glitchJitter(v, MINOR);
    }

    public static void spinnerPulse(Spinner sp) {
        if (sp == null || !fxAllowed()) return;
        for (int i = 1; i <= 3; i++) {
            final long delay = i * 200L + RND.nextInt(50);
            sp.postDelayed(() -> glitchView(sp, MINOR), delay);
        }
    }

    /**
     * Page Transition Animation - Corrupted Reconstruction (Item #9)
     * Page A corrupts and breaks apart (0-260ms), Page B materializes from fragments (260-500ms),
     * and becomes stable (500ms+).
     */
    public static void glitchPageTransition(View oldPage, View newPage, Runnable onComplete) {
        if (oldPage == null && newPage == null) {
            if (onComplete != null) onComplete.run();
            return;
        }

        if (!fxAllowed()) {
            if (oldPage != null) {
                oldPage.setVisibility(View.GONE);
                cleanView(oldPage);
            }
            if (newPage != null) {
                newPage.setVisibility(View.VISIBLE);
                cleanView(newPage);
            }
            if (onComplete != null) onComplete.run();
            return;
        }

        // Phase 1: 0–260ms - Page A corrupts, breaks apart, disintegrates
        if (oldPage != null && oldPage.getVisibility() == View.VISIBLE) {
            oldPage.animate().cancel();
            glitchTree(oldPage, MAJOR);
            glitchJitter(oldPage, MAJOR);

            oldPage.postDelayed(() -> oldPage.setAlpha(0.7f), 60);
            oldPage.postDelayed(() -> oldPage.setAlpha(0.2f), 140);
            oldPage.postDelayed(() -> oldPage.setAlpha(0.6f), 200);
            oldPage.postDelayed(() -> {
                oldPage.setAlpha(0f);
                oldPage.setVisibility(View.GONE);
                cleanView(oldPage);
            }, 260);
        }

        // Phase 2: 260–500ms - Page B materializes from fragments and reconstructs
        if (newPage != null) {
            H.postDelayed(() -> {
                newPage.setVisibility(View.VISIBLE);
                newPage.setAlpha(0.1f);
                glitchTree(newPage, MAJOR);
                glitchJitter(newPage, MAJOR);

                newPage.postDelayed(() -> newPage.setAlpha(0.85f), 70);
                newPage.postDelayed(() -> newPage.setAlpha(0.35f), 130);
                newPage.postDelayed(() -> newPage.setAlpha(0.9f), 180);
                newPage.postDelayed(() -> {
                    newPage.setAlpha(1f);
                    cleanView(newPage);
                    if (onComplete != null) onComplete.run();
                }, 240);
            }, 240);
        } else if (onComplete != null) {
            H.postDelayed(onComplete, 260);
        }
    }

    /**
     * Clear all temporary transforms/alpha/shadow layer and restore view state completely.
     */
    public static void cleanView(View v) {
        if (v == null) return;
        v.animate().cancel();
        v.setAlpha(1f);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
        v.setScaleX(1f);
        v.setScaleY(1f);
        v.setRotation(0f);
    }

    private static void collect(View v, ArrayList<Node> out) {
        if (v == null || v.getVisibility() != View.VISIBLE || v.getId() == R.id.logView) return;
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

    private static long restoreFor(int level) {
        if (level == MAJOR) return 160 + RND.nextInt(70);
        if (level == MEDIUM) return 120 + RND.nextInt(50);
        return 90 + RND.nextInt(40);
    }

    // ---------------- WANDER LOOP (subtle & rare per requirements) ----------------

    public static void start(Context ctx) {
        init(ctx);
        purge();
        if (running) return;
        running = true;
        // Subtle ambient wander: starts after 3-5 seconds
        scheduleTick(3000 + RND.nextInt(2000));
    }

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
                if (tv == null || tv.getId() == R.id.logView) continue;
                alive.add(n);
                if (n.hot) aliveHot.add(n);
            }
            if (!alive.isEmpty() && fxAllowed()) {
                // Ambient glitch is rare and subtle: 1 target per tick every 4-7 seconds
                Node n = pick(alive, aliveHot);
                if (n != null) burst(n, MINOR);
            }
            scheduleTick(4000 + RND.nextInt(3000));
            scheduleRestore(120);
        }
    };

    private static final Runnable RESTORE = new Runnable() {
        @Override public void run() {
            restoreQueued = false;
            restoreNow();
        }
    };

    private static Node pick(ArrayList<Node> alive, ArrayList<Node> hot) {
        if (!hot.isEmpty() && RND.nextInt(100) < 60) {
            return hot.get(RND.nextInt(hot.size()));
        }
        return alive.get(RND.nextInt(alive.size()));
    }

    private static void burst(Node node, int level) {
        TextView tv = node.ref.get();
        if (tv == null || tv.getVisibility() != View.VISIBLE || tv.getId() == R.id.logView) return;
        CharSequence cur = tv.getText();
        String s = cur == null ? "" : cur.toString();
        if (s.trim().isEmpty()) return;

        if (node.spannable) {
            CharSequence base = PENDING.containsKey(tv) ? PENDING.get(tv) : cur;
            int len = base.length();
            if (len > 0) {
                SpannableString sp = new SpannableString(base);
                int ranges = level == MAJOR ? 3 + RND.nextInt(2)
                        : level == MEDIUM ? 2 + RND.nextInt(2)
                        : 1 + RND.nextInt(2);
                if (len < 8 && ranges > 1) ranges = 1;
                int strikeP = level == MAJOR ? 3 : level == MEDIUM ? 4 : 5;
                int blockP = level == MAJOR ? 3 : level == MEDIUM ? 4 : 6;
                int made = 0;
                for (int r = 0; r < ranges && made < ranges; r++) {
                    int start = RND.nextInt(len);
                    int end = Math.min(len, start + 1 + (RND.nextInt(3) == 0 ? 1 : 0));
                    if (end <= start) continue;
                    sp.setSpan(new ForegroundColorSpan(
                                    PALETTE[RND.nextInt(PALETTE.length)]),
                            start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (RND.nextInt(strikeP) == 0) {
                        sp.setSpan(new StrikethroughSpan(),
                                start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
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
            tv.setShadowLayer(1.2f * d, 2f * d, 0f, BURST_SHADOW_RED);
            final TextView ftv = tv;
            final Node fn = node;
            ftv.postDelayed(() -> {
                TextView v2 = fn.ref.get();
                if (v2 != null && SHADOWED.get(v2) == fn) {
                    v2.setShadowLayer(1.2f * d, -2f * d, 0f, BURST_SHADOW_CYAN);
                }
            }, 45);
            SHADOWED.put(tv, node);
        } else {
            boolean left = RND.nextBoolean();
            tv.setShadowLayer(1.2f * d, (left ? -2f : 2f) * d, 0f,
                    left ? BURST_SHADOW_CYAN : BURST_SHADOW_RED);
            SHADOWED.put(tv, node);
        }

        if (node.spannable && level >= MEDIUM && RND.nextInt(level == MAJOR ? 3 : 4) == 0) {
            tv.setScaleX(RND.nextBoolean() ? 1.015f : 0.985f);
            final TextView stv = tv;
            stv.postDelayed(() -> stv.setScaleX(1f), 80 + RND.nextInt(50));
        }
    }

    private static void restoreNow() {
        if (!PENDING.isEmpty()) {
            for (Iterator<Map.Entry<TextView, CharSequence>> it = PENDING.entrySet().iterator(); it.hasNext();) {
                Map.Entry<TextView, CharSequence> e = it.next();
                TextView tv = e.getKey();
                CharSequence base = e.getValue();
                it.remove();
                if (tv == null) continue;
                if (TextUtils.equals(tv.getText(), base)) {
                    sApplying = true;
                    tv.setText(base);
                    sApplying = false;
                }
            }
        }
        if (!SHADOWED.isEmpty()) {
            for (Iterator<Map.Entry<TextView, Node>> it = SHADOWED.entrySet().iterator(); it.hasNext();) {
                Map.Entry<TextView, Node> e = it.next();
                TextView tv = e.getKey();
                Node n = e.getValue();
                it.remove();
                if (tv == null || n == null) continue;
                float d = DENSITY;
                if (n.hasShadow) {
                    tv.setShadowLayer(n.baseRadius, n.baseDx, n.baseDy, n.baseShadowColor);
                } else {
                    tv.setShadowLayer(0f, 0f, 0f, 0);
                }
            }
        }
    }

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

    private static final class GlitchWatcher implements TextWatcher {
        private final Node node;

        GlitchWatcher(Node node) { this.node = node; }

        @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            if (sApplying || !fxAllowed()) return;
            TextView tv = node.ref.get();
            if (tv == null || tv.getId() == R.id.logView) return;

            // Delete / Backspace detection: character(s) removed
            if (b > c) {
                if (tv instanceof EditText) {
                    EditText et = (EditText) tv;
                    float d = DENSITY;
                    // Temporary corrosion shadow layer on backspace
                    et.setShadowLayer(2f * d, -2f * d, 0f, BURST_SHADOW_RED);
                    H.postDelayed(() -> {
                        et.setShadowLayer(0f, 0f, 0f, 0);
                    }, 80);
                } else {
                    burst(node, MINOR);
                    scheduleRestore(restoreFor(MINOR));
                }
            }
        }

        @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
            if (sApplying || !fxAllowed()) return;
            TextView tv = node.ref.get();
            if (tv == null || tv.getId() == R.id.logView) return;

            if (tv instanceof EditText) {
                // Typing (addition): apply precise shadow flash on EditText without modifying text/spans
                if (count > before) {
                    float d = DENSITY;
                    tv.setShadowLayer(1.5f * d, 1.5f * d, 0f, BURST_SHADOW_RED);
                    H.postDelayed(() -> tv.setShadowLayer(0f, 0f, 0f, 0), 60);
                }
            } else {
                H.postDelayed(() -> {
                    if (sApplying || !fxAllowed()) return;
                    TextView target = node.ref.get();
                    if (target == null || target.getId() == R.id.logView) return;
                    burst(node, MINOR);
                    scheduleRestore(restoreFor(MINOR));
                }, 25 + RND.nextInt(35));
            }
        }

        @Override public void afterTextChanged(android.text.Editable s) {}
    }
}
