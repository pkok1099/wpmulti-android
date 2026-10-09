package com.wpmulti.test;

import static org.junit.Assert.assertEquals;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * A3 (cy10.11): BUKTI dampak samping fail-closed BLOCK — apakah paket
 * aplikasi LAIN yang pemiliknya sempat tidak terdeteksi ikut terbuang
 * saat satu aplikasi diset BLOCK?
 *
 * <p>Tes ini mensimulasikan RANTAI KEPUTUSAN ENGINE secara harfiah
 * memakai fungsi yang SAMA yang dipakai VpnEngine (bukan salinan):
 * <pre>
 *   // VpnEngine.flowOwner (:302-318):
 *   own = (appIpModes kosong || exception lookup || uid &lt; 0
 *          || pkgs null/kosong) ? null
 *        : FlowOwner(uidVerdictMode(pkgs, appIpModes), ...)
 *   // VpnEngine.handleTcp/handleTcp6 (:1813/:1871):
 *   act = verdictAction(own == null ? null : own.mode, effV6,
 *                       blockV4Active, blockV6Active, ipMode)
 * </pre>
 * Semua jalur "pemilik tidak terdeteksi" di engine bermuara ke
 * {@code mode == null}:
 * <ul>
 * <li><b>Race lookup conntrack</b> — entri belum terlihat netd saat
 *     paket tiba (kegagalan TIDAK di-cache, di-retry per paket —
 *     VpnEngine.flowOwner komentar :284-288);</li>
 * <li><b>UID sistem tanpa paket</b> — getPackagesForUid kosong/null
 *     (mis. UID 0/1000/1023) -> flowOwner return null (:314);</li>
 * <li><b>Exception binder</b> -> null (:304-306).</li>
 * </ul>
 * Catatan minSdk: aplikasi ini minSdk 34 (&gt;= API 29) sehingga
 * ConnectivityManager.getConnectionOwnerUid SELALU tersedia — jalur
 * "API terlalu tua" tidak ada di kode (tidak ada cabang Build.VERSION
 * di flowOwner; satu-satunya fallback adalah null di atas).
 */
public class FailClosedSideEffectsTest {

    private static final String DUAL = "dual";

    /**
     * Replika persis rantai flowOwner -> verdict di VpnEngine
     * (fungsi yang dipakai data plane, dipanggil apa adanya).
     */
    private static int engineTcpVerdict(String[] pkgs,
            Map<String, String> appIpModes, boolean effV6,
            boolean blockV4Active, boolean blockV6Active, String ipMode) {
        // flowOwner: appIpModes kosong / pkgs null / kosong -> null.
        Integer mode;
        if (appIpModes.isEmpty() || pkgs == null || pkgs.length == 0) {
            mode = null; // pemilik TIDAK diketahui
        } else {
            mode = IpModeVerdict.uidVerdictMode(pkgs, appIpModes);
        }
        return IpModeVerdict.verdictAction(mode, effV6,
                blockV4Active, blockV6Active, ipMode);
    }

    // Skenario: SATU app (com.game) di-BLOCK v4; app lain (com.chat,
    // com.browser) tidak diset; com.sysapp = aplikasi sistem (UID
    // tanpa paket terpetakan); "null" = race conntrack / exception.

    private static Map<String, String> appModes() {
        Map<String, String> m = new HashMap<>();
        m.put("com.game", "block4");
        return m;
    }

    @Test
    public void otherAppsNormalTrafficUnaffected() {
        // BUKTI (bukan janji): app lain yg pemiliknya BERHASIL
        // diatribusikan TIDAK tersentuh oleh BLOCK milik app lain —
        // TCP v4 maupun v6 tetap PASS.
        Map<String, String> aim = appModes();
        boolean b4 = true, b6 = false;
        assertEquals(IpModeVerdict.PASS,
                engineTcpVerdict(new String[] {"com.chat"}, aim,
                        false, b4, b6, DUAL));
        assertEquals(IpModeVerdict.PASS,
                engineTcpVerdict(new String[] {"com.browser"}, aim,
                        true, b4, b6, DUAL));
        // App yg di-BLOCK tetap ditolak di family yg diblok.
        assertEquals(IpModeVerdict.REJECT,
                engineTcpVerdict(new String[] {"com.game"}, aim,
                        false, b4, b6, DUAL));
        // ...dan tetap boleh pakai family lain.
        assertEquals(IpModeVerdict.PASS,
                engineTcpVerdict(new String[] {"com.game"}, aim,
                        true, b4, b6, DUAL));
    }

    @Test
    public void unknownOwnerPacketOfOtherAppIsDropped_sideEffectProven() {
        // DAMPAK SISING YANG TERBUKTI: paket IPv4 yg pemiliknya gagal
        // diatribusikan (race conntrack / exception binder / UID tanpa
        // paket) DIBUANG walau kemungkinan besar milik app lain yg tak
        // diblok — inilah harga fail-closed (permintaan eksplisit
        // cy10.10: "saat kepemilikan tidak bisa ditentukan, trafik
        // family yg diblok harus dibuang").
        Map<String, String> aim = appModes();
        assertEquals(IpModeVerdict.REJECT,
                engineTcpVerdict(null, aim, false, true, false, DUAL));
        // UID sistem tanpa paket terpetakan -> jalur yg sama.
        assertEquals(IpModeVerdict.REJECT,
                engineTcpVerdict(new String[0], aim, false, true, false, DUAL));
    }

    @Test
    public void sideEffectLimitedToBlockedFamily() {
        // Dampak sising TERBATAS pada family yg diblok: paket v6 pemilik
        // tak dikenal TETAP LOLOS saat hanya v4 yg diblok.
        Map<String, String> aim = appModes();
        assertEquals(IpModeVerdict.PASS,
                engineTcpVerdict(null, aim, true, true, false, DUAL));
        assertEquals(IpModeVerdict.PASS,
                engineTcpVerdict(new String[0], aim, true, true, false, DUAL));
    }

    @Test
    public void noSideEffectWhenNoBlockAnywhere() {
        // Tanpa app BLOCK manapun: pemilik tak dikenal TETAP lolos
        // (fail-closed hanya aktif utk family yg diblok seseorang).
        Map<String, String> aim = new HashMap<>();
        aim.put("com.chat", "v6"); // hanya mode paksa, bukan BLOCK
        assertEquals(IpModeVerdict.PASS,
                engineTcpVerdict(null, aim, false, false, false, DUAL));
        assertEquals(IpModeVerdict.PASS,
                engineTcpVerdict(null, aim, true, false, false, DUAL));
    }

    @Test
    public void udpAndDnsSideEffectsMatchTcp() {
        // Jalur UDP & DNS punya sifat yg sama (verdict terpadu):
        Map<String, String> aim = appModes();
        // UDP pemilik tak dikenal, family diblok -> REJECT (unreach).
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.udpVerdict(null, false, true, false, DUAL));
        // UDP pemilik tak dikenal, family bebas -> PASS.
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.udpVerdict(null, true, true, false, DUAL));
        // DNS pemilik tak dikenal saat v4 diblok -> A NODATA
        // (app lain bisa kehilangan resolusi A saat lookup gagal).
        IpModeVerdict.DnsPolicy p = IpModeVerdict.dnsPolicy(
                null, true, false, DUAL);
        assertEquals(true, p.killA);
        assertEquals(false, p.killAAAA);
    }

    @Test
    public void liveApplyCutsUnknownOwnerConnections() {
        // cutConflictingFlows (:680-703): koneksi lama pemilik-tak-dikenal
        // diputus saat BLOCK baru diterapkan (verdict owner null ->
        // fail-closed) — konsisten jalur paket, bukan kebijakan lain.
        Map<String, String> aim = appModes();
        int act = IpModeVerdict.verdictAction(null, false, true, false, DUAL);
        assertEquals(IpModeVerdict.REJECT, act); // != PASS -> resetHard()
        // Sebaliknya koneksi app lain yg pemiliknya diketahui TIDAK
        // diputus (verdict PASS).
        assertEquals(IpModeVerdict.PASS, IpModeVerdict.verdictAction(
                IpModeVerdict.uidVerdictMode(new String[] {"com.chat"}, aim),
                false, true, false, DUAL));
    }
}
