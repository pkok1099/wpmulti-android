package com.wpmulti.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * B4 (cy10.12): tes otomatis KESENTERANAN pill navbar — GAGAL bila pill
 * tidak center di semua tab. Robolectric menjalankan View/FrameLayout/
 * CyberNavBar ASLI (layout sungguhan di JVM, font metric Roboto asli via
 * native graphics); inflasi R.layout.activity_main UTUH supaya rantai
 * parent (rootMain FrameLayout + gravity/margin XML asli) ikut teruji.
 *
 * <p>Invarian B3 (toleransi 1px):
 * <ol>
 * <li>margin kiri pill == margin kanan (±1px — sisa pembagian bulat
 *     FrameLayout.CENTER_HORIZONTAL, tidak bisa lebih dari 1px);</li>
 * <li>pusat horizontal pill == pusat window (±1px);</li>
 * <li>row TANPA padding pengimbang (regresi hack kompensasi cy10.7 —
 *     dilarang oleh aturan audit B: padding/offset pengimbang = menutup
 *     gejala, bukan akar);</li>
 * <li>inset konten tepi kiri == kanan terhadap capsule (±1px);</li>
 * <li>indikator terpusat pada item terpilih (±1px), lebarnya mengikuti
 *     item (cap 64dp M3 — tidak menjorok ke item tetangga);</li>
 * <li>jangkar vertikal indikator == pusat ikon (±1px, kontrak cy10.9);</li>
 * <li>tiap item >= 48dp (area sentuh).</li>
 * </ol>
 *
 * <p>BATAS LINGKUNGAN (jujur, bukan klaim lulus di device): ini tes JVM.
 * Yang TIDAK tercakup dan tetap butuh perangkat fisik/emulator: system
 * insets nyata (margin bawah = inset nav + 4dp dipasang listener
 * MainActivity:2832 — hanya jalan ada window nyata), font device
 * (One UI dsb.), dan rendering piksel aktual. Tes instrumented on-device
 * BELUM dijalankan.
 */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = 34)
public class PillCenterTest {

    private static final int[] TABS = {R.id.navHome, R.id.navSesi,
            R.id.navLog, R.id.navSetelan};

    @Test
    public void pillCenteredPortraitAllDensitiesAllTabs() {
        run("portrait", 412);
    }

    @Test
    public void pillCenteredLandscapeAllDensitiesAllTabs() {
        run("landscape", 915);
    }

    /** Token tema & container: ?attr/colorSurfaceContainer resolvable,
     * background alpha hampir opaque (0xF5), clip capsule tetap. */
    @Test
    public void pillContainerNearOpaqueFromThemeSurfaceContainer() {
        Context ctx = themedCtx("420dpi");
        android.content.res.TypedArray ta = ctx.getTheme()
                .obtainStyledAttributes(new int[]{
                        com.google.android.material.R.attr.colorSurfaceContainer});
        int color = ta.getColor(0, 0);
        ta.recycle();
        assertTrue("colorSurfaceContainer harus resolvable dari tema "
                + "(material 1.14 M3) — dapat 0x"
                + Integer.toHexString(color), color != 0);
        View root = LayoutInflater.from(ctx)
                .inflate(R.layout.activity_main, null);
        CyberNavBar pill = root.findViewById(R.id.navPill);
        pill.setMenu(R.menu.bottom_nav);
        pill.selectInitial(R.id.navHome);
        assertEquals(0xF5, pill.getBackground().getAlpha());
        assertTrue("clipToOutline harus tetap aktif (bentuk capsule)",
                pill.getClipToOutline());
    }

    // ---------------- mesin pengujian ----------------

    private static Context themedCtx(String qual) {
        RuntimeEnvironment.setQualifiers(qual);
        return new android.view.ContextThemeWrapper(
                RuntimeEnvironment.getApplication(),
                R.style.Theme_WpmultiTest);
    }

    private void run(String mode, int widthDp) {
        String[] quals = {"mdpi", "hdpi", "xhdpi", "400dpi", "420dpi",
                "xxhdpi", "560dpi"};
        for (String q : quals) {
            Context ctx = themedCtx(q);
            View root = LayoutInflater.from(ctx)
                    .inflate(R.layout.activity_main, null);
            CyberNavBar pill = root.findViewById(R.id.navPill);
            pill.setMenu(R.menu.bottom_nav);
            pill.selectInitial(R.id.navHome);
            float d = ctx.getResources().getDisplayMetrics().density;
            int W = Math.round(widthDp * d);
            int H = Math.round((mode.equals("portrait") ? 915 : 412) * d);
            String tag = q + "/" + mode + " W=" + W;

            // (3) tidak boleh ada padding pengimbang di row (regresi
            // hack cy10.7 yang menggeser blok tab extraLeft/2 px).
            LinearLayout row = findRow(pill);
            assertNotNull("row item harus ada", row);
            assertEquals("row.paddingLeft harus 0 (" + tag + ")",
                    0, row.getPaddingLeft());
            assertEquals("row.paddingRight harus 0 (" + tag + ")",
                    0, row.getPaddingRight());
            // cy10.12-review (T2-1): padding XML pill sendiri juga harus
            // simetris — tanpa ini cek inset konten bisa saling meniadakan
            // bila paddingStart/End dibuat asimetris (row mengikuti padding).
            assertEquals("padding horizontal pill asimetris (" + tag + ")",
                    pill.getPaddingLeft(), pill.getPaddingRight());

            for (int tab : TABS) {
                pill.selectInitial(tab);
                root.measure(
                        View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
                root.layout(0, 0, W, H);
                verify(tab, pill, root, row, W, d, tag);
            }
        }
    }

    private void verify(int tab, CyberNavBar pill, View root,
            LinearLayout row, int W, float d, String tag) {
        String m = tag + " tab=" + name(tab);

        // (1)+(2) margin & pusat pill vs window.
        int mL = pill.getLeft();
        int mR = W - pill.getRight();
        assertTrue("margin kiri/kanan pill beda > 1px: " + mL + " vs " + mR
                + " (" + m + ")", Math.abs(mL - mR) <= 1);
        int centerOff = (pill.getLeft() + pill.getRight()) / 2 - W / 2;
        assertTrue("pusat pill != pusat window (off " + centerOff + "px, "
                + centerOff / d + "dp) (" + m + ")", Math.abs(centerOff) <= 1);

        // (4) inset konten tepi kiri == kanan (kolom ikon+label item
        // tepi terhadap batas konten capsule).
        View first = row.getChildAt(0);
        View last = row.getChildAt(row.getChildCount() - 1);
        android.view.ViewGroup fc = (android.view.ViewGroup) first;
        android.view.ViewGroup lc = (android.view.ViewGroup) last;
        int insetL = row.getLeft() + first.getLeft()
                + fc.getChildAt(0).getLeft() - pill.getPaddingLeft();
        int insetR = pill.getWidth() - pill.getPaddingRight()
                - (row.getLeft() + last.getLeft()
                + lc.getChildAt(0).getRight());
        assertTrue("inset konten tepi asimetris: " + insetL + " vs "
                + insetR + " (" + m + ")", Math.abs(insetL - insetR) <= 1);

        // (5) indikator terpusat pada item terpilih + lebar ikut item.
        View ind = findIndicator(pill);
        assertNotNull(ind);
        View selIt = null;
        for (int i = 0; i < row.getChildCount(); i++) {
            if (row.getChildAt(i).isSelected()) selIt = row.getChildAt(i);
        }
        assertNotNull("item terpilih harus ada (" + m + ")", selIt);
        int indAbsL = pill.getLeft() + ind.getLeft()
                + Math.round(ind.getTranslationX());
        int indAbsR = indAbsL + ind.getWidth();
        int itAbsL = pill.getLeft() + row.getLeft() + selIt.getLeft();
        int itAbsR = itAbsL + selIt.getWidth();
        assertTrue("indikator tidak terpusat pada item: indCenter="
                + (indAbsL + indAbsR) / 2 + " itemCenter="
                + (itAbsL + itAbsR) / 2 + " (" + m + ")",
                Math.abs((indAbsL + indAbsR) / 2 - (itAbsL + itAbsR) / 2) <= 1);
        assertTrue("indikator lebih lebar dari itemnya: " + ind.getWidth()
                + " > " + selIt.getWidth() + " (" + m + ")",
                ind.getWidth() <= selIt.getWidth());

        // (6) jangkar vertikal: pusat indikator == pusat IKON item
        // terpilih (kontrak cy10.9, rantai layout nyata).
        android.view.ViewGroup selCol = (android.view.ViewGroup) selIt;
        android.view.ViewGroup col =
                (android.view.ViewGroup) selCol.getChildAt(0);
        View icon = col.getChildAt(0);
        int iconCenterY = pill.getTop() + row.getTop() + selIt.getTop()
                + selCol.getChildAt(0).getTop() + icon.getTop()
                + icon.getHeight() / 2;
        int indCenterY = pill.getTop() + ind.getTop()
                + Math.round(ind.getTranslationY()) + ind.getHeight() / 2;
        assertTrue("jangkar vertikal indikator != pusat ikon ("
                + (indCenterY - iconCenterY) + "px) (" + m + ")",
                Math.abs(indCenterY - iconCenterY) <= 1);

        // (7) area sentuh tiap item >= 48dp (h/w).
        for (int i = 0; i < row.getChildCount(); i++) {
            View it = row.getChildAt(i);
            assertTrue("item '" + name(TABS[i]) + "' lebih sempit dari 48dp: "
                    + (int) (it.getWidth() / d) + "dp (" + m + ")",
                    it.getWidth() >= 48 * d - 1);
            assertTrue("item terlalu pendek: " + it.getHeight() + "px (" + m + ")",
                    it.getHeight() >= 48 * d - 1);
        }

        // Label item terpilih tetap terbaca (bukan terpotong/0).
        TextView label = findLabel(selIt);
        assertNotNull(label);
        assertTrue("label item terpilih kosong (" + m + ")",
                label.getWidth() > 0 && label.getHeight() > 0);
    }

    private static String name(int id) {
        if (id == R.id.navHome) return "Beranda";
        if (id == R.id.navSesi) return "Sesi";
        if (id == R.id.navLog) return "Log";
        return "Setelan";
    }

    private LinearLayout findRow(CyberNavBar pill) {
        for (int i = 0; i < pill.getChildCount(); i++) {
            View c = pill.getChildAt(i);
            if (c instanceof LinearLayout) return (LinearLayout) c;
        }
        return null;
    }

    private View findIndicator(CyberNavBar pill) {
        for (int i = 0; i < pill.getChildCount(); i++) {
            View c = pill.getChildAt(i);
            if (!(c instanceof LinearLayout)
                    && !(c instanceof TextView)) return c;
        }
        return null;
    }

    private TextView findLabel(View item) {
        if (!(item instanceof android.view.ViewGroup)) return null;
        android.view.ViewGroup vg = (android.view.ViewGroup) item;
        for (int i = 0; i < vg.getChildCount(); i++) {
            View col = vg.getChildAt(i);
            if (col instanceof android.view.ViewGroup) {
                android.view.ViewGroup cvg = (android.view.ViewGroup) col;
                for (int j = 0; j < cvg.getChildCount(); j++) {
                    View c2 = cvg.getChildAt(j);
                    if (c2 instanceof TextView) return (TextView) c2;
                }
            }
        }
        return null;
    }
}
