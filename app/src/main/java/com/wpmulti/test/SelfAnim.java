package com.wpmulti.test;

import android.os.SystemClock;
import android.view.Choreographer;

/**
 * SelfAnim (cy9) - penggerak animasi mini ber-CHOREOGRAPHER sendiri,
 * pengganti ValueAnimator / SpringAnimation sistem.
 *
 * KENAPA: efek app (denyut dot status, morph sudut tombol START&lt;-&gt;STOP,
 * bounce spring) sebelumnya digerakkan Animator sistem sehingga ikut mati
 * saat "skala animator" developer = 0. GlitchText sendiri sudah ber-Handler
 * (framework step()), jadi tidak terpengaruh - kelas ini menyamakan
 * kedudukan TIGA efek terakhir itu: jalan walau animator sistem = 0,
 * digerakkan frame-callback sendiri.
 *
 * HASIL VISUAL IDENTIK dgn versi Animator sistem:
 *  - ease()           = rumusan persis AccelerateDecelerateInterpolator
 *                       (default ValueAnimator).
 *  - ofDuration()     = padanan ValueAnimator satu-kali (durasi sama,
 *                       fraksi linear 0..1; kurva diterapkan pemanggil).
 *  - pulse()          = padanan ValueAnimator REVERSE + INFINITE
 *                       (denyut bolak-balik; fraksi arah 0..1).
 *  - spring()         = padanan SpringAnimation (DynamicAnimation):
 *                       solusi spring underdamped BENTUK-TERTUTUP dengan
 *                       stiffness/dampingRatio/minVisible yang sama,
 *                       berakhir PASTI di nilai final.
 *
 * KEAMANAN:
 *  - Token.cancel() idempoten; frame callback dilepas dari Choreographer.
 *  - Animasi satu-kali/spring SELALU berakhir di nilai final (tidak ada
 *    alpha/scale/sudut tertinggal di nilai antara).
 *  - Hanya dipanggil dari main thread (Choreographer main looper).
 *  - Tidak menyentuh input/estado view - murni lapisan presentasi.
 */
final class SelfAnim {

    private SelfAnim() {}

    /**
     * Kurva AccelerateDecelerateInterpolator (default ValueAnimator) -
     * rumus persis framework, hasil visual tidak berubah.
     */
    static float ease(float t) {
        if (t <= 0f) return 0f;
        if (t >= 1f) return 1f;
        return (float) (Math.cos((t + 1) * Math.PI) / 2.0) + 0.5f;
    }

    /** Penerima nilai animasi per-frame. */
    interface Eval { void eval(float v); }

    /** Token animasi berjalan; cancel() idempoten dan thread-utama saja. */
    static final class Token {
        private Choreographer.FrameCallback cb;
        private boolean done;

        final void cancel() {
            done = true;
            Choreographer.FrameCallback c = cb;
            cb = null;
            if (c != null) {
                try {
                    Choreographer.getInstance().removeFrameCallback(c);
                } catch (Exception ignored) {}
            }
        }

        boolean isRunning() { return !done; }
    }

    /** Re-post helper (callback menimpa dirinya sendiri tiap frame). */
    private static void repost(Token tk, Choreographer.FrameCallback cb) {
        if (tk.done) { tk.cb = null; return; }
        Choreographer.getInstance().postFrameCallback(cb);
    }

    /**
     * Animasi SATU KALI berdurasi tetap - padanan ValueAnimator(duration).
     * Eval menerima fraksi WAKTU linear 0..1 (pemanggil menerapkan ease()
     * sesuai kurva yang diinginkan). Berakhir PASTI di 1f.
     */
    static Token ofDuration(final long durationMs, final Eval ev) {
        final Token tk = new Token();
        final long t0 = SystemClock.uptimeMillis();
        tk.cb = new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                if (tk.done) { tk.cb = null; return; }
                float t = (SystemClock.uptimeMillis() - t0)
                        / (float) Math.max(1L, durationMs);
                if (t >= 1f) {
                    tk.done = true;
                    tk.cb = null;
                    ev.eval(1f); // nilai final pasti tercapai
                    return;
                }
                ev.eval(t);
                repost(tk, this);
            }
        };
        Choreographer.getInstance().postFrameCallback(tk.cb);
        return tk;
    }

    /**
     * Denyut bolak-balik tanpa akhir - padanan ValueAnimator REVERSE +
     * INFINITE. Eval menerima fraksi ARAH 0..1: 0 = nilai awal siklus,
     * 1 = nilai akhir siklus; tiap setengah-periode berikutnya arah
     * dibalik (cermin), persis perilaku REVERSE.
     */
    static Token pulse(final long halfPeriodMs, final Eval ev) {
        final Token tk = new Token();
        final long t0 = SystemClock.uptimeMillis();
        tk.cb = new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                if (tk.done) { tk.cb = null; return; }
                long el = SystemClock.uptimeMillis() - t0;
                float ph = (el % (2f * halfPeriodMs)) / (float) halfPeriodMs;
                ev.eval(ph > 1f ? 2f - ph : ph);
                repost(tk, this);
            }
        };
        Choreographer.getInstance().postFrameCallback(tk.cb);
        return tk;
    }

    /**
     * Spring underdamped bentuk-tertutup - padanan SpringAnimation dengan
     * SpringForce(final=to, stiffness, dampingRatio) dari kecepatan awal 0.
     * Rumus: x(t) = to + (from-to) * e^(-zeta*wn*t) * [cos(wd*t)
     *          + (zeta*wn/wd) * sin(wd*t)]
     * dengan wn = sqrt(stiffness), wd = wn*sqrt(1-zeta^2). Selesai saat
     * |x-to| &lt; minVisible (setara MIN_VISIBLE_CHANGE DynamicAnimation,
     * scale = 1/500) -> snap tepat ke nilai final; hard-cap 3 dtk.
     */
    static Token spring(final float from, final float to,
            final float stiffness, final float dampingRatio,
            final float minVisible, final Eval ev) {
        final Token tk = new Token();
        final long t0 = SystemClock.uptimeMillis();
        final float wn = (float) Math.sqrt(Math.max(1f, stiffness));
        final float zeta = Math.min(0.999f, Math.max(0f, dampingRatio));
        final float zwn = zeta * wn;
        final float wd = wn * (float) Math.sqrt(1f - zeta * zeta);
        tk.cb = new Choreographer.FrameCallback() {
            @Override public void doFrame(long frameTimeNanos) {
                if (tk.done) { tk.cb = null; return; }
                float t = (SystemClock.uptimeMillis() - t0) / 1000f;
                float env = (float) Math.exp(-zwn * t);
                float x = to + (from - to) * env
                        * ((float) Math.cos(wd * t)
                                + (zwn / wd) * (float) Math.sin(wd * t));
                if (Math.abs(x - to) < minVisible || t > 3f) {
                    tk.done = true;
                    tk.cb = null;
                    ev.eval(to); // final PASTI - tidak ada skala tertinggal
                    return;
                }
                ev.eval(x);
                repost(tk, this);
            }
        };
        Choreographer.getInstance().postFrameCallback(tk.cb);
        return tk;
    }
}
