package com.wpmulti.test;

import java.util.Map;

/**
 * Logika verdict Mode IP per aplikasi — FUNGSI MURNI (cy10.11).
 *
 * <p>Satu-satunya sumber logika verdict yang dipakai {@link VpnEngine}
 * (delegasi). Dipisah dari engine supaya bisa diuji JUnit langsung di JVM
 * (menggantikan mirror Python cy10.10 — tes kini menguji KODE yang
 * benar-benar berjalan di data plane, bukan salinannya). Tanpa dependensi
 * Android sama sekali: hanya java.util.
 *
 * <p>Konvensi parameter (semua final & bebas efek samping):
 * <ul>
 * <li>{@code mode} — kode mode pemilik ({@code null} = pemilik TIDAK
 *     DIKETAHUI; lihat {@link #verdictAction});</li>
 * <li>{@code effV6} — family EFEKTIF tujuan (alamat ter-embed-v4 dihitung
 *     v4; lihat {@link #effFamilyV6});</li>
 * <li>{@code blockV4Active}/{@code blockV6Active} — apakah minimal satu
 *     app mem-BLOCK family itu (kunci fail-closed);</li>
 * <li>{@code globalIpMode} — mode global sesi ini
 *     ("dual" / "v6only" / "v4only").</li>
 * </ul>
 *
 * <p>Aksi: {@link #PASS} = teruskan; {@link #DROP_SILENT} = buang tanpa
 * jawaban (mode paksa — Happy Eyeballs); {@link #REJECT} = tolak cepat
 * (BLOCK: TCP RST / UDP ICMP-unreach / DNS NODATA; fail-closed).
 */
public final class IpModeVerdict {

    /** Kode mode per-app (nilai prefs {@code vpn_app_ip_<pkg>}). */
    public static final int M_NONE = 0;
    /** "v4": paksa IPv4 (BYPASS v6 — paket v6 dibuang di tunnel). */
    public static final int M_V4 = 1;
    /** "v6": paksa IPv6 (BYPASS v4 — paket v4 dibuang di tunnel). */
    public static final int M_V6 = 2;
    /** "block4": buang SEMUA IPv4 milik app (TCP/UDP/DNS). */
    public static final int M_BLOCK4 = 3;
    /** "block6": buang SEMUA IPv6 milik app (TCP/UDP/DNS). */
    public static final int M_BLOCK6 = 4;

    /** Teruskan paket. */
    public static final int PASS = 0;
    /** Buang diam (mode paksa family lawan). */
    public static final int DROP_SILENT = 1;
    /** Tolak cepat: RST / ICMP unreach / DNS NODATA (BLOCK, fail-closed). */
    public static final int REJECT = 2;

    private IpModeVerdict() {}

    /**
     * Mode IP per-app sebagai kode int (lebih murah daripada String.equals
     * di jalur paket). 0 = tanpa mode / nilai tak dikenal.
     */
    public static int modeCode(String m) {
        if (m == null) return M_NONE;
        switch (m) {
            case "v4": return M_V4;
            case "v6": return M_V6;
            case "block4": return M_BLOCK4;
            case "block6": return M_BLOCK6;
            default: return M_NONE;
        }
    }

    /**
     * Alamat IPv6 yang membawa alamat IPv4 ter-embed —
     * {@code ::ffff:0:0/96} (IPv4-mapped, RFC 4291) dan
     * {@code 64:ff9b::/96} (prefiks NAT64 well-known RFC 6052 — dipakai
     * 464XLAT/Android di jaringan v6-only). Koneksi ke alamat begini
     * menjangkau tujuan IPv4; untuk verdict BLOCK/paksa dihitung family
     * v4 — tanpa ini app BLOCK v4 bisa lolos lewat socket AF_INET6 +
     * alamat ter-embed. (6to4 {@code 2002::/16} &amp; Teredo
     * {@code 2001:0::/32} tidak dibedakan — butuh infra relay khusus
     * yang deprecated; tercatat di KNOWN_ISSUES.)
     */
    public static boolean isV4EmbeddedV6(byte[] a) {
        if (a == null || a.length != 16) return false;
        boolean zeros10 = true;
        for (int i = 0; i < 10; i++) if (a[i] != 0) { zeros10 = false; break; }
        if (zeros10 && (a[10] & 0xFF) == 0xFF && (a[11] & 0xFF) == 0xFF)
            return true; // ::ffff:0:0/96
        if ((a[0] & 0xFF) == 0x00 && (a[1] & 0xFF) == 0x64
                && (a[2] & 0xFF) == 0xFF && (a[3] & 0xFF) == 0x9B) {
            for (int i = 4; i < 12; i++) if (a[i] != 0) return false;
            return true; // 64:ff9b::/96
        }
        return false;
    }

    /**
     * Family EFEKTIF untuk verdict: tujuan 16-byte ter-embed-v4 dihitung
     * v4; selain itu family = family kabel (4 byte = v4).
     */
    public static boolean effFamilyV6(byte[] dstB) {
        return dstB != null && dstB.length == 16 && !isV4EmbeddedV6(dstB);
    }

    /**
     * Aksi untuk SYN TCP milik app ber-mode. Mode paksa v4/v6 hanya aktif
     * saat global dual (perilaku lama); BLOCK berlaku di SEMUA mode global
     * (TUN di-auto-capture dual bila ada app BLOCK — lihat onStartCommand).
     */
    public static int synAction(int mode, boolean effV6, String globalIpMode) {
        switch (mode) {
            case M_V4: return "dual".equals(globalIpMode) && effV6
                    ? DROP_SILENT : PASS;
            case M_V6: return "dual".equals(globalIpMode) && !effV6
                    ? DROP_SILENT : PASS;
            case M_BLOCK4: return effV6 ? PASS : REJECT;
            case M_BLOCK6: return effV6 ? REJECT : PASS;
        }
        return PASS;
    }

    /**
     * Verdict LENGKAP untuk aliran BARU (TCP) — memutus juga kasus pemilik
     * TIDAK DIKETAHUI ({@code mode == null}): FAIL-CLOSED untuk family yang
     * sedang diblok minimal satu app -> REJECT (tolak cepat, dihitung sbg
     * drop "(unknown)" di statistik). Sebelum cy10.10 pemilik tak dikenal
     * selalu lolos (fail-open, G7). Mode paksa v4/v6 TIDAK
     * difail-close-kan (aturan per-app tak bisa ditebak tanpa pemilik;
     * blok tetap tertutup jalur ini).
     */
    public static int verdictAction(Integer mode, boolean effV6,
            boolean blockV4Active, boolean blockV6Active, String globalIpMode) {
        if (mode == null) {
            if (!effV6 && blockV4Active) return REJECT;
            if (effV6 && blockV6Active) return REJECT;
            return PASS;
        }
        return synAction(mode, effV6, globalIpMode);
    }

    /**
     * Verdict UDP non-DNS (jalur paket handleUdp/handleUdp6 DAN cut live
     * apply — keduanya harus identik): BLOCK family efektif -> REJECT
     * (unreach); mode paksa family lawan (hanya global dual) ->
     * DROP_SILENT (mirror SYN TCP); pemilik tak dikenal -> fail-closed
     * REJECT bila family efektifnya sedang diblok.
     */
    public static int udpVerdict(Integer mode, boolean effV6,
            boolean blockV4Active, boolean blockV6Active, String globalIpMode) {
        if (mode == null) {
            return (effV6 ? blockV6Active : blockV4Active) ? REJECT : PASS;
        }
        if (effV6 ? mode == M_BLOCK6 : mode == M_BLOCK4) return REJECT;
        if (synAction(mode, effV6, globalIpMode) == DROP_SILENT)
            return DROP_SILENT;
        return PASS;
    }

    /**
     * Mode utk satu UID dari daftar paketnya: mode BLOCK MENANG atas mode
     * paksa — untuk shared UID hasil kini deterministik (dulu: paket
     * pertama yang punya mode apa pun menang, urutan getPackagesForUid
     * tidak dijamin). pkgs null/kosong -> M_NONE.
     */
    public static int uidVerdictMode(String[] pkgs, Map<String, String> appIpModes) {
        if (pkgs == null || pkgs.length == 0) return M_NONE;
        int best = M_NONE;
        for (String p : pkgs) {
            int m = modeCode(appIpModes.get(p));
            if (m == M_BLOCK4 || m == M_BLOCK6) return m;
            if ((m == M_V4 || m == M_V6) && best == M_NONE) best = m;
        }
        return best;
    }

    /**
     * Paket penentu verdict (utk statistik log): paket ber-mode BLOCK
     * (prioritas), else paket ber-mode paksa pertama, else pkgs[0].
     * null/kosong -> null.
     */
    public static String uidVerdictLabel(String[] pkgs, Map<String, String> appIpModes) {
        if (pkgs == null || pkgs.length == 0) return null;
        String label = null;
        for (String p : pkgs) {
            int m = modeCode(appIpModes.get(p));
            if (m == M_BLOCK4 || m == M_BLOCK6) return p;
            if ((m == M_V4 || m == M_V6) && label == null) label = p;
        }
        return label != null ? label : pkgs[0];
    }

    /**
     * Kebijakan utk satu query DNS berdasarkan verdict pemiliknya.
     * killA/killAAAA = qtype A/AAAA dijawab NODATA lokal:
     * <ul>
     * <li>BLOCK v4/v6 (semua mode global, req 2c);</li>
     * <li>mode paksa v4/v6 (HANYA global dual): family lawan — "hanya via
     *     IPv6" benar-benar menolak resolusi A;</li>
     * <li>pemilik TAK DIKETAHUI + family itu sedang diblok: FAIL-CLOSED
     *     (data plane juga menolak family itu; jawaban kosong = gagal
     *     cepat &amp; konsisten).</li>
     * </ul>
     * preferFamily (family upstream yang DIUTAMAKAN, kebalikan blok):
     * hanya utk mode BLOCK — mode paksa tidak mengubah jalur upstream
     * kita (socket milik proses VPN, bukan milik app).
     */
    public static final class DnsPolicy {
        /** Jawab qtype A (1) dengan NODATA lokal. */
        public final boolean killA;
        /** Jawab qtype AAAA (28) dengan NODATA lokal. */
        public final boolean killAAAA;
        /** Family upstream yang diutamakan: 0 netral / 4 / 6. */
        public final int preferFamily;

        DnsPolicy(boolean ka, boolean kaa, int pf) {
            killA = ka; killAAAA = kaa; preferFamily = pf;
        }
    }

    public static DnsPolicy dnsPolicy(Integer mode, boolean blockV4Active,
            boolean blockV6Active, String globalIpMode) {
        boolean killA = false, killAAAA = false;
        if (mode != null) {
            if (mode == M_BLOCK4) killA = true;
            else if (mode == M_BLOCK6) killAAAA = true;
            else if (mode == M_V4 && "dual".equals(globalIpMode)) killAAAA = true;
            else if (mode == M_V6 && "dual".equals(globalIpMode)) killA = true;
        } else if (blockV4Active || blockV6Active) {
            if (blockV4Active) killA = true;
            if (blockV6Active) killAAAA = true;
        }
        int preferFamily = 0;
        if (mode != null && mode == M_BLOCK4) preferFamily = 6;
        else if (mode != null && mode == M_BLOCK6) preferFamily = 4;
        return new DnsPolicy(killA, killAAAA, preferFamily);
    }
}
