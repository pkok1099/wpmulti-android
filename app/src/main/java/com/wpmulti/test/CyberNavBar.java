package com.wpmulti.test;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;

/**
 * CyberNavBar — navbar pill melayang pengganti BottomNavigationView (cy10.4).
 *
 * KONTEKS: artifact "kotak hitam persegi" di area navbar yang bertahan lintas
 * fix bertahap (cy3/cy8/cy10.3). Akar terbukti dari forensik screenshot +
 * bytecode material-1.14.0 (lihat docs/KNOWN_ISSUES.md §4):
 *  1. Kotak hitam = PERSIS bounds BottomNavigationView (300x56dp, offset
 *     padding pill 4dp) — terukur piksel dari screenshot device.
 *  2. android:background="@null" TIDAK mematikan mesin background lib:
 *     constructor NavigationBarView JUSTRU memasang MaterialShapeDrawable
 *     baru saat background == null (NavigationBarView.java baris 148-157).
 *  3. app:elevation dari style lib (3dp, m3_sys_elevation_level2)
 *     MENIMPA android:elevation="0dp" di XML (baris 171-172: setElevation
 *     dipanggil SETELAH constructor View memproses XML) — plus
 *     setElevation/onAttachedToWindow meneruskan elevasi ke MSD itu via
 *     MaterialShapeUtils (jalur shadow/elevation-overlay RenderNode).
 *  4. View ber-elevation + background shape-drawable + invalidasi berulang
 *     (pindah tab) = jalur compositing layer yang bisa membeku jadi KOTAK
 *     HITAM OPAQUE persis seukuran view (keluarga bug yang sama dengan
 *     cy8 "alpha pada view ber-elevation").
 *
 * SOLUSI BY CONSTRUCTION (tidak ada mesin yang bisa menghasilkan kotak):
 *  - SEMUA view framework polos: FrameLayout + LinearLayout + ImageView +
 *    TextView + View. TIDAK ADA import material, TIDAK ADA
 *    MaterialShapeDrawable, TIDAK ADA style lib, TIDAK ADA menu presenter,
 *    TIDAK ADA badge, ripple foreground, lazy label inflater.
 *  - Bentuk pill dari SATU sumber (bg_nav_pill.xml) + clipToOutline dengan
 *    outline capsule gradient-drawable — clip mengikuti bentuk pill, bukan
 *    kotak. elevation SELALU 0 (tidak ada jalur layer/shadow framework).
 *  - Indikator aktif = SATU view terpisah (bg_nav_indicator.xml capsule
 *    64x32dp) yang bergeser (translationX) ke item terpilih — di belakang
 *    ikon, bukan bagian dari item.
 *  - Efek glitch HANYA pada item yang kehilangan/mendapat active state
 *    (item lama MINOR, item baru MEDIUM) via API GlitchText yang beku —
 *    span label + translationX murni; TANPA hardware layer, TANPA snapshot
 *    bitmap, TANPA alpha pada container (kontrak cy8 dipertahankan).
 *  - Gerakan indikator = perubahan STATE (bukan efek): selalu bergerak,
 *    instan saat skala animator sistem = 0 / efek mati.
 */
public class CyberNavBar extends FrameLayout {

    /** Listener pemilihan item (dipanggil hanya saat pilihan BERUBAH). */
    public interface OnItemSelectedListener {
        boolean onNavigationItemSelected(int id);
    }

    private static final String ANDROID_NS =
            "http://schemas.android.com/apk/res/android";

    // ---- token ukuran (dp; label ikut TextAppearance.Wpmulti.NavLabel) ----
    private static final int ICON_DP = 20;          // ikon 20dp (cy10.3)
    private static final int GAP_DP = 3;            // jarak ikon -> label
    private static final int ITEM_PAD_SIDE_DP = 10; // samping konten item
    private static final int ITEM_PAD_TOP_DP = 7;
    private static final int ITEM_PAD_BOTTOM_DP = 7;
    private static final int ITEM_MIN = 48;         // area sentuh minimal
    private static final int IND_W_DP = 64;         // indikator M3 64x32dp
    private static final int IND_H_DP = 32;
    private static final long IND_ANIM_MS = 240;    // gerakan indikator

    /** Satu item navbar: id + view item + label. */
    private static final class Item {
        final int id;
        final FrameLayout view;
        final TextView label;
        Item(int id, FrameLayout view, TextView label) {
            this.id = id; this.view = view; this.label = label;
        }
    }

    private final LinearLayout row;      // deretan 4 item
    private final View indicator;        // pill aktif (satu view, bergerak)
    private final ArrayList<Item> items = new ArrayList<>();
    private OnItemSelectedListener listener;
    private int selectedId = 0;
    private ValueAnimator indAnim;
    private boolean indicatorLaid = false;

    public CyberNavBar(Context c) { this(c, null); }

    public CyberNavBar(Context c, AttributeSet a) {
        super(c, a);
        // ELEVASI SELALU 0 + tanpa layer: tidak ada jalur compositing layer
        // framework pada area navbar (akar kotak hitam — lihat kelas doc).
        setElevation(0f);
        // Clip konten mengikuti outline background (capsule bg_nav_pill.xml)
        // — SATU sumber bentuk; tidak ada kotak di level mana pun.
        setClipToOutline(true);

        row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        addView(row, new FrameLayout.LayoutParams(
                LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        indicator = new View(c);
        indicator.setBackgroundResource(R.drawable.bg_nav_indicator);
        indicator.setElevation(0f);
        // index 0: DI BAWAH row (z-order gambar) — indikator di belakang
        // ikon, persis bahasa visual M3 lama.
        addView(indicator, 0, new FrameLayout.LayoutParams(
                (int) (IND_W_DP * getResources().getDisplayMetrics().density),
                (int) (IND_H_DP * getResources().getDisplayMetrics().density)));
    }

    // ---------------- menu ----------------

    /**
     * Bangun item dari menu XML (satu sumber kebenaran id/ikon/judul —
     * R.menu.bottom_nav; id TIDAK berubah). Parser manual: getResources()
     * .getXml() pada XML terkompilasi mengembalikan XmlBlock.Parser yang
     * JUGA AttributeSet — id/ikon dibaca TYPED (getAttributeResourceValue,
     * referensi "@0x7f..." hex) agar tidak bergantung format string.
     * Tanpa MenuBuilder internal / PopupMenu / inflater lib apa pun.
     */
    public void setMenu(int menuRes) {
        row.removeAllViews();
        items.clear();
        ArrayList<int[]> defs = new ArrayList<>();   // {id, iconRes}
        ArrayList<String> titles = new ArrayList<>();
        try {
            XmlPullParser p = getResources().getXml(menuRes);
            AttributeSet as = (p instanceof AttributeSet)
                    ? (AttributeSet) p : null;
            int ev;
            while ((ev = p.next()) != XmlPullParser.END_DOCUMENT) {
                if (ev != XmlPullParser.START_TAG) continue;
                if (!"item".equals(p.getName())) continue;
                if (as == null) continue; // praktis tidak terjadi di framework
                int id = as.getAttributeResourceValue(ANDROID_NS, "id", 0);
                int icon = as.getAttributeResourceValue(ANDROID_NS, "icon", 0);
                String title = as.getAttributeValue(ANDROID_NS, "title");
                if (title != null && title.startsWith("@")) {
                    title = getResources().getString(
                            as.getAttributeResourceValue(ANDROID_NS,
                                    "title", 0));
                }
                if (id == 0) continue;
                defs.add(new int[]{id, icon});
                titles.add(title == null ? "" : title);
            }
        } catch (Exception ignored) {
            // menu rusak = navbar kosong (fail-safe, tidak crash)
        }

        // Ukuran item: tinggi dari font + padding (label tidak terpotong);
        // lebar seragam mengikuti label terlebar + padding (pill mengikuti
        // ISI, konstan di semua ukuran layar — bukan 0.175x lebar layar).
        // cy10.5 (akar crash startup NPE — docs/KNOWN_ISSUES.md §7):
        // TextView.setText() -> checkForRelayout() membaca mLayoutParams
        // .width SEBAGAI PERNYATAAN PERTAMA (AOSP 14..16 TANPA null-guard;
        // android-14.0.0_r1:11246, main:11679) dan hanya terpanggil saat
        // mLayout != null. Probe lama (satu objek dipakai ulang) diukur di
        // iterasi 1 -> mLayout terbentuk -> setText iterasi 2 (menu punya
        // 4 judul) -> checkForRelayout -> probe TANPA parent/tanpa
        // setLayoutParams = mLayoutParams NULL -> NPE pasti di cold start.
        // Fix BY CONSTRUCTION, dua lapis: (1) probe BARU per judul — setText
        // selalu terjadi saat view masih segar (mLayout null, jalur
        // checkForRelayout tak tersentuh); (2) LP eksplisit sebelum apapun
        // — mLayoutParams tidak pernah null di jalur manapun. Hasil ukur
        // identik (measure(UNSPECIFIED) membaca spec, bukan LP).
        int labelH = 0, labelW = 0;
        for (String t : titles) {
            TextView probe = buildLabel(getContext());
            probe.setLayoutParams(new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            probe.setText(t);
            probe.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            labelH = Math.max(labelH, probe.getMeasuredHeight());
            labelW = Math.max(labelW, probe.getMeasuredWidth());
        }
        float dp = getResources().getDisplayMetrics().density;
        int iconPx = (int) (ICON_DP * dp);
        int itemH = (int) ((ITEM_PAD_TOP_DP + ICON_DP + GAP_DP
                + ITEM_PAD_BOTTOM_DP) * dp) + labelH;
        itemH = Math.max(itemH, (int) (ITEM_MIN * dp));
        int contentW = Math.max(iconPx, labelW)
                + (int) (2 * ITEM_PAD_SIDE_DP * dp);
        int itemW = Math.max(contentW, (int) (ITEM_MIN * dp));

        ColorStateList iconTint = getContext().getColorStateList(
                R.color.nav_item_icon_tint);
        ColorStateList textTint = getContext().getColorStateList(
                R.color.nav_item_text_tint);

        for (int i = 0; i < defs.size(); i++) {
            final int id = defs.get(i)[0];
            int iconRes = defs.get(i)[1];

            FrameLayout item = new FrameLayout(getContext());
            item.setLayoutParams(new LinearLayout.LayoutParams(itemW, itemH));

            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding((int) (ITEM_PAD_SIDE_DP * dp), 0,
                    (int) (ITEM_PAD_SIDE_DP * dp), 0);
            item.addView(col, new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));

            ImageView icon = new ImageView(getContext());
            // duplicateParentState: state SELECTED item menjalar ke ikon/
            // label -> selector warna (mekanisme state framework polos).
            icon.setDuplicateParentStateEnabled(true);
            if (iconRes != 0) icon.setImageResource(iconRes);
            if (iconTint != null) icon.setImageTintList(iconTint);
            LinearLayout.LayoutParams ilp2 = new LinearLayout.LayoutParams(
                    iconPx, iconPx);
            col.addView(icon, ilp2);

            TextView label = buildLabel(getContext());
            label.setDuplicateParentStateEnabled(true);
            if (textTint != null) label.setTextColor(textTint);
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            llp.topMargin = (int) (GAP_DP * dp);
            // cy10.5: pasang ke parent (LP terisi) SEBELUM setText —
            // defence-in-depth kontrak "LP dulu, teks kemudian" (akar §7).
            col.addView(label, llp);
            label.setText(titles.get(i));

            // Tekan item = feedback TEPAT SASARAN (pola tombol cy8: korupsi
            // label + micro displacement item — BUKAN jitter pill penuh).
            item.setOnTouchListener((v, ev) -> {
                if (ev.getActionMasked() == MotionEvent.ACTION_DOWN
                        && v.isEnabled()) {
                    GlitchText.glitchNow(label, GlitchText.MINOR);
                    GlitchText.glitchJitter(v, GlitchText.MINOR);
                }
                return false; // tidak dikonsumsi: klik tetap normal
            });
            item.setOnClickListener(v -> select(id, true));
            item.setClickable(true);
            item.setFocusable(true);

            row.addView(item);
            items.add(new Item(id, item, label));
        }

        // Daftarkan label ke registry glitch SEKALI di sini (tidak ada
        // inflasi lazy seperti BNV — semua view sudah final).
        GlitchText.registerTree(this);
    }

    private TextView buildLabel(Context c) {
        TextView t = new TextView(c);
        t.setTextAppearance(c, R.style.TextAppearance_Wpmulti_NavLabel);
        t.setMaxLines(1);
        t.setIncludeFontPadding(false);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    // ---------------- seleksi ----------------

    public void setOnItemSelectedListener(OnItemSelectedListener l) {
        listener = l;
    }

    /** Pilihan awal: set state TANPA efek & tanpa listener (cold start). */
    public void selectInitial(int id) {
        selectedId = id;
        for (Item it : items) it.view.setSelected(it.id == id);
        requestLayout();
    }

    /**
     * Pilih item: state berubah seketika; efek glitch hanya pada item lama
     * (MINOR) dan item baru (MEDIUM) — target eksplisit, API yang sama
     * sejak cy7/cy8 (glitchTree + glitchJitter per item); indikator
     * bergeser ke item baru (state, bukan efek). Tap pada item yang SUDAH
     * aktif = no-op (perilaku BNV lama: reselect tidak memicu listener).
     */
    public void select(int id, boolean invokeListener) {
        if (id == selectedId) return;
        Item old = findItem(selectedId);
        Item neu = findItem(id);
        selectedId = id;
        for (Item it : items) it.view.setSelected(it.id == id);
        if (old != null) {
            GlitchText.glitchTree(old.view, GlitchText.MINOR);
            GlitchText.glitchJitter(old.view, GlitchText.MINOR);
        }
        if (neu != null) {
            GlitchText.glitchTree(neu.view, GlitchText.MEDIUM);
            GlitchText.glitchJitter(neu.view, GlitchText.MINOR);
        }
        moveIndicator(true);
        if (invokeListener && listener != null) {
            listener.onNavigationItemSelected(id);
        }
    }

    public int getSelectedItemId() { return selectedId; }

    private Item findItem(int id) {
        for (Item it : items) if (it.id == id) return it;
        return null;
    }

    // ---------------- indikator ----------------

    /** X target indikator (koordinat CyberNavBar) utk item terpilih. */
    private float indicatorTargetX() {
        Item it = findItem(selectedId);
        if (it == null) return row.getLeft();
        return row.getLeft() + it.view.getLeft()
                + (it.view.getWidth() - indicator.getWidth()) / 2f;
    }

    private void moveIndicator(boolean animate) {
        if (!indicatorLaid || indicator.getWidth() == 0) return;
        float target = indicatorTargetX();
        if (indAnim != null) indAnim.cancel();
        if (!animate || animatorScaleZero()) {
            indicator.setTranslationX(target);
            return;
        }
        float cur = indicator.getTranslationX();
        if (Math.abs(target - cur) < 0.5f) return;
        indAnim = ValueAnimator.ofFloat(cur, target);
        indAnim.setDuration(IND_ANIM_MS);
        indAnim.setInterpolator(new DecelerateInterpolator());
        indAnim.addUpdateListener(a ->
                indicator.setTranslationX((Float) a.getAnimatedValue()));
        indAnim.start();
    }

    private Boolean scaleZeroCache;
    private final ContentObserver scaleObs = new ContentObserver(
            new Handler(Looper.getMainLooper())) {
        @Override public void onChange(boolean self) {
            scaleZeroCache = null; // skala animator berubah -> baca ulang
        }
    };

    /** Skala animator sistem = 0? (cache + observer settings global). */
    private boolean animatorScaleZero() {
        if (scaleZeroCache == null) {
            try {
                float s = Settings.Global.getFloat(
                        getContext().getContentResolver(),
                        Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
                scaleZeroCache = (s == 0f);
            } catch (Exception e) {
                scaleZeroCache = Boolean.FALSE;
            }
        }
        return scaleZeroCache;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            getContext().getContentResolver().registerContentObserver(
                    Settings.Global.getUriFor(
                            Settings.Global.ANIMATOR_DURATION_SCALE),
                    false, scaleObs);
        } catch (Exception ignored) { }
    }

    @Override
    protected void onDetachedFromWindow() {
        try {
            getContext().getContentResolver().unregisterContentObserver(
                    scaleObs);
        } catch (Exception ignored) { }
        if (indAnim != null) { indAnim.cancel(); indAnim = null; }
        super.onDetachedFromWindow();
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        // Posisi vertikal indikator: di tengah ikon (translationY murni —
        // tidak memicu re-layout, sama seperti translationX horizontal).
        if (indicator.getHeight() > 0 && !items.isEmpty()) {
            Item first = items.get(0);
            float dp = getResources().getDisplayMetrics().density;
            int iconCenterY = row.getTop() + first.view.getTop()
                    + (int) (ITEM_PAD_TOP_DP * dp) + (int) (ICON_DP * dp) / 2;
            float ty = iconCenterY - indicator.getHeight() / 2f
                    - indicator.getTop();
            indicator.setTranslationY(Math.max(-indicator.getTop(), ty));
        }
        indicatorLaid = true;
        // Snap tanpa animasi: layout pertama / rotasi / rebuild — state
        // indikator selalu benar tanpa bergantung pada animator.
        if (indAnim == null || !indAnim.isRunning()) {
            indicator.setTranslationX(indicatorTargetX());
        }
    }
}
