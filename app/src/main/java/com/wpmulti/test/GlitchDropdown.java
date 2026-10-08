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
 * GlitchDropdown (cy8) - dropdown yang BENAR-BENAR bagian dari bahasa
 * visual glitch, menggantikan popup platform Spinner yang tidak bisa
 * dianimasikan:
 *
 *  OPEN    : window flicker (glitch_materialize) + scanline menyapu
 *            popup + item materialize staggered (alpha flicker +
 *            displacement bergeser waktu) + burst teks MEDIUM.
 *  OPENED  : pulse mikro berkala (380-650ms) pada SATU item acak +
 *            shimmer scanline tipis tiap beberapa pulse - dropdown
 *            terasa "unstable" TANPA mengganggu pemilihan.
 *  SELECT  : kilat scanline + item-list korupsi (vanish staggered)
 *            lalu popup ditutup; Spinner.setSelection memicu listener
 *            state nyata.
 *  DISMISS : tap luar / back -> windowExitAnimation (glitch_disintegrate)
 *            via style popup.
 *
 * CATATAN cy8: kelas ini sebelumnya TIDAK PERNAH terpanggil (cabang
 * Spinner di GlitchText.installTouch tertutup cabang ViewGroup yang
 * selalu return) - itulah sebabnya dropdown masih terasa seperti UI
 * Android biasa. Kini benar-benar aktif.
 *
 * FIX cy8.1 (bug "semua dropdown tidak muncul"): pw.setContentView(lv)
 * TIDAK pernah dipanggil + setWidth/setHeight tidak di-set -> guard
 * mContentView == null di PopupWindow membuat showAsDropDown()/
 * showAtLocation() no-op SENYAP (tanpa crash), sementara sentuhan
 * spinner sudah dikonsumsi listener -> tidak ada dropdown sama
 * sekali. Content view + ukuran window kini dipasang eksplisit.
 *
 * State tetap milik Spinner + adapter-nya (minimal-invasif): popup hanya
 * lapisan presentasi; nilai terpilih tetap dibaca dari Spinner. Tap
 * spinner lagi saat terbuka = toggle tutup. Mode efek OFF / (AUTO +
 * animator sistem 0) -> popup polos tanpa efek (state tetap benar).
 * cy9: keputusan efek = GlitchText.isGlitchEnabled() (satu fungsi pusat).
 */
final class GlitchDropdown {

    private static PopupWindow sOpen;
    private static ListView sOpenList;
    private static boolean sClosing;
    private static int sPulseCount;
    private static final Handler H = new Handler(Looper.getMainLooper());

    private GlitchDropdown() {}

    /** Pulse berkala: satu item acak mikro-terkorupsi selama terbuka. */
    private static final Runnable sPulse = new Runnable() {
        @Override public void run() {
            if (sOpen == null || sOpenList == null) return;
            GlitchText.dropdownPulse(sOpenList);
            // tiap pulse ke-4: shimmer scanline tipis sesaat - dropdown
            // "bernapas" tanpa guncangan konstan.
            if (++sPulseCount % 4 == 0
                    && GlitchText.isGlitchEnabled()) {
                GlitchText.scanline(sOpenList, 90, 70);
            }
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

        final boolean fx = GlitchText.isGlitchEnabled();
        final float d = sp.getResources().getDisplayMetrics().density;
        final ListView lv = new ListView(sp.getContext());
        lv.setAdapter(la);
        lv.setDivider(null);
        lv.setItemsCanFocus(true);
        lv.setBackgroundResource(R.drawable.bg_dropdown);
        lv.setVerticalScrollBarEnabled(false);
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // Selector default (highlight item yang ditekan) DIPERTAHANKAN juga
        // saat efek mati - itu feedback fungsional pilihan item, bukan
        // dekorasi glitch (aksesibilitas: state tetap terbaca jelas).

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
            // hygiene: overlay & efek pada item popup dilepas bersih -
            // tidak ada langkah tertunda yang tersisa pada view mati.
            GlitchText.clearScanline(lv);
            GlitchText.cancelFor(lv);
            for (int i = 0; i < lv.getChildCount(); i++) {
                GlitchText.cancelFor(lv.getChildAt(i));
            }
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

        // FIX cy8.1 - INTI perbaikan "semua dropdown tidak muncul":
        // PopupWindow HARUS diberi content view SEBELUM show*(); tanpa
        // itu showAsDropDown()/showAtLocation() no-op senyap (guard
        // mContentView == null di AOSP). Ukuran window juga eksplisit:
        // lebar >= lebar spinner, tinggi hasil pengukuran ter-cap 280dp
        // (WRAP_CONTENT bila pengukuran belum tersedia) - tanpa ini
        // popup WRAP_CONTENT bisa mengabaikan cap 280dp.
        pw.setContentView(lv);
        pw.setWidth(w);
        pw.setHeight(h);
        if (spaceBelow >= h + (int) (8 * d)) {
            pw.showAsDropDown(sp, 0, (int) (2 * d));
        } else {
            pw.showAtLocation(sp.getRootView(), Gravity.NO_GRAVITY,
                    loc[0], Math.max(0, loc[1] - h - (int) (6 * d)));
        }
        sOpen = pw;
        sOpenList = lv;
        sPulseCount = 0;

        if (fx) {
            // Materialize (setelah popup benar-benar layout):
            // scanline menyapu + burst teks + fragment item menyala
            // staggered dgn displacement. Teks popup didaftarkan agar
            // pulse punya span korupsi nyata.
            lv.post(() -> {
                if (sOpen != pw) return; // sudah ditutup lagi
                GlitchText.registerTree(lv);
                GlitchText.scanline(lv, 320);
                GlitchText.glitchTree(lv, GlitchText.MEDIUM);
                GlitchText.materializeStaggered(lv);
                H.removeCallbacks(sPulse);
                H.postDelayed(sPulse, 480);
            });
        }
    }

    /**
     * Tutup popup. selectMode=true -> kilat scanline + item-list korupsi
     * singkat (vanish staggered) sebelum dismiss; false (tap luar/back)
     * -> langsung dismiss (exit animation style yang menangani
     * disintegrate).
     */
    private static void close(boolean selectMode) {
        PopupWindow pw = sOpen;
        if (pw == null) return;
        H.removeCallbacks(sPulse);
        if (selectMode && GlitchText.isGlitchEnabled() && !sClosing
                && sOpenList != null) {
            sClosing = true;
            // disintegrate: kilat scanline + fragment pecah bergeser
            GlitchText.scanline(sOpenList, 140);
            GlitchText.glitchTree(sOpenList, GlitchText.MINOR);
            GlitchText.vanishStaggered(sOpenList);
            H.postDelayed(pw::dismiss, 150);
        } else {
            pw.dismiss();
        }
    }
}
