package com.wpmulti.test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Tes JUnit logika verdict Mode IP per-app (cy10.11) — menguji
 * {@link IpModeVerdict}, kelas murni yang BENAR-BENAR dipakai data plane
 * {@link VpnEngine} (engine mendelegasikan semua verdict ke sini).
 * Menggantikan mirror Python cy10.10: identitas fungsi dijamin oleh
 * refactor delegasi, bukan disalin manual.
 *
 * <p>Konvensi: {@code mode == null} = pemilik TIDAK DIKETAHUI
 * (lookup conntrack gagal / uid &lt; 0 / UID tanpa paket — termasuk UID
 * sistem tanpa paket &amp; race lookup). {@code b4}/{@code b6} =
 * blockV4Active/blockV6Active (ada minimal satu app BLOCK family itu).
 */
public class IpModeVerdictTest {

    private static final String DUAL = "dual";
    private static final String V6ONLY = "v6only";
    private static final String V4ONLY = "v4only";

    // ---- alamat uji ----

    /** 8.8.8.8 */
    private static final byte[] V4 = {8, 8, 8, 8};
    /** 2001:db8::1 (v6 native, bukan ter-embed). */
    private static final byte[] V6 = {
        0x20, 0x01, 0x0d, (byte) 0xb8, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 1};
    /** ::ffff:8.8.8.8 (IPv4-mapped, RFC 4291). */
    private static final byte[] MAPPED = {
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        (byte) 0xff, (byte) 0xff, 8, 8, 8, 8};
    /** 64:ff9b::8.8.8.8 (NAT64 well-known, RFC 6052). */
    private static final byte[] NAT64 = {
        0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 0,
        0, 0, 0, 0, 8, 8, 8, 8};
    /** 2002:0808:0808:: (6to4 — TIDAK dibedakan, terdokumentasi). */
    private static final byte[] V6TO4 = {
        0x20, 0x02, 0x08, 0x08, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0};
    /** 2001:0000:: (Teredo — TIDAK dibedakan, terdokumentasi). */
    private static final byte[] TEREDO = {
        0x20, 0x01, 0x00, 0x00, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0};
    /** 64:ff9b:0:0:1:: (prefiks benar tapi byte 4..11 tidak nol —
     * bukan /96 well-known). */
    private static final byte[] NAT64_NONZERO = {
        0x00, 0x64, (byte) 0xff, (byte) 0x9b, 0, 0, 0, 1,
        0, 0, 0, 0, 8, 8, 8, 8};

    private static Map<String, String> modes(Object... kv) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            m.put((String) kv[i], (String) kv[i + 1]);
        return m;
    }

    // ================================================================
    // modeCode
    // ================================================================

    @Test
    public void modeCodeMapping() {
        assertEquals(IpModeVerdict.M_V4, IpModeVerdict.modeCode("v4"));
        assertEquals(IpModeVerdict.M_V6, IpModeVerdict.modeCode("v6"));
        assertEquals(IpModeVerdict.M_BLOCK4, IpModeVerdict.modeCode("block4"));
        assertEquals(IpModeVerdict.M_BLOCK6, IpModeVerdict.modeCode("block6"));
        assertEquals(IpModeVerdict.M_NONE, IpModeVerdict.modeCode(null));
        assertEquals(IpModeVerdict.M_NONE, IpModeVerdict.modeCode(""));
        assertEquals(IpModeVerdict.M_NONE, IpModeVerdict.modeCode("bogus"));
    }

    // ================================================================
    // Family efektif: IPv4-mapped + NAT64
    // ================================================================

    @Test
    public void v4IsNotV6() {
        assertFalse(IpModeVerdict.effFamilyV6(V4));
        assertFalse(IpModeVerdict.effFamilyV6(null));
    }

    @Test
    public void nativeV6IsV6() {
        assertTrue(IpModeVerdict.effFamilyV6(V6));
    }

    @Test
    public void ipv4MappedCountsAsV4() {
        // ::ffff:8.8.8.8 menjangkau IPv4 -> family efektif v4.
        assertFalse(IpModeVerdict.effFamilyV6(MAPPED));
        assertTrue(IpModeVerdict.isV4EmbeddedV6(MAPPED));
    }

    @Test
    public void nat64WellKnownCountsAsV4() {
        // 64:ff9b::/96 (464XLAT/Android di jaringan v6-only) -> v4.
        assertFalse(IpModeVerdict.effFamilyV6(NAT64));
        assertTrue(IpModeVerdict.isV4EmbeddedV6(NAT64));
    }

    @Test
    public void nat64WithNonZeroBitsIsNotWellKnown() {
        // 64:ff9b:0:0:1:: bukan /96 murni -> tidak dianggap embed.
        assertFalse(IpModeVerdict.isV4EmbeddedV6(NAT64_NONZERO));
        assertTrue(IpModeVerdict.effFamilyV6(NAT64_NONZERO));
    }

    @Test
    public void sixToFourAndTeredoNotTreatedAsEmbedded() {
        // Batas terdokumentasi (KNOWN_ISSUES): 6to4/Teredo tidak
        // dibedakan — relay deprecated. Ini MEMASTIKAN batas itu, bukan
        // bug tak sadar: perilaku saat ini = family kabel v6.
        assertFalse(IpModeVerdict.isV4EmbeddedV6(V6TO4));
        assertFalse(IpModeVerdict.isV4EmbeddedV6(TEREDO));
        assertTrue(IpModeVerdict.effFamilyV6(V6TO4));
        assertTrue(IpModeVerdict.effFamilyV6(TEREDO));
    }

    // ================================================================
    // BLOCK v4 / BLOCK v6 — TCP (verdictAction == jalur SYN)
    // ================================================================

    @Test
    public void block4RejectsV4Tcp() {
        for (String g : new String[] {DUAL, V6ONLY, V4ONLY}) {
            assertEquals("global=" + g, IpModeVerdict.REJECT,
                    IpModeVerdict.verdictAction(IpModeVerdict.M_BLOCK4,
                            false, false, false, g));
        }
    }

    @Test
    public void block4PassesV6Tcp() {
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.verdictAction(IpModeVerdict.M_BLOCK4,
                        true, false, false, DUAL));
    }

    @Test
    public void block6RejectsV6Tcp() {
        for (String g : new String[] {DUAL, V6ONLY, V4ONLY}) {
            assertEquals("global=" + g, IpModeVerdict.REJECT,
                    IpModeVerdict.verdictAction(IpModeVerdict.M_BLOCK6,
                            true, false, false, g));
        }
    }

    @Test
    public void block6PassesV4Tcp() {
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.verdictAction(IpModeVerdict.M_BLOCK6,
                        false, false, false, DUAL));
    }

    @Test
    public void block4RejectsIpv4MappedAndNat64Tcp() {
        // Celah NAT64/IPv4-mapped tertutup: tujuan embed dihitung v4.
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.verdictAction(IpModeVerdict.M_BLOCK4,
                        IpModeVerdict.effFamilyV6(MAPPED), false, false, DUAL));
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.verdictAction(IpModeVerdict.M_BLOCK4,
                        IpModeVerdict.effFamilyV6(NAT64), false, false, DUAL));
        // ... dan app BLOCK v6 TIDAK terpengaruh tujuan embed (v4 lolos).
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.verdictAction(IpModeVerdict.M_BLOCK6,
                        IpModeVerdict.effFamilyV6(MAPPED), false, false, DUAL));
    }

    @Test
    public void appWithoutModePasses() {
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.verdictAction(IpModeVerdict.M_NONE,
                        false, true, true, DUAL));
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.verdictAction(IpModeVerdict.M_NONE,
                        true, true, true, DUAL));
    }

    // ================================================================
    // Mode paksa v4/v6 — hanya aktif saat global dual (guard)
    // ================================================================

    @Test
    public void forcedV4DropsV6OnlyWhenDual() {
        assertEquals(IpModeVerdict.DROP_SILENT,
                IpModeVerdict.synAction(IpModeVerdict.M_V4, true, DUAL));
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.synAction(IpModeVerdict.M_V4, false, DUAL));
        // Guard non-dual: tidak ditegakkan (UI menonaktifkan barisnya).
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.synAction(IpModeVerdict.M_V4, true, V6ONLY));
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.synAction(IpModeVerdict.M_V4, true, V4ONLY));
    }

    @Test
    public void forcedV6DropsV4OnlyWhenDual() {
        assertEquals(IpModeVerdict.DROP_SILENT,
                IpModeVerdict.synAction(IpModeVerdict.M_V6, false, DUAL));
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.synAction(IpModeVerdict.M_V6, true, DUAL));
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.synAction(IpModeVerdict.M_V6, false, V6ONLY));
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.synAction(IpModeVerdict.M_V6, false, V4ONLY));
    }

    @Test
    public void forcedModeOnEmbeddedCountsAsV4() {
        // Mode paksa v6 (tolak v4) + tujuan NAT64 -> family efektif v4
        // -> drop diam saat dual.
        assertEquals(IpModeVerdict.DROP_SILENT,
                IpModeVerdict.synAction(IpModeVerdict.M_V6,
                        IpModeVerdict.effFamilyV6(NAT64), DUAL));
    }

    // ================================================================
    // UDP (termasuk QUIC — UDP non-DNS) — jalur paket == cut live
    // ================================================================

    @Test
    public void block4RejectsV4UdpQuic() {
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.udpVerdict(IpModeVerdict.M_BLOCK4,
                        false, false, false, DUAL));
    }

    @Test
    public void block6RejectsV6UdpQuic() {
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.udpVerdict(IpModeVerdict.M_BLOCK6,
                        true, false, false, DUAL));
    }

    @Test
    public void block4ViaV6EmbedRejectedOnUdp() {
        // UDP ke 64:ff9b::/96 dari app BLOCK v4 -> REJECT (unreach
        // ICMPv6, family kabel, kernel mengaitkan ke socket v6 app).
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.udpVerdict(IpModeVerdict.M_BLOCK4,
                        IpModeVerdict.effFamilyV6(NAT64), false, false, DUAL));
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.udpVerdict(IpModeVerdict.M_BLOCK4,
                        IpModeVerdict.effFamilyV6(MAPPED), false, false, DUAL));
    }

    @Test
    public void block6AppStillUsesV4UdpViaEmbed() {
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.udpVerdict(IpModeVerdict.M_BLOCK6,
                        IpModeVerdict.effFamilyV6(MAPPED), false, false, DUAL));
    }

    @Test
    public void forcedModeFiltersUdpOnlyWhenDual() {
        // QUIC/IP literal dari mode paksa: drop diam saat dual...
        assertEquals(IpModeVerdict.DROP_SILENT,
                IpModeVerdict.udpVerdict(IpModeVerdict.M_V4, true,
                        false, false, DUAL));
        assertEquals(IpModeVerdict.DROP_SILENT,
                IpModeVerdict.udpVerdict(IpModeVerdict.M_V6, false,
                        false, false, DUAL));
        // ...tidak ditegakkan saat non-dual...
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.udpVerdict(IpModeVerdict.M_V4, true,
                        false, false, V6ONLY));
        // ...dan family searah tetap lolos.
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.udpVerdict(IpModeVerdict.M_V4, false,
                        false, false, DUAL));
    }

    @Test
    public void udpVerdictMatrixForKnownOwners() {
        // Matriks lengkap utk pemilik dikenal (b4/b6 tak relevan).
        int[] mm = {IpModeVerdict.M_NONE, IpModeVerdict.M_V4,
                IpModeVerdict.M_V6, IpModeVerdict.M_BLOCK4,
                IpModeVerdict.M_BLOCK6};
        for (int mode : mm) {
            for (boolean effV6 : new boolean[] {false, true}) {
                for (String g : new String[] {DUAL, V6ONLY, V4ONLY}) {
                    assertEquals("mode=" + mode + " effV6=" + effV6
                                    + " g=" + g,
                            IpModeVerdict.verdictAction(mode, effV6,
                                    false, false, g),
                            IpModeVerdict.udpVerdict(mode, effV6,
                                    false, false, g));
                }
            }
        }
        // Invarian terbukti utk SEMUA kombinasi pemilik-dikenal:
        // UDP verdict == TCP verdict (REJECT utk BLOCK family,
        // DROP_SILENT utk mode paksa lawan, selain itu PASS).
    }

    // ================================================================
    // DNS (killA / killAAAA / preferFamily)
    // ================================================================

    @Test
    public void block4KillsARecords() {
        IpModeVerdict.DnsPolicy p = IpModeVerdict.dnsPolicy(
                IpModeVerdict.M_BLOCK4, false, false, DUAL);
        assertTrue(p.killA);
        assertFalse(p.killAAAA);
        assertEquals(6, p.preferFamily); // upstream diarahkan hindari v4
    }

    @Test
    public void block6KillsAaaaRecords() {
        IpModeVerdict.DnsPolicy p = IpModeVerdict.dnsPolicy(
                IpModeVerdict.M_BLOCK6, false, false, DUAL);
        assertFalse(p.killA);
        assertTrue(p.killAAAA);
        assertEquals(4, p.preferFamily);
    }

    @Test
    public void blockWorksInAllGlobalModesDns() {
        for (String g : new String[] {DUAL, V6ONLY, V4ONLY}) {
            assertTrue(IpModeVerdict.dnsPolicy(
                    IpModeVerdict.M_BLOCK4, false, false, g).killA);
            assertTrue(IpModeVerdict.dnsPolicy(
                    IpModeVerdict.M_BLOCK6, false, false, g).killAAAA);
        }
    }

    @Test
    public void forcedV4KillsAaaaOnlyWhenDual() {
        assertTrue(IpModeVerdict.dnsPolicy(
                IpModeVerdict.M_V4, false, false, DUAL).killAAAA);
        assertFalse(IpModeVerdict.dnsPolicy(
                IpModeVerdict.M_V4, false, false, V6ONLY).killAAAA);
        assertFalse(IpModeVerdict.dnsPolicy(
                IpModeVerdict.M_V4, false, false, V4ONLY).killAAAA);
        // Mode paksa tidak mengubah jalur upstream VPN.
        assertEquals(0, IpModeVerdict.dnsPolicy(
                IpModeVerdict.M_V4, false, false, DUAL).preferFamily);
    }

    @Test
    public void forcedV6KillsAOnlyWhenDual() {
        assertTrue(IpModeVerdict.dnsPolicy(
                IpModeVerdict.M_V6, false, false, DUAL).killA);
        assertFalse(IpModeVerdict.dnsPolicy(
                IpModeVerdict.M_V6, false, false, V6ONLY).killA);
    }

    @Test
    public void noModeNoKill() {
        IpModeVerdict.DnsPolicy p = IpModeVerdict.dnsPolicy(
                IpModeVerdict.M_NONE, true, true, DUAL);
        assertFalse(p.killA);
        assertFalse(p.killAAAA);
        assertEquals(0, p.preferFamily);
    }

    // ================================================================
    // Shared UID: deterministik, BLOCK menang atas mode paksa
    // ================================================================

    @Test
    public void blockWinsOverForcedRegardlessOfOrder() {
        Map<String, String> m1 = modes("com.a", "block4", "com.b", "v6");
        Map<String, String> m2 = modes("com.a", "v6", "com.b", "block4");
        String[] pkgs = {"com.a", "com.b"};
        String[] pkgsRev = {"com.b", "com.a"};
        assertEquals(IpModeVerdict.M_BLOCK4,
                IpModeVerdict.uidVerdictMode(pkgs, m1));
        assertEquals(IpModeVerdict.M_BLOCK4,
                IpModeVerdict.uidVerdictMode(pkgsRev, m1));
        assertEquals(IpModeVerdict.M_BLOCK4,
                IpModeVerdict.uidVerdictMode(pkgs, m2));
        assertEquals(IpModeVerdict.M_BLOCK4,
                IpModeVerdict.uidVerdictMode(pkgsRev, m2));
    }

    @Test
    public void labelIsDeterminingPackage() {
        Map<String, String> m = modes("com.a", "v6", "com.b", "block4");
        assertEquals("com.b",
                IpModeVerdict.uidVerdictLabel(new String[] {"com.a", "com.b"}, m));
        // Tanpa mode sama sekali -> fallback paket pertama.
        assertEquals("com.a",
                IpModeVerdict.uidVerdictLabel(
                        new String[] {"com.a", "com.b"}, new HashMap<>()));
    }

    @Test
    public void firstForcedWinsWhenNoBlock() {
        Map<String, String> m = modes("com.a", "v6", "com.b", "v4");
        assertEquals(IpModeVerdict.M_V6,
                IpModeVerdict.uidVerdictMode(new String[] {"com.a", "com.b"}, m));
        assertEquals(IpModeVerdict.M_V4,
                IpModeVerdict.uidVerdictMode(new String[] {"com.b", "com.a"}, m));
    }

    @Test
    public void unknownPkgsSafe() {
        assertEquals(IpModeVerdict.M_NONE,
                IpModeVerdict.uidVerdictMode(null, new HashMap<>()));
        assertEquals(IpModeVerdict.M_NONE,
                IpModeVerdict.uidVerdictMode(new String[0], new HashMap<>()));
        assertNull(IpModeVerdict.uidVerdictLabel(null, new HashMap<>()));
        assertNull(IpModeVerdict.uidVerdictLabel(new String[0], new HashMap<>()));
    }

    // ================================================================
    // PEMILIK TIDAK DIKENAL — fail-closed per family
    // (A3: bukti dampak samping — lihat FailClosedSideEffectsTest)
    // ================================================================

    @Test
    public void unknownOwnerPassesWhenNoBlockActive() {
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.verdictAction(null, false, false, false, DUAL));
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.verdictAction(null, true, false, false, DUAL));
    }

    @Test
    public void unknownOwnerRejectedOnlyForBlockedFamily() {
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.verdictAction(null, false, true, false, DUAL));
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.verdictAction(null, true, false, true, DUAL));
        // Family yang tidak diblok tetap lolos.
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.verdictAction(null, true, true, false, DUAL));
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.verdictAction(null, false, false, true, DUAL));
    }

    @Test
    public void unknownOwnerUdpFailClosedPerFamily() {
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.udpVerdict(null, false, true, false, DUAL));
        assertEquals(IpModeVerdict.REJECT,
                IpModeVerdict.udpVerdict(null, true, false, true, DUAL));
        assertEquals(IpModeVerdict.PASS,
                IpModeVerdict.udpVerdict(null, false, false, true, DUAL));
    }

    @Test
    public void unknownOwnerDnsFailClosedPerFamily() {
        IpModeVerdict.DnsPolicy p = IpModeVerdict.dnsPolicy(
                null, true, false, DUAL);
        assertTrue(p.killA);
        assertFalse(p.killAAAA);
        p = IpModeVerdict.dnsPolicy(null, false, true, DUAL);
        assertFalse(p.killA);
        assertTrue(p.killAAAA);
        // Kedua family diblok app berbeda -> query unknown NODATA semua
        // (A=kehilangan, AAAA=kehilangan) — konsisten dgn data plane.
        p = IpModeVerdict.dnsPolicy(null, true, true, DUAL);
        assertTrue(p.killA);
        assertTrue(p.killAAAA);
    }

    @Test
    public void unknownOwnerFailClosedInAllGlobalModes() {
        for (String g : new String[] {DUAL, V6ONLY, V4ONLY}) {
            assertEquals(IpModeVerdict.REJECT,
                    IpModeVerdict.verdictAction(null, false, true, false, g));
        }
    }
}
