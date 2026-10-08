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
import android.widget.SpinnerAdapter;

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
 * showAtLocation() no-op SENYAP (tanpa crash). Content view + ukuran
 * window kini dipasang eksplisit.
 *
 * FIX cy10 (bug "popup setinggi satu baris, opsi terpotong, teks
 * menimpa teks di belakang") - AKAR MASALAH ganda:
 *  1. ListView diukur dgn mode tinggi UNSPECIFIED - pada AOSP
 *     ListView.onMeasure, mode UNSPECIFIED HANYA mengukur satu anak
 *     (tinggi = listPadding + childHeight + fading edge) -> pw.setHeight
 *     menerima tinggi ~satu baris. Kini diukur AT_MOST terhadap cap:
 *     SEMUA item dihitung setelah adapter terpasang (tinggi = jumlah
 *     item), di-cap 280dp dan ruang layar tersedia (bawah/atas
 *     trigger, mana yang lebih besar) - TIDAK memakai tinggi view
 *     pemicu.
 *  2. ListView popup memanggil adapter.getView() (bukan
 *     getDropDownView()) -> selama ini baris popup memakai layout
 *     "closed" simple_spinner_item tanpa padding. Adapter jembatan
 *     di bawah memetakan getView -> getDropDownView sehingga baris
 *     popup = layout dropdown (padding + tipografi cybercore).
 *  3. Fading edge vertikal dimatikan - gradien transparan di tepi
 *     atas/bawah adalah "jendela tembus" yang membuat teks konten di
 *     belakang popup terlihat menimpa.
 * Cleanup tidak pernah bergantung pada callback Animator sistem
 * (semua via Handler + cancelFor eksplisit) -> aman saat animator
 * scale sistem = 0.
 *
 * State tetap milik Spinner + adapter-nya (minimal-invasif): popup hanya
 * lapisan presentasi; nilai terpilih tetap dibaca dari Spinner. Tap
 * spinner lagi saat terbuka = toggle tutup. Mode efek OFF / (AUTO +
 * animator sistem 0) -> popup polos TANPA window animation (style
 * 0-duration), state tetap benar. cy9: keputusan efek =
 * GlitchText.isGlitchEnabled() (satu fungsi pusat).
 */
final class GlitchDropdown {

    private static PopupWindow sOpen;
    private static ListView sOpenList;
    private static boolean sClosing;
    private static int sPulseCount;
    private static final Handler H = new Handler(Looper.getMainLooper());

    // cy10.2: Handler ini MILIK SENDIRI (terpisah dari GlitchText.H)
    // sehingga tidak ikut dibersihkan GlitchText.stop() di onPause -
    // dulunya pulse terus berdetak tiap 380-650ms di background selama
    // dropdown dibiakan terbuka (boros CPU/baterai, tak terlihat siapa
    // pun). pausePulse()/resumePulse() dipanggil MainActivity.onPause/
    // onResume. Visual identik: popup tak terlihat saat background;
    // denyut melanjutkan kadensi acak yang sama setelah kembali.

    /** Hentikan loop pulse (onPause) - popup tetap terbuka, beku diam. */
    static void pausePulse() {
        H.removeCallbacks(sPulse);
    }

    /** Lanjutkan pulse bila popup masih terbuka dan efek aktif (onResume). */
    static void resumePulse() {
        if (sOpen != null && sOpenList != null && sOpen.isShowing()
                && GlitchText.isGlitchEnabled()) {
            H.removeCallbacks(sPulse);
            H.postDelayed(sPulse, 380 + GlitchText.rndInt(270));
        }
    }

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
        // FIX cy10 #2: jembatan getView -> getDropDownView: baris popup
        // memakai layout DROPDOWN adapter (padding + tipografi), bukan
        // layout "closed" spinner. Dibungkus tipis - state tetap milik
        // adapter yang sama (selected item Spinner tidak tersentuh).
        final android.widget.ListAdapter base = la;
        final boolean hasDropDown = la instanceof SpinnerAdapter;
        android.widget.ListAdapter popupAd = new android.widget.ListAdapter() {
            @Override public int getCount() { return base.getCount(); }
            @Override public Object getItem(int position) {
                return base.getItem(position);
            }
            @Override public long getItemId(int position) {
                return base.getItemId(position);
            }
            @Override public boolean hasStableIds() {
                return base.hasStableIds();
            }
            @Override public View getView(int position, View cv,
                    ViewGroup parent) {
                return hasDropDown
                        ? ((SpinnerAdapter) base).getDropDownView(
                                position, cv, parent)
                        : base.getView(position, cv, parent);
            }
            @Override public int getItemViewType(int position) {
                return base.getItemViewType(position);
            }
            @Override public int getViewTypeCount() {
                return base.getViewTypeCount();
            }
            @Override public boolean isEmpty() { return base.isEmpty(); }
            @Override public boolean areAllItemsEnabled() {
                return base.areAllItemsEnabled();
            }
            @Override public boolean isEnabled(int position) {
                return base.isEnabled(position);
            }
            @Override public void registerDataSetObserver(
                    android.database.DataSetObserver observer) {
                base.registerDataSetObserver(observer);
            }
            @Override public void unregisterDataSetObserver(
                    android.database.DataSetObserver observer) {
                base.unregisterDataSetObserver(observer);
            }
        };
        lv.setAdapter(popupAd);
        lv.setDivider(null);
        lv.setItemsCanFocus(true);
        lv.setBackgroundResource(R.drawable.bg_dropdown);
        lv.setVerticalScrollBarEnabled(false);
        lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        // FIX cy10 #3: fading edge = gradien transparan di tepi atas/
        // bawah - konten di belakang popup tembus lewat situ. Popup
        // bergaya panel solid: tepinya bersih.
        lv.setVerticalFadingEdgeEnabled(false);
        // Item terpilih ditandai state checked (teks cyan via selector
        // layout dropdown) - feedback "nilai sekarang" tanpa mengubah
        // state apa pun.
        lv.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        // Selector default (highlight item yang ditekan) DIPERTAHANKAN juga
        // saat efek mati - itu feedback fungsional pilihan item, bukan
        // dekorasi glitch (aksesibilitas: state tetap terbaca jelas).

        final PopupWindow pw = new PopupWindow(sp.getContext());
        pw.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        pw.setOutsideTouchable(true);
        pw.setFocusable(true);
        pw.setClippingEnabled(true);
        // fx MATI -> style 0-duration (muncul/tutup seketika); fx HIDUP
        // -> materialize/disintegrate glitch. Tidak pernah memakai
        // animasi fade default platform.
        pw.setAnimationStyle(fx ? R.style.GlitchPopupWindow
                : R.style.GlitchPopupWindowOff);

        // Lebar popup >= spinner.
        final int w = Math.max(sp.getWidth(), (int) (220 * d));

        // Posisi: di bawah spinner; tidak muat -> di atas spinner.
        int[] loc = new int[2];
        sp.getLocationOnScreen(loc);
        int screenH = sp.getResources().getDisplayMetrics().heightPixels;
        int spaceBelow = screenH - (loc[1] + sp.getHeight());
        int spaceAbove = loc[1];

        // FIX cy10 #1: ukur SEMUA item (AT_MOST), bukan UNSPECIFIED yang
        // hanya mengukur 1 anak. Cap = min(280dp, ruang layar terbesar
        // dikurangi margin 24dp, lantai 120dp). Tinggi TIDAK diambil dari
        // view pemicu. Ukuran tersedia segera (adapter sudah terpasang).
        int cap = Math.min((int) (280 * d),
                Math.max((int) (120 * d),
                        Math.max(spaceBelow, spaceAbove) - (int) (24 * d)));
        lv.measure(
                View.MeasureSpec.makeMeasureSpec(w,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(cap,
                        View.MeasureSpec.AT_MOST));
        int h = lv.getMeasuredHeight();
        if (h <= 0) h = ViewGroup.LayoutParams.WRAP_CONTENT;

        final int selPos = sp.getSelectedItemPosition();
        if (selPos >= 0) lv.setItemChecked(selPos, true);
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
            // TIDAK bergantung pada callback Animator sistem (Handler),
            // jadi aman saat animator scale sistem = 0.
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

        if (h <= 0) h = ViewGroup.LayoutParams.WRAP_CONTENT;

        // FIX cy8.1 - INTI perbaikan "semua dropdown tidak muncul":
        // PopupWindow HARUS diberi content view SEBELUM show*(); tanpa
        // itu showAsDropDown()/showAtLocation() no-op senyap (guard
        // mContentView == null di AOSP). Ukuran window eksplisit:
        // lebar >= spinner, tinggi hasil pengukuran SEMUA item (AT_MOST,
        // ter-cap ruang layar).
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
     * disintegrate). Dismiss dijadwalkan lewat Handler (bukan Animator
     * callback) -> bebas dari animator scale sistem.
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
