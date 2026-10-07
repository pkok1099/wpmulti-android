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
 */
public class TrafficGraphView extends View {
    private static final int CAP = 60;
    private final long[] rx = new long[CAP];
    private final long[] tx = new long[CAP];
    private int count = 0;

    private final Paint rxPaint = new Paint();
    private final Paint txPaint = new Paint();
    private final Paint gridPaint = new Paint();
    private final Paint textPaint = new Paint();

    public TrafficGraphView(Context ctx) {
        super(ctx);
        init();
    }

    public TrafficGraphView(Context ctx, AttributeSet a) {
        super(ctx, a);
        init();
    }

    private void init() {
        // Warna ikut tema (palet orkid light/night). colorPrimary
        // dideklarasikan appcompat; attr M3 lain via material R.
        // Tidak ada hex hardcoded di sini.
        rxPaint.setColor(attr(androidx.appcompat.R.attr.colorPrimary));
        rxPaint.setStrokeWidth(3f);
        rxPaint.setStyle(Paint.Style.STROKE);
        rxPaint.setAntiAlias(true);
        txPaint.setColor(attr(com.google.android.material.R.attr.colorTertiary));
        txPaint.setStrokeWidth(3f);
        txPaint.setStyle(Paint.Style.STROKE);
        txPaint.setAntiAlias(true);
        gridPaint.setColor(ColorUtils.setAlphaComponent(
                attr(com.google.android.material.R.attr.colorOutline), 0x33));
        gridPaint.setStrokeWidth(1f);
        textPaint.setColor(attr(com.google.android.material.R.attr.colorOnSurfaceVariant));
        textPaint.setTextSize(24f);
    }

    /** Resolve warna attr tema aktif; aman dipanggil dari constructor
     *  (resolusi murni via context theme, tanpa perlu view ter-attach). */
    private int attr(int a) {
        return MaterialColors.getColor(this, a);
    }

    /** Tambah sampel (byte kumulatif); dihitung delta per detik oleh caller. */
    public synchronized void addSample(long rxBytes, long txBytes) {
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
        postInvalidate();
    }

    public synchronized void clear() {
        count = 0;
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
        if (count < 2) {
            c.drawText("menunggu data...", 16, h / 2f, textPaint);
            return;
        }
        long max = 1;
        for (int i = 0; i < count; i++) {
            if (rx[i] > max) max = rx[i];
            if (tx[i] > max) max = tx[i];
        }
        // label max
        c.drawText(fmtRate(max) + "/s", 8, 28, textPaint);
        c.drawText("RX", 8, h - 40, rxPaint);
        c.drawText("TX", 8, h - 12, txPaint);
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
