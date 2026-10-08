package com.wpmulti.test;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.color.MaterialColors;

/**
 * Grafik trafik real-time (RX colorPrimary, TX colorTertiary - ikut tema
 * light/night palet orkid). Ring buffer 60 sampel; tiap
 * addSample(rxBytes, txBytes) menggeser. Digambar sebagai line chart
 * dengan skala otomatis.
 *
 * Fase 3: teks memakai ukuran dp (dulu 24px mentah - nyaris tak terbaca
 * di layar densitas tinggi), legend RX/TX digabung SATU baris horizontal
 * di kiri-atas dengan lebar terukur (dulu dua baris bertumpuk di kiri
 * bawah dan saling menimpa saat rate panjang), dan label puncak pindah
 * ke kanan-bawah agar tidak menabrak legend.
 */
public class TrafficGraphView extends View {
    private static final int CAP = 60;
    private final long[] rx = new long[CAP];
    private final long[] tx = new long[CAP];
    private int count = 0;
    // cy10.6: konservatif-sticky — true bila pernah ada sampel bersebelahan
    // yang beda nilai (bisa tetap true setelah pasangan tsb lepas oleh
    // geser buffer; verifikasi homogenitas penuh dilakukan hanya saat
    // kandidat skip — lihat needsRedraw).
    private boolean mVaries = false;

    private final Paint rxPaint = new Paint();
    private final Paint txPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint textPaint = new Paint();
    private final float d; // faktor densitas untuk ukuran dp -> px

    public TrafficGraphView(Context ctx) {
        super(ctx);
        d = getResources().getDisplayMetrics().density;
        init();
    }

    public TrafficGraphView(Context ctx, AttributeSet a) {
        super(ctx, a);
        d = getResources().getDisplayMetrics().density;
        init();
    }

    private void init() {
        // Warna ikut tema (palet orkid light/night). colorPrimary
        // dideklarasikan appcompat; attr M3 lain via material R.
        // Tidak ada hex hardcoded di sini.
        rxPaint.setColor(attr(androidx.appcompat.R.attr.colorPrimary));
        rxPaint.setStrokeWidth(3f * d);
        rxPaint.setStyle(Paint.Style.STROKE);
        rxPaint.setAntiAlias(true);
        txPaint.setColor(attr(com.google.android.material.R.attr.colorTertiary));
        txPaint.setStrokeWidth(3f * d);
        txPaint.setStyle(Paint.Style.STROKE);
        txPaint.setAntiAlias(true);
        gridPaint.setColor(ColorUtils.setAlphaComponent(
                attr(com.google.android.material.R.attr.colorOutline), 0x33));
        gridPaint.setStrokeWidth(1f);
        textPaint.setColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        textPaint.setTextSize(12f * d);
        textPaint.setAntiAlias(true);
    }

    /** Resolve warna attr tema aktif; aman dipanggil dari constructor
     *  (resolusi murni via context theme, tanpa perlu view ter-attach). */
    private int attr(int a) {
        return MaterialColors.getColor(this, a);
    }

    /**
     * Tambah sampel (byte kumulatif); dihitung delta per detik oleh caller.
     * cy10.6: invalidate hanya bila piksel BISA berubah — buffer penuh yang
     * seluruhnya datar (semua sampel RX homogen & semua TX homogen) dengan
     * nilai baru sama seperti terakhir menghasilkan grafik/legend/label
     * puncak yang identik piksel demi piksel -> redraw dilewati
     * (rendering event-driven: tidak ada perubahan visual = tidak ada
     * frame). Ada variasi apa pun / fase tumbuh (kurva muncul) ->
     * invalidate persis seperti dulu.
     */
    public synchronized void addSample(long rxBytes, long txBytes) {
        insert(rxBytes, txBytes);
        if (needsRedraw()) postInvalidate();
    }

    /**
     * cy10.6: sisipkan sampel TANPA invalidate — dipakai thread monitor
     * saat activity di-background: kontinuitas timeline grafik terjaga
     * (data tersisip persis seperti perilaku lama), tanpa kerja render
     * pada view yang tidak tergambar. Redraw terjadi otomatis pada sampel
     * berikutnya lewat addSample, atau saat window digambar ulang pada
     * resume (surface dibuat ulang -> full draw dengan data lengkap).
     */
    synchronized void insert(long rxBytes, long txBytes) {
        if (count > 0
                && (rxBytes != rx[count - 1] || txBytes != tx[count - 1])) {
            mVaries = true;
        }
        if (count < CAP) {
            rx[count] = rxBytes;
            tx[count] = txBytes;
            count++;
        } else {
            System.arraycopy(rx, 1, rx, 0, CAP - 1);
            System.arraycopy(tx, 1, tx, 0, CAP - 1);
            rx[CAP - 1] = rxBytes;
            tx[CAP - 1] = txBytes;
        }
    }

    /**
     * Apakah hasil gambar berpotensi berubah? Fase tumbuh (count < CAP)
     * selalu ya. Buffer penuh + flag mVaries = ya. Buffer penuh + flag
     * false = verifikasi homogenitas penuh O(CAP) (<=120 perbandingan
     * long, jauh lebih murah dari satu pass render) sebelum memutuskan
     * tidak redraw — flag konservatif tidak pernah menyebabkan skip
     * yang salah, hanya invalidasi berlebih yang kemudian lurus sendiri.
     */
    private synchronized boolean needsRedraw() {
        if (count < CAP) return true;
        if (mVaries) return true;
        for (int i = 1; i < count; i++) {
            if (rx[i] != rx[0] || tx[i] != tx[0]) {
                mVaries = true;
                return true;
            }
        }
        return false;
    }

    public synchronized void clear() {
        count = 0;
        mVaries = false;
        postInvalidate();
    }

    @Override
    protected synchronized void onDraw(Canvas c) {
        super.onDraw(c);
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        // grid
        for (int i = 1; i < 4; i++) {
            float y = h * i / 4f;
            c.drawLine(0, y, w, y, gridPaint);
        }
        float pad = 8 * d;
        float baseline = 16 * d; // baseline teks legend (kiri-atas)
        if (count < 2) {
            c.drawText("menunggu data...", pad, h / 2f, textPaint);
            return;
        }
        long max = 1;
        for (int i = 0; i < count; i++) {
            if (rx[i] > max) max = rx[i];
            if (tx[i] > max) max = tx[i];
        }
        // Legend SATU baris horizontal: bullet RX + teks, bullet TX + teks.
        // Kalau muat, legend memuat rate terkini; kalau tidak (layar
        // sempit / rate panjang), cukup label RX/TX agar tidak bertumpuk.
        float dotR = 3 * d;
        float gap = 5 * d;
        String rxTxt = "RX " + fmtRate(rx[count - 1]) + "/s";
        String txTxt = "TX " + fmtRate(tx[count - 1]) + "/s";
        float legendW = dotR * 2 + gap + textPaint.measureText(rxTxt)
                + gap + dotR * 2 + gap + textPaint.measureText(txTxt);
        if (legendW > w - 2 * pad) {
            rxTxt = "RX";
            txTxt = "TX";
        }
        float cur = pad;
        c.drawCircle(cur + dotR, baseline - 4 * d, dotR, rxPaint);
        cur += dotR * 2 + gap;
        c.drawText(rxTxt, cur, baseline, textPaint);
        cur += textPaint.measureText(rxTxt) + gap;
        c.drawCircle(cur + dotR, baseline - 4 * d, dotR, txPaint);
        cur += dotR * 2 + gap;
        c.drawText(txTxt, cur, baseline, textPaint);
        // Label puncak di kanan-bawah (dulu kiri-atas, menabrak legend).
        String peak = "puncak " + fmtRate(max) + "/s";
        c.drawText(peak, w - pad - textPaint.measureText(peak),
                h - pad, textPaint);
        float dx = (float) w / (CAP - 1);
        // RX line
        for (int i = 1; i < count; i++) {
            float x0 = (i - 1) * dx, x1 = i * dx;
            float y0 = h - (h * 0.85f * rx[i - 1] / max) - 8;
            float y1 = h - (h * 0.85f * rx[i] / max) - 8;
            c.drawLine(x0, y0, x1, y1, rxPaint);
        }
        // TX line
        for (int i = 1; i < count; i++) {
            float x0 = (i - 1) * dx, x1 = i * dx;
            float y0 = h - (h * 0.85f * tx[i - 1] / max) - 8;
            float y1 = h - (h * 0.85f * tx[i] / max) - 8;
            c.drawLine(x0, y0, x1, y1, txPaint);
        }
    }

    private static String fmtRate(long bps) {
        if (bps < 1024) return bps + " B";
        if (bps < 1048576) return String.format("%.1f KB", bps / 1024.0);
        return String.format("%.1f MB", bps / 1048576.0);
    }
}
