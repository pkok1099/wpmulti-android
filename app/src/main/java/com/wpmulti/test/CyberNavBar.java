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
 *    ikon, bukan bagian dari item. cy10.11-B: lebar indikator mengikuti
 *    lebar item terpilih (cap 64dp M3) — item kini selebar isi masing2.
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

    /** Satu item navbar: id + view item + kolom ikon+label + ikon + label
     * + lebar ISI item (cy10.11-B — item tidak lagi seragam). */
    private static final class Item {
        final int id;
        final FrameLayout view;
        final LinearLayout col;   // cy10.9: jangkar posisi ikon (lihat onLayout)
        final ImageView icon;     // cy10.9: pusat vertikal indikator = pusat ikon
        final TextView label;
        final int w;              // cy10.11-B: lebar item terukur (isi + pad)
        Item(int id, FrameLayout view, LinearLayout col, ImageView icon,
                TextView label, int w) {
            this.id = id; this.view = view; this.col = col; this.icon = icon;
            this.label = label; this.w = w;
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
        // cy10.11-B: container HAMPIR OPAQUE (96%): teks konten halaman yang
        // lewat di belakang pill tidak lagi menembus/mengganggu label.
        // Warna tetap dari token tema (?attr/colorSurfaceContainer di
        // bg_nav_pill.xml — M3 surface container); alpha hanya pada
        // INSTANCE drawable ini (mutate). Area DI LUAR pill tetap
        // transparan penuh: background hanya pada view pill + clipToOutline
        // capsule — tidak ada view/latar lain di area navbar.
        android.graphics.drawable.Drawable bg = getBackground();
        if (bg != null) bg.mutate().setAlpha(0xF5);

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

        // Ukuran item: tinggi dari font + padding (label tidak terpotong).
        // cy10.11-B (AKAR, mengganti dua lapis perilaku lama):
        // (1) lebar item = ISI NYA (label/ikon item itu + padding), min
        //     48dp utk area sentuh — BUKAN lagi seragam selebar label
        //     terlebar. Konsekuensi baik: inset konten tepi kiri/kanan
        //     terhadap pill = padSide PERSIS simetris tanpa kompensasi
        //     apa pun, dan blok item tepat terpusat di capsule.
        // (2) HACK kompensasi cy10.7 (row.setPadding(extraLeft/
        //     extraRight) utk "menyeimbangkan" inset tepi pada item
        //     seragam) DIHAPUS: ia menggeser blok tab ke kanan
        //     (extraLeft/2 px — terukur Robolectric cy10.11: 1..7px
        //     bergantung density/font), salah arah di RTL (label tepi
        //     bercermin, padding tidak), dan melebarkan pill tanpa isi.
        //     Item seragam + konten per-item terpusat adalah akar asimetri;
        //     menyembuhkannya dgn padding pengimbang = "mengubah angka,
        //     bukan akar masalah".
        // Pill tetap mengikuti ISI (kontrak cy10.4) — kini = jumlah lebar
        // isi tiap item; indikator menyesuaikan lebar item (cap 64dp).
        //
        // cy10.5 (akar crash startup NPE — docs/KNOWN_ISSUES.md §7) tetap
        // berlaku: probe BARU per judul + LP eksplisit sebelum setText
        // (mLayout null -> checkForRelayout tak tersentuh; mLayoutParams
        // tidak pernah null). Hasil ukur identik.
        int labelH = 0;
        // cy10.7: lebar TERUKUR per judul - kini dipakai utk lebar item
        // per-item (bukan lagi utk kompensasi inset).
        int[] titleW = new int[titles.size()];
        for (int i = 0; i < titles.size(); i++) {
            TextView probe = buildLabel(getContext());
            probe.setLayoutParams(new FrameLayout.LayoutParams(
                    LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
            probe.setText(titles.get(i));
            probe.measure(MeasureSpec.UNSPECIFIED, MeasureSpec.UNSPECIFIED);
            labelH = Math.max(labelH, probe.getMeasuredHeight());
            titleW[i] = probe.getMeasuredWidth();
        }
        float dp = getResources().getDisplayMetrics().density;
        int iconPx = (int) (ICON_DP * dp);
        int gapPx = (int) (GAP_DP * dp);
        int itemH = (int) ((ITEM_PAD_TOP_DP + ICON_DP + GAP_DP
                + ITEM_PAD_BOTTOM_DP) * dp) + labelH;
        itemH = Math.max(itemH, (int) (ITEM_MIN * dp));
        // cy10.9 (fix sisa "agak miring" - bias 1px): kolom ikon+label
        // dipusatkan di item lewat Gravity.CENTER FrameLayout yang membagi
        // slack dgn INTEGER DIVISION; slack GANJIL menaruh blok 1px lebih
        // TINGGI dari pusat. +1px pada itemH (tak terlihat) menjamin slack
        // GENAP = terpusat persis di semua density/fontScale.
        int colH = iconPx + gapPx + labelH;
        if ((itemH - colH) % 2 != 0) itemH++;
        int padSidePx = (int) (ITEM_PAD_SIDE_DP * dp);
        int minItemPx = (int) (ITEM_MIN * dp);
        int[] itemW = new int[titles.size()];
        for (int i = 0; i < titles.size(); i++) {
            itemW[i] = Math.max(Math.max(iconPx, titleW[i]) + 2 * padSidePx,
                    minItemPx);
        }

        ColorStateList iconTint = getContext().getColorStateList(
                R.color.nav_item_icon_tint);
        ColorStateList textTint = getContext().getColorStateList(
                R.color.nav_item_text_tint);

        for (int i = 0; i < defs.size(); i++) {
            final int id = defs.get(i)[0];
            int iconRes = defs.get(i)[1];

            FrameLayout item = new FrameLayout(getContext());
            // cy10.11-B: lebar per-item (isi + pad, min 48dp). Tinggi tetap
            // seragam — semua item satu baris ikon+label, baseline sejajar.
            item.setLayoutParams(new LinearLayout.LayoutParams(itemW[i], itemH));

            LinearLayout col = new LinearLayout(getContext());
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setPadding(padSidePx, 0, padSidePx, 0);
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
            items.add(new Item(id, item, col, icon, label, itemW[i]));
        }

        // cy10.11-B: lebar indikator = lebar item TERPILIH (cap 64dp M3) —
        // item kini bisa lebih sempit dari 64dp; tanpa ini indikator
        // menjorok melewati bounds item sempit (menimpa ikon tetangga).
        applyIndicatorWidth(findItem(selectedId));

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
        // cy10.11-B: lebar indikator ikut item terpilih SEBELUM layout
        // (state dipasang seketika, sebelum fx apa pun).
        applyIndicatorWidth(findItem(id));
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
        // cy10.11-B: lebar indikator ikut item baru SEBELUM gerakan —
        // indicatorTargetX membaca lebar dari LayoutParams (lebar yang
        // AKAN diterapkan, bukan getWidth() basi pra-layout), jadi target
        // geser selalu dihitung dgn geometri final.
        applyIndicatorWidth(neu);
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

    /** X target indikator (TRANSLATION, koordinat relatif posisi layout
     * indikator sendiri) utk item terpilih.
     *
     * <p>cy10.12 (AKAR "indikator/pill tampak ke kanan", pra-ada sejak
     * cy10.9): rumus lama mengembalikan row.getLeft() + item.left + ...,
     * yaitu posisi X ABSOLUT dalam koordinat bar — padahal nilai ini
     * dipakai sebagai TRANSLATION yang ditambahkan DI ATAS posisi layout
     * indikator. Indikator (child FrameLayout tanpa gravity) beristirahat
     * di paddingLeft bar (6dp), jadi kapsul tergeser permanen +6dp ke
     * KANAN dari item yang dibingkainya (+ rowPadL/2 ekstra saat hack
     * kompensasi cy10.7 masih ada — perbaikan-perbaikan lama hanya
     * menggeser besarnya, persis gejala "kemiringan berpindah").
     * Jangkar vertikal cy10.9 sudah benar karena mengurangkan
     * indicator.getTop(); horizontal lupa mengurangkan posisi layout
     * sendiri. Fix: target = pusatX(item) − indW/2 − indicator.getLeft().
     *
     * <p>cy10.9: Math.round tetap -> tepi kiri/kanan capsule jatuh di
     * piksel penuh (posisi sub-piksel membuat KEDUA tepi AA-soft;
     * deviasi &lt;= 0.5px dari pusat tak terlihat, tepi kabur TERLIHAT).
     * cy10.11-B: lebar indikator dibaca dari LayoutParams — lebar yang
     * diterapkan/akan diterapkan (lebar indikator kini mengikuti item
     * terpilih; getWidth() bisa basi sebelum layout selesai). */
    private float indicatorTargetX() {
        Item it = findItem(selectedId);
        if (it == null) return 0;
        int indW = indicator.getLayoutParams().width;
        float itemCenterX = row.getLeft() + it.view.getLeft()
                + it.view.getWidth() / 2f;
        return Math.round(itemCenterX - indW / 2f - indicator.getLeft());
    }

    /** cy10.11-B: samakan lebar indikator dgn lebar item terpilih
     * (cap 64dp M3 — indikator tidak pernah lebih lebar dari itemnya,
     * tidak menjorok ke item tetangga). requestLayout hanya bila lebar
     * benar-benar berubah. Item null (menu kosong) = no-op. */
    private void applyIndicatorWidth(Item it) {
        if (it == null) return;
        float dp = getResources().getDisplayMetrics().density;
        int w = Math.min((int) (IND_W_DP * dp), it.w);
        FrameLayout.LayoutParams lp =
                (FrameLayout.LayoutParams) indicator.getLayoutParams();
        if (lp.width != w) {
            lp.width = w;
            indicator.requestLayout();
        }
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
        // Posisi vertikal indikator (cy10.9): dipusatkan pada IKON — pusat
        // NYATA hasil layout (row.getTop + item.getTop + col.getTop +
        // icon.getTop + icon.getHeight/2), rantai posisi penuh tanpa
        // asumsi padding/font (imun clamp ITEM_MIN & pembulatan int).
        //
        // cy10.7 SALAH JANGKAR: "terpusat vertikal terhadap blok ikon+label"
        // = pusat ITEM, padahal ikon ada di ATAS blok → capsule 32dp
        // tertarik ±8dp LEBIH RENDAH dari ikon: tepi atasnya memotong
        // bagian atas ikon & tepi bawahnya memotong label → indikator
        // tampak "agak miring / melorot ke bawah". Jangkar benar = IKON
        // (bahasa M3 asli, kontrak cy10.4 "indikator di belakang ikon"):
        // ikon 20dp masuk penuh dalam capsule (ruang 6dp atas & bawah),
        // label di bawahnya di luar capsule. Rumus pra-cy10.7 memang sudah
        // ke ikon tapi dari ASUMSI padTop+ikon/2 yang bisa meleset 1-2px
        // saat ITEM_MIN meng-clamp / truncation (int) (sumber "pill
        // sedikit lebih rendah"); kini dibaca dari layout nyata sehingga
        // kedua masalah sekaligus tertutup. translationY murni — tidak
        // memicu re-layout, sama seperti translationX horizontal.
        if (indicator.getHeight() > 0 && !items.isEmpty()) {
            Item ref = findItem(selectedId);
            if (ref == null) ref = items.get(0);
            float iconCenterY = row.getTop() + ref.view.getTop()
                    + ref.col.getTop() + ref.icon.getTop()
                    + ref.icon.getHeight() / 2f;
            float ty = iconCenterY - indicator.getHeight() / 2f
                    - indicator.getTop();
            // Bulatkan ke piksel penuh (sama seperti indicatorTargetX):
            // tepi capsule tajam & simetris — offset sub-piksel membuat
            // tepi atas/bawah ter-AA tak sama kuat, terbaca "miring".
            indicator.setTranslationY(
                    Math.round(Math.max(-indicator.getTop(), ty)));
        }
        indicatorLaid = true;
        // Snap tanpa animasi: layout pertama / rotasi / rebuild — state
        // indikator selalu benar tanpa bergantung pada animator.
        if (indAnim == null || !indAnim.isRunning()) {
            indicator.setTranslationX(indicatorTargetX());
        }
    }
}
