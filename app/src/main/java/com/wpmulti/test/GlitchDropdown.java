package com.wpmulti.test;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.Spinner;

/**
 * GlitchDropdown (cy7) - dropdown yang BENAR-BENAR bagian dari bahasa
 * visual glitch, menggantikan popup platform Spinner yang tidak bisa
 * dianimasikan:
 *
 *  OPEN    : popup materialize staggered (item menyala bergeser waktu)
 *            + burst teks MEDIUM sekali di seluruh item.
 *  OPENED  : pulse mikro berkala (380-650ms) pada SATU item acak -
 *            dropdown terasa "unstable" tanpa mengganggu pemilihan.
 *  SELECT  : item-list korupsi singkat (vanish staggered) lalu popup
 *            ditutup; Spinner.setSelection memicu listener state nyata.
 *  DISMISS : tap luar / back -> windowExitAnimation (glitch_disintegrate)
 *            via style popup.
 *
 * State tetap milik Spinner + adapter-nya (minimal-invasif): popup hanya
 * lapisan presentasi; nilai terpilih tetap dibaca dari Spinner. Tap
 * spinner lagi saat terbuka = toggle tutup. animScale 0 -> popup polos
 * tanpa efek (state tetap benar).
 */
final class GlitchDropdown {

    private static PopupWindow sOpen;
    private static ListView sOpenList;
    private static boolean sClosing;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private GlitchDropdown() {}

    /** Pulse berkala: satu item acak mikro-terkorupsi selama terbuka. */
    private static final Runnable sPulse = new Runnable() {
        @Override public void run() {
            if (sOpen == null || sOpenList == null) return;
            GlitchText.dropdownPulse(sOpenList);
            H.postDelayed(this, 380 + GlitchText.rndInt(270));
        }
    };

    /** Buka dropdown glitch utk Spinner (dipanggil GlitchText.installTouch). */
    static void show(Spinner sp) {
        // Toggle: sudah terbuka -> tutup dulu (jangan tumpuk popup).
        if (sOpen != null) { close(true); return; }
        android.widget.ListAdapter la;
        try {
            la = (android.widget.ListAdapter) sp.getAdapter();
        } catch (ClassCastException e) {
            la = null;
        }
        if (la == null || la.getCount() == 0) return;

        final boolean fx = GlitchText.isFxAllowed();
        final float d = sp.getResources().getDisplayMetrics().density;
        final ListView lv = new ListView(sp.getContext());
        lv.setAdapter(la);
        lv.setDivider(null);
        lv.setItemsCanFocus(true);
        lv.setBackgroundResource(R.drawable.bg_dropdown);
        lv.setVerticalScrollBarEnabled(false);
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        if (!fx) lv.setSelector(new android.graphics.drawable.ColorDrawable(
                Color.TRANSPARENT));

        final PopupWindow pw = new PopupWindow(sp.getContext());
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);
        pw.setClippingEnabled(true);
        if (fx) pw.setAnimationStyle(R.style.GlitchPopupWindow);

        // Lebar popup >= spinner; tinggi diukur & di-cap (max ~280dp).
        final int w = Math.max(sp.getWidth(),
                (int) (220 * d));
        int hSpec = View.MeasureSpec.makeMeasureSpec(0,
                View.MeasureSpec.UNSPECIFIED);
        lv.measure(View.MeasureSpec.makeMeasureSpec(w,
                        View.MeasureSpec.EXACTLY), hSpec);
        int h = Math.min(lv.getMeasuredHeight(), (int) (280 * d));

        final int selPos = sp.getSelectedItemPosition();
        lv.setOnItemClickListener((parent, view, pos, id) -> {
            close(true);
            // Posisi berubah -> listener Spinner terpicu (state nyata);
            // posisi sama -> cukup glitch tutup, tidak ada state baru.
            if (pos != selPos) sp.setSelection(pos);
        });

        pw.setOnDismissListener(() -> {
            H.removeCallbacks(sPulse);
            if (sOpen == pw) {
                sOpen = null;
                sOpenList = null;
            }
            sClosing = false;
        });

        // Posisi: di bawah spinner; tidak muat -> di atas spinner.
        int[] loc = new int[2];
        sp.getLocationOnScreen(loc);
        int screenH = sp.getResources().getDisplayMetrics().heightPixels;
        int spaceBelow = screenH - (loc[1] + sp.getHeight());
        if (h <= 0) h = ViewGroup.LayoutParams.WRAP_CONTENT;
        if (spaceBelow >= h + (int) (8 * d)) {
            pw.showAsDropDown(sp, 0, (int) (2 * d));
        } else {
            pw.showAtLocation(sp.getRootView(), Gravity.NO_GRAVITY,
                    loc[0], Math.max(0, loc[1] - h - (int) (6 * d)));
        }
        sOpen = pw;
        sOpenList = lv;

        if (fx) {
            // Materialize: burst teks + fragment item menyala staggered;
            // daftarkan teks popup agar pulse punya span korupsi nyata.
            lv.post(() -> {
                if (sOpen != pw) return; // sudah ditutup lagi
                GlitchText.registerTree(lv);
                GlitchText.glitchTree(lv, GlitchText.MEDIUM);
                GlitchText.materializeStaggered(lv);
                H.removeCallbacks(sPulse);
                H.postDelayed(sPulse, 420);
            });
        }
    }

    /**
     * Tutup popup. selectMode=true -> item-list korupsi singkat (vanish
     * staggered) sebelum dismiss; false (tap luar/back) -> langsung
     * dismiss (exit animation style yang menangani disintegrate).
     */
    private static void close(boolean selectMode) {
        PopupWindow pw = sOpen;
        if (pw == null) return;
        H.removeCallbacks(sPulse);
        if (selectMode && GlitchText.isFxAllowed() && !sClosing
                && sOpenList != null) {
            sClosing = true;
            GlitchText.glitchTree(sOpenList, GlitchText.MINOR);
            GlitchText.vanishStaggered(sOpenList);
            H.postDelayed(pw::dismiss, 90);
        } else {
            pw.dismiss();
        }
    }
}
