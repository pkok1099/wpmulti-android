package com.wpmulti.test;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.Settings;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import net.luminis.quic.QuicClientConnection;
import net.luminis.quic.QuicStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

// TAHAP 1: TIDAK ADA lagi import mobile.Mobile — VpnEngine berjalan di
// proses utama; path unix socket relay diambil dari EngineClient (GET_STATUS
// dari proses :goengine).

/**
 * VpnService yang me-route seluruh trafik device lewat SOCKS5 lokal
 * (127.0.0.1:1080) milik engine wpmulti.
 *
 * - TUN 10.0.0.2/32 + fd00::2/128, route 0.0.0.0/0 + ::/0, DNS 1.1.1.1
 * - TCP (v4 & v6): relay via SOCKS5 CONNECT (userspace TCP state machine).
 *   Destinasi IPv6 dikirim dengan ATYP 0x04 (16 byte).
 * - UDP/53 (DNS, v4 & v6): forward ke 1.1.1.1 via socket ter-proteksi.
 *   Query AAAA di-forward apa adanya (byte query bersifat opaque).
 * - ICMPv6: hanya Echo Request (type 128) yang ditujukan ke alamat TUN
 *   sendiri yang dibalas (diagnostik). Sisanya di-drop.
 * - UDP non-DNS, ICMP(v4), dan paket lain: drop.
 *
 * KEPUTUSAN DESAIN (didokumentasikan agar tidak ditebak ulang):
 *
 * 1. Fragmentasi IPv6 -> DROP (bukan bypass, bukan reassembly).
 *    Alasan: (a) TCP kita clamp MSS ke 1460 (v4) / 1440 (v6) sehingga segmen TCP tidak
 *    pernah perlu fragmentasi; (b) satu-satunya UDP yang di-relay adalah
 *    DNS yang kecil; (c) reassembly butuh state per-flow + timer untuk
 *    keuntungan praktis nol. Drop != bocor: paket hilang, tidak keluar
 *    jalur langsung.
 *    Pengecualian: paket dengan fragment header tapi offset=0 dan M=0
 *    adalah paket utuh -> diproses normal (skip 8 byte fragment header).
 *
 * 2. NDP (Neighbor Solicitation/Advertisement, type 133-137) -> ABAIKAN.
 *    TUN dari VpnService bersifat point-to-point tanpa L2; kernel tidak
 *    butuh neighbor discovery untuk me-route via interface ini. Paket IP
 *    dari aplikasi tiba sebagai paket IP utuh di fd TUN.
 *
 * 3. ICMPv6 transit (ping ke host internet) -> DROP.
 *    Userspace tanpa raw socket tidak bisa mengirim ICMP sungguhan.
 *    Menjawab dengan echo palsu adalah kebohongan yang menyesatkan
 *    (RTT palsu). Satu-satunya yang dijawab: ping ke fd00::2 (TUN
 *    sendiri) sebagai bukti jalur TUN hidup.
 *
 * 4. ESP (50) / AH (51) -> DROP. ESP terenkripsi sehingga next-header
 *    tidak bisa dibaca dari userspace; AH punya panjang variabel yang
 *    tidak bisa di-skip dengan aman tanpa parsing penuh.
 *
 * 5. DNS upstream tetap 1.1.1.1 via IPv4. Format wire DNS identik untuk
 *    query A maupun AAAA; yang di-relay hanya byte responsenya. Reply
 *    dikirim kembali via IPv6 dengan source 2606:4700:4700::1111
 *    (anycast v6 Cloudflare, provider yang sama).
 *
 * 6. Anti-loop: addDisallowedApplication(getPackageName()) berlaku untuk
 *    semua trafik aplikasi (v4 & v6), dan setiap socket yang kita buat
 *    (termasuk yang IPv6) dilewatkan ke protect().
 *
 * 7. Checksum UDP atas IPv6 WAJIB dihitung (tidak boleh 0 seperti di
 *    IPv4); jika hasil komputasi 0, dikirim sebagai 0xFFFF sesuai RFC 768.
 */
public class VpnEngine extends VpnService {
    private static final String TAG = "VpnEngine";
    /** Buffer diagnostik TCP; dibaca MainActivity untuk halaman Log. */
    public static final java.util.concurrent.ConcurrentLinkedQueue<String>
            tcpDiag = new java.util.concurrent.ConcurrentLinkedQueue<>();
    private static void diag(String x) {
        android.util.Log.i(TAG, "DIAG " + x);
        tcpDiag.offer(x);
        while (tcpDiag.size() > 200) tcpDiag.poll();
    }

    /** Broadcast saat status VPN berubah. */
    public static final String ACTION_VPN_STATE = "com.wpmulti.test.VPN_STATE";
    public static final String EXTRA_RUNNING = "running";

    public static volatile boolean running = false;

    private static final String SOCKS_HOST = "127.0.0.1";
    private static final int SOCKS_PORT = 1080;
    private static final String VPN_ADDR = "10.0.0.2";
    private static final int VPN_PREFIX = 32;
    /** Alamat ULA untuk TUN IPv6 (tidak akan clash dengan jaringan nyata). */
    private static final String VPN_ADDR6 = "fd00::2";
    private static final int VPN_PREFIX6 = 128;
    /** Source address untuk reply DNS atas IPv6 (anycast v6 Cloudflare). */
    private static final int MTU = 1500;

    private ParcelFileDescriptor tunFd;
    private volatile boolean stopFlag;
    private Thread readerThread;
    private Thread writerThread;
    private ExecutorService pool;
    private final java.util.concurrent.ConcurrentHashMap<String, UdpFlow> udpFlows =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, IcmpFlow> icmpFlows =
            new java.util.concurrent.ConcurrentHashMap<>();
    public static volatile long icmpTunCount = 0;
    public static volatile long icmpGoOkCount = 0;
    public static volatile String icmpLastHex = "";
    /** Alamat TUN IPv6 dalam 16 byte; di-set di onStartCommand. */
    private byte[] tunAddr6;
    // DIBATASI 4096: tanpa batas, banjir paket (TUN menulis lebih cepat
    // dari writer) menumpuk queue hingga ratusan MB -> OOM. offer() yang
    // gagal = paket di-drop; TCP retransmit, DNS client retry.
    private final LinkedBlockingQueue<byte[]> writeQueue =
            new LinkedBlockingQueue<>(4096);
    // Batas koneksi TCP konkuren: tiap koneksi = 1-2 thread relay (200
    // koneksi = ~400 thread). Tanpa cap, socket bocor/QUIC storm membebani
    // RAM + scheduler. Lebih dari ini: RST (klien mencoba lagi).
    private static final int MAX_TCP_CONNS = 512;
    private final ConcurrentHashMap<String, TcpConn> tcpConns =
            new ConcurrentHashMap<>();
    private final AtomicLong statRx = new AtomicLong();
    private final AtomicLong statTx = new AtomicLong();

    public static long bytesRx() { return inst != null ? inst.statRx.get() : 0; }
    public static long bytesTx() { return inst != null ? inst.statTx.get() : 0; }
    public static int connCount() {
        return inst != null ? inst.tcpConns.size() : 0;
    }
    private static volatile VpnEngine inst;

    // Mode DNS: "plain" | "dot" | "doh" | "doq". Di-set di onStartCommand.
    private String dnsMode = "plain";
    // Mode IP global: "dual" | "v6only" | "v4only". Di-set di onStartCommand.
    private String ipMode = "dual";
    // Mode IP per aplikasi: pkg -> "v4" | "v6" | "block4" | "block6".
    // Di-set di onStartCommand DAN bisa ditukar LIVE mid-sesi (cy10.8
    // applyAppIpModes - dibaca ulang dari SharedPreferences saat user
    // mengubah mode per-app / massal di UI). volatile: jalur paket membaca
    // referensi map - swap atomik, pembaca selalu melihat map lama ATAU
    // baru yang utuh, tidak pernah setengah terisi.
    private volatile java.util.Map<String, String> appIpModes =
            java.util.Collections.emptyMap();
    // cy10.7: ada app ber-mode BLOCK? (unci utk auto dual-capture & jalur
    // cepat UDP/DNS yang tidak perlu jalan saat fitur tak dipakai).
    // cy10.8: volatile - ikut ditukar live bersama appIpModes.
    private volatile boolean anyBlockApp = false;
    // cy10.8: family yang BENAR-BENAR ditangkap route TUN sesi ini
    // (ditetapkan saat establish - route VpnService tidak bisa diubah
    // pada sesi yang sudah jalan). Dipakai utk memberi tahu user bila
    // live-apply mode BLOCK tidak bisa ditegakkan penuh (family yang
    // diblok belum masuk TUN -> paketnya tidak pernah sampai ke kita).
    private volatile boolean tunHasV4 = false;
    private volatile boolean tunHasV6 = false;
    // Target upstream: IP literal (plain/dot/doq) atau URL lengkap (doh).
    private String dnsTarget = "";
    private SSLSocket dotSocket;      // koneksi DoT persisten (family target)
    private String dotServer = "";
    // cy10.7: koneksi DoT kedua utk upstream family-remap (query app
    // BLOCK yang HARUS menghindari versi IP yang diblok - req 2c).
    private SSLSocket dotSocketAlt;
    private String dotServerAlt = "";
    private QuicClientConnection doqConn; // koneksi DoQ persisten
    private String doqServer = "";

    // Kill switch: deteksi putus tak terduga + auto-reconnect.
    public static final String ACTION_VPN_DROP = "com.wpmulti.test.VPN_DROP";
    private static volatile boolean expectedStop = false;
    private static volatile int reconnectAttempts = 0;
    private static final int MAX_RECONNECT = 3;
    private static String lastDnsMode = "plain";
    private static String lastDnsIp = "1.1.1.1";
    private static String lastDnsTarget = "";
    private static String lastIpMode = "dual";
    private static String lastAppMode = "all";
    private static String[] lastAppList = new String[0];
    private static Context appCtx;
    private static final Handler reconnectHandler =
            new Handler(Looper.getMainLooper());

    // Mematikan VPN secara eksplisit dan sinkron.
    // Menutup TUN langsung (VPN mati di level sistem saat itu juga),
    // lalu memastikan service berhenti. Lebih andal daripada hanya
    // mengandalkan stopService() -> onDestroy() yang timing-nya
    // tergantung framework. Idempoten: aman dipanggil berulang.
    public static void disconnect() {
        expectedStop = true; // user yang mematikan -> bukan insiden
        reconnectAttempts = 0;
        reconnectHandler.removeCallbacksAndMessages(null);
        running = false;
        VpnEngine v = inst;
        if (v != null) {
            v.cleanup();
            v.broadcast(false);
            try { v.stopSelf(); } catch (Exception ignored) {}
        }
        android.util.Log.i(TAG, "disconnect() dipanggil");
    }

    // ================= lifecycle =================

    private static boolean isV6Literal(String s) {
        return s != null && s.contains(":");
    }

    // cy10.7: mode IP per-app sebagai kode int (lebih murah daripada
    // String.equals di jalur paket). 0 = tanpa mode.
    private static int modeCode(String m) {
        if (m == null) return 0;
        switch (m) {
            case "v4": return 1;
            case "v6": return 2;
            case "block4": return 3;
            case "block6": return 4;
            default: return 0;
        }
    }

    /** Pemilik aliran + mode IP-nya (cy10.7). */
    private static final class FlowOwner {
        final int mode;   // kode modeCode; 0 = tak ada
        final String pkg; // utk statistik log (null bila mode 0)
        FlowOwner(int m, String p) { mode = m; pkg = p; }
    }

    // Cache verdict per 4-tuple. Verdict dibuang & dibangun ulang saat
    // mode berubah (cy10.8 live apply: ownerCache.clear() bersama swap
    // appIpModes) - entri lama bisa memegang verdict mode yang sudah basi.
    // Cap 2048 + clear penuh (sederhana, deterministik; rebuild via lookup).
    private final ConcurrentHashMap<String, FlowOwner> ownerCache =
            new ConcurrentHashMap<>();

    /**
     * Cari mode IP app pemilik aliran ini (TCP maupun UDP) lewat
     * ConnectivityManager.getConnectionOwnerUid (conntrack netd).
     * Fail-open: kegagalan/gagal mengenal -> null (diizinkan).
     * Hasil di-cache per 4-tuple - lookup binder hanya sekali per
     * koneksi/aliran baru, SYN retransmit & query DNS berikutnya dari
     * port sumber yang sama memakai cache.
     */
    private FlowOwner flowOwner(boolean isV6, int proto,
            byte[] srcB, int srcPort, byte[] dstB, int dstPort) {
        if (appIpModes.isEmpty()) return null;
        String key = (isV6 ? "6:" : "4:") + ipStr(srcB) + ":" + srcPort
                + ">" + ipStr(dstB) + ":" + dstPort;
        FlowOwner o = ownerCache.get(key);
        if (o != null) return o;
        int uid;
        try {
            java.net.InetSocketAddress local = new java.net.InetSocketAddress(
                    java.net.InetAddress.getByAddress(srcB), srcPort);
            java.net.InetSocketAddress remote = new java.net.InetSocketAddress(
                    java.net.InetAddress.getByAddress(dstB), dstPort);
            uid = connOwnerUid(proto, local, remote);
        } catch (Exception e) {
            return null; // fail-open: lebih baik lolos daripada putus
        }
        if (uid < 0) return null;
        String[] pkgs;
        try {
            pkgs = getPackageManager().getPackagesForUid(uid);
        } catch (Exception e) {
            return null;
        }
        if (pkgs == null) return null;
        FlowOwner found = null;
        for (String p : pkgs) {
            int m = modeCode(appIpModes.get(p));
            if (m != 0) { found = new FlowOwner(m, p); break; }
        }
        if (found == null) found = new FlowOwner(0, null);
        if (ownerCache.size() > 2048) ownerCache.clear();
        ownerCache.put(key, found);
        return found;
    }

    /**
     * Aksi utk SYN TCP milik app ber-mode: 0 = teruskan, 1 = drop diam
     * (perilaku lama mode v4/v6 - Happy Eyeballs), 2 = TOLAK CEPAT via
     * RST (mode BLOCK, req 2b). Mode v4/v6 hanya aktif saat global dual
     * (perilaku lama); BLOCK berlaku di SEMUA mode global karena TUN
     * di-auto-capture dual bila ada app BLOCK (lihat onStartCommand).
     */
    private int synAction(FlowOwner o, boolean isV6) {
        if (o == null || o.mode == 0) return 0;
        switch (o.mode) {
            case 1: return "dual".equals(ipMode) && isV6 ? 1 : 0;
            case 2: return "dual".equals(ipMode) && !isV6 ? 1 : 0;
            case 3: return isV6 ? 0 : 2; // BLOCK v4
            case 4: return isV6 ? 2 : 0; // BLOCK v6
        }
        return 0;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (running) return START_NOT_STICKY;
        try {
            // cy10.8: appCtx duluan - readAppIpModes() butuh context utk
            // membaca prefs (helper yang sama dipakai jalur live apply).
            appCtx = getApplicationContext();
            Builder b = new Builder();
            b.setSession("wpmulti-vpn");
            b.setMtu(MTU);
            // Mode IP: dual (default), v6only (bypass IPv4),
            // v4only (bypass IPv6).
            String ipMode = intent != null
                    ? intent.getStringExtra("vpn_ip_mode") : null;
            if (ipMode == null || ipMode.isEmpty()) ipMode = "dual";
            // Mode IP per aplikasi dibaca DULU (req 2a): bila ada app
            // ber-mode BLOCK, versi IP yang akan diblok HARUS ditangkap
            // ke TUN - tanpa jalur bypass - supaya bisa ditolak di data
            // plane. Route VPN bersifat global (satu TUN utk semua app),
            // jadi menangkap versi itu utk SATU app = menangkap utk semua;
            // konsekuensinya (trafik versi "bypass" app lain kini ikut
            // tunnel, bukan direct) dicatat di KNOWN_ISSUES.
            java.util.Map<String, String> aim = readAppIpModes();
            appIpModes = aim;
            ownerCache.clear();
            anyBlockApp = false;
            int nBlock4 = 0, nBlock6 = 0;
            for (String m : aim.values()) {
                if ("block4".equals(m)) { nBlock4++; anyBlockApp = true; }
                else if ("block6".equals(m)) { nBlock6++; anyBlockApp = true; }
            }
            android.util.Log.i(TAG,
                    "mode IP per-app: " + aim.size() + " aplikasi"
                    + (anyBlockApp ? " (BLOCK v4: " + nBlock4
                      + ", BLOCK v6: " + nBlock6 + ")" : ""));
            boolean upgraded = false;
            // cy10.8: catat family yang BENAR-BENAR ditangkap route TUN
            // sesi ini - route tidak bisa diubah setelah establish (batas
            // API VpnService); live apply mode BLOCK pada family yang tak
            // ditangkap tidak bisa ditegakkan penuh (paket tak pernah masuk
            // TUN) -> dilaporkan ke user via LIVE_PARTIAL.
            tunHasV4 = tunHasV6 = false;
            if ("v6only".equals(ipMode)) {
                b.addAddress(VPN_ADDR6, VPN_PREFIX6);
                b.addRoute("::", 0);
                tunHasV6 = true;
                if (anyBlockApp) {
                    // BLOCK v4 butuh paket v4 masuk TUN -> capture v4 juga
                    b.addAddress(VPN_ADDR, VPN_PREFIX);
                    b.addRoute("0.0.0.0", 0);
                    tunHasV4 = true;
                    upgraded = true;
                }
            } else if ("v4only".equals(ipMode)) {
                b.addAddress(VPN_ADDR, VPN_PREFIX);
                b.addRoute("0.0.0.0", 0);
                tunHasV4 = true;
                if (anyBlockApp) {
                    b.addAddress(VPN_ADDR6, VPN_PREFIX6);
                    b.addRoute("::", 0);
                    tunHasV6 = true;
                    upgraded = true;
                }
            } else {
                ipMode = "dual";
                b.addAddress(VPN_ADDR, VPN_PREFIX);
                b.addAddress(VPN_ADDR6, VPN_PREFIX6);
                b.addRoute("0.0.0.0", 0);
                b.addRoute("::", 0); // tangkap juga trafik IPv6 -> anti bocor
                tunHasV4 = tunHasV6 = true;
            }
            this.ipMode = ipMode;
            if (anyBlockApp) {
                blockLogOffer("aktif: " + (nBlock4 + nBlock6) + " app"
                        + " (BLOCK v4: " + nBlock4 + ", BLOCK v6: " + nBlock6
                        + ")"
                        + (upgraded ? "; TUN dinaikkan ke dual-capture"
                          + " agar blok ditegakkan (versi bypass app lain"
                          + " kini lewat tunnel)" : ""));
            }
            // Kill switch: allowBypass() TIDAK dipanggil -> bypass dilarang.
            // (API 29+: default Builder sudah memblokir bypass.)
            // Konfigurasi DNS dari UI: mode + IP iklan + target upstream.
            String m = intent != null ? intent.getStringExtra("dns_mode") : null;
            String ip = intent != null ? intent.getStringExtra("dns_ip") : null;
            String tgt = intent != null ? intent.getStringExtra("dns_target") : null;
            if (m == null || m.isEmpty()) m = "plain";
            if (ip == null || ip.isEmpty()) ip = "1.1.1.1";
            if (tgt == null || tgt.isEmpty()) tgt = ip;
            dnsMode = m;
            dnsTarget = tgt;
            // Iklan DNS harus terjangkau lewat TUN: pada v6only pakai
            // IPv6, pada v4only pakai IPv4 (fallback bila tidak cocok).
            // Upstream (dnsTarget) tetap lewat socket ter-proteksi.
            String advertise = ip;
            if ("v6only".equals(ipMode) && !isV6Literal(ip)) {
                advertise = "2606:4700:4700::1111";
            } else if ("v4only".equals(ipMode) && isV6Literal(ip)) {
                advertise = "1.1.1.1";
            }
            b.addDnsServer(advertise);
            android.util.Log.i(TAG, "DNS mode=" + dnsMode +
                    " advertise=" + advertise + " target=" + dnsTarget);
            // Per-app VPN (split tunneling).
            String appMode = intent != null
                    ? intent.getStringExtra("vpn_app_mode") : null;
            String[] appList = intent != null
                    ? intent.getStringArrayExtra("vpn_app_list") : null;
            if (appMode == null) appMode = "all";
            if (appList == null) appList = new String[0];
            try {
                if (appMode.equals("allow")) {
                    // Allowlist: hanya app terpilih. App sendiri tidak
                    // didaftarkan -> otomatis bypass (anti-loop terjaga).
                    int n = 0;
                    for (String pkg : appList) {
                        try {
                            b.addAllowedApplication(pkg);
                            n++;
                        } catch (Exception e) {
                            android.util.Log.w(TAG,
                                    "allow app gagal: " + pkg);
                        }
                    }
                    android.util.Log.i(TAG, "per-app allowlist: " + n + " app");
                } else {
                    // "all" & "deny": app sendiri selalu bypass (anti-loop).
                    try {
                        b.addDisallowedApplication(getPackageName());
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "disallow self gagal: " + e);
                    }
                    if (appMode.equals("deny")) {
                        int n = 0;
                        for (String pkg : appList) {
                            try {
                                b.addDisallowedApplication(pkg);
                                n++;
                            } catch (Exception e) {
                                android.util.Log.w(TAG,
                                        "deny app gagal: " + pkg);
                            }
                        }
                        android.util.Log.i(TAG,
                                "per-app denylist: " + n + " app");
                    }
                }
            } catch (Exception e) {
                android.util.Log.w(TAG, "per-app gagal: " + e);
            }
            tunFd = b.establish();
            // CATATAN ARSITEKTUR (TAHAP 1): pemanggilan Mobile.wgProtectFds()
            // DIHAPUS. (1) Runtime Go kini di proses :goengine — fd UDP
            // WireGuard milik proses lain, dan VpnService.protect(fd) hanya
            // berlaku untuk fd milik proses pemanggil, jadi protect lintas
            // proses tidak mungkin. (2) Anti-loop tetap terjamin penuh:
            // VpnEngine selalu mengecualikan UID app sendiri dari TUN
            // (addDisallowedApplication(getPackageName()) pada mode all/
            // deny, dan mode allow hanya me-route app terpilih) — aturan UID
            // berlaku untuk SEMUA proses app, termasuk :goengine.
            if (tunFd == null) {
                broadcast(false);
                stopSelf();
                return START_NOT_STICKY;
            }
            // FGS WAJIB: service ini di-start via startForegroundService
            // (tile QS, auto-reconnect dari background). Tanpa startForeground
            // dalam 5 detik sistem melempar ForegroundServiceDidNotStartIn-
            // TimeException. Tipe specialUse dideklarasikan di manifest.
            startVpnForeground();
            tunAddr6 = parseIp6(VPN_ADDR6);
            stopFlag = false;
            pool = Executors.newCachedThreadPool();
            inst = this;
            running = true;
            startWriter();
            startReader();
            startUdpSweeper();
            broadcast(true);
            // reset kill-switch state: koneksi baru yang sah
            expectedStop = false;
            reconnectAttempts = 0;
            lastDnsMode = dnsMode;
            lastDnsIp = ip;
            lastDnsTarget = dnsTarget;
            lastIpMode = ipMode;
            lastAppMode = appMode;
            lastAppList = appList;
            android.util.Log.i(TAG, "VPN aktif (mode IP: " + ipMode + ")");
        } catch (Exception e) {
            android.util.Log.e(TAG, "start gagal: " + e);
            cleanup();
            broadcast(false);
            stopSelf();
        }
        // NOT_STICKY: VPN tidak boleh hidup lagi sendiri tanpa aksi user
        return START_NOT_STICKY;
    }

    // ================= live apply mode IP per-app (cy10.8) =================

    /** Baca mode IP per-app dari SharedPreferences "vpn"
     * (key vpn_app_ip_<pkg> -> "v4"|"v6"|"block4"|"block6"). Satu sumber
     * utk onStartCommand dan jalur live apply - SharedPreferences adalah
     * instance cache per-proses yang sama dengan penulisnya (MainActivity,
     * proses utama; VpnEngine juga proses utama), jadi apply() dari UI
     * langsung terlihat tanpa menunggu flush disk. */
    private static java.util.Map<String, String> readAppIpModes() {
        java.util.Map<String, String> aim = new java.util.HashMap<>();
        try {
            android.content.SharedPreferences sp = appCtx
                    .getSharedPreferences("vpn", Context.MODE_PRIVATE);
            for (java.util.Map.Entry<String, ?> e : sp.getAll().entrySet()) {
                String k = e.getKey();
                if (k.startsWith("vpn_app_ip_")
                        && e.getValue() instanceof String) {
                    aim.put(k.substring("vpn_app_ip_".length()),
                            (String) e.getValue());
                }
            }
        } catch (Exception ex) {
            android.util.Log.w(TAG, "baca mode IP per-app gagal: " + ex);
        }
        return aim;
    }

    // Hasil applyAppIpModes().
    /** Tidak ada sesi aktif - prefs tersimpan, berlaku saat start. */
    public static final int LIVE_NOP = 0;
    /** Diterapkan penuh ke sesi aktif (semua verdict jalan seketika). */
    public static final int LIVE_OK = 1;
    /** Verdict diterapkan, TAPI ada mode BLOCK utk family yang belum
     * ditangkap route TUN sesi ini -> paket family itu tidak pernah masuk
     * TUN sehingga tidak bisa ditolak; penegakan penuh butuh restart VPN
     * (route VpnService tidak bisa diubah setelah establish). */
    public static final int LIVE_PARTIAL = 2;

    /**
     * cy10.8 (req 2): terapkan mode IP per-app KE SESI AKTIF tanpa restart
     * VPN. Dipanggil MainActivity SETELAH prefs ditulis (ubah per-app
     * maupun aksi massal "terapkan ke semua aplikasi"). Alur: tukar map
     * mode (volatile) + buang cache verdict -> paket BARU (SYN/UDP/DNS)
     * langsung memakai verdict baru; koneksi/flow LAMA yang bertentangan
     * diputus di background (RST/ICMP) supaya app segera mengikuti aturan
     * baru dan bisa fallback/reconnect. Tidak pernah memutus flow yang
     * TIDAK bertentangan - justru sebaliknya, inilah yang mencegah
     * "bypass yang tidak disengaja" dari koneksi tua yang lolos.
     */
    public static int applyAppIpModes() {
        VpnEngine v = inst;
        if (v == null || !running) return LIVE_NOP;
        return v.applyLive();
    }

    private int applyLive() {
        java.util.Map<String, String> aim = readAppIpModes();
        int nb4 = 0, nb6 = 0;
        for (String m : aim.values()) {
            if ("block4".equals(m)) nb4++;
            else if ("block6".equals(m)) nb6++;
        }
        // Bagian sinkron (murah, tanpa binder): swap map + reset cache.
        // Jalur paket melihat map lama ATAU baru yang utuh (volatile ref).
        appIpModes = aim;
        anyBlockApp = nb4 + nb6 > 0;
        ownerCache.clear();
        // Family yang dibutuhkan mode BLOCK vs yang ditangkap TUN ini.
        boolean partial = (nb4 > 0 && !tunHasV4) || (nb6 > 0 && !tunHasV6);
        // Bagian lambat (connOwnerUid = binder call per flow) di background:
        // jangan blok main thread UI. Verdict utk paket baru SUDAH aktif
        // sejak swap di atas; sweep ini hanya menutup koneksi lama.
        final boolean fpartial = partial;
        final int fnb4 = nb4, fnb6 = nb6, fsize = aim.size();
        ExecutorService p = pool;
        if (p != null) {
            p.execute(() -> {
                try {
                    int cut = cutConflictingFlows();
                    blockLogOffer("live: aturan per-app diperbarui ("
                            + fsize + " app; BLOCK v4: " + fnb4
                            + ", v6: " + fnb6 + ")"
                            + (cut > 0 ? "; " + cut + " koneksi lama diputus" : "")
                            + (fpartial ? "; PERHATIAN: ada BLOCK utk versi IP"
                              + " yang belum ditangkap TUN - restart VPN utk"
                              + " penegakan penuh" : ""));
                } catch (Throwable t) {
                    android.util.Log.w(TAG, "cutConflictingFlows: " + t);
                }
            });
        }
        return partial ? LIVE_PARTIAL : LIVE_OK;
    }

    /**
     * Putus SEMUA koneksi TCP & flow UDP yang bertentangan dgn mode yang
     * BARU saja diterapkan. Verdict memakai fungsi yang sama dgn jalur
     * paket (synAction utk TCP; utk UDP hanya mode BLOCK - konsisten dgn
     * handleUdp* yang memang tidak menyaring UDP utk mode v4/v6 lama).
     * Atribusi via conntrack: koneksi TCP ESTABLISHED punya entri stabil;
     * flow UDP one-shot bisa kedaluwarsa -> gagal atribusi = dibiarkan
     * (fail-open, flow idle-timeout <=60 dtk atau paket berikutnya kena
     * verdict baru di handleUdp*). Flow app LAIN tidak pernah tersentuh.
     */
    private int cutConflictingFlows() {
        int n = 0;
        for (TcpConn c : tcpConns.values()) {
            boolean v6 = c.isV6();
            FlowOwner o = flowOwner(v6, 6, c.srcB, c.srcPort,
                    c.dstB, c.dstPort);
            if (o == null) continue;
            if (synAction(o, v6) != 0) { c.resetHard(); n++; }
        }
        for (UdpFlow f : udpFlows.values()) {
            FlowOwner o = flowOwner(f.v6, 17, f.srcB, f.srcPort,
                    f.dstB, f.dstPort);
            if (o == null) continue;
            if ((o.mode == 3 && !f.v6) || (o.mode == 4 && f.v6)) {
                f.unreachAndClose();
                n++;
            }
        }
        return n;
    }

    @Override
    public void onRevoke() {
        android.util.Log.i(TAG, "VPN revoked oleh sistem");
        boolean unexpected = !expectedStop;
        cleanup();
        broadcast(false);
        try { stopSelf(); } catch (Exception ignored) {}
        if (unexpected) {
            onUnexpectedDrop();
        }
        super.onRevoke();
    }

    // Kill switch: VPN mati bukan karena user -> peringatan + reconnect.
    private void onUnexpectedDrop() {
        android.util.Log.w(TAG, "KILL SWITCH: VPN putus tak terduga!");
        Intent d = new Intent(ACTION_VPN_DROP);
        d.setPackage(getPackageName()); // lihat komentar broadcast(boolean)
        sendBroadcast(d);
        alertNotification();
        if (appCtx == null) return;
        boolean auto = appCtx.getSharedPreferences("vpn", Context.MODE_PRIVATE)
                .getBoolean("auto_reconnect", true);
        if (!auto || reconnectAttempts >= MAX_RECONNECT) {
            if (!auto) android.util.Log.i(TAG, "auto-reconnect dimatikan user");
            else android.util.Log.w(TAG, "auto-reconnect menyerah setelah "
                    + MAX_RECONNECT + "x");
            return;
        }
        reconnectAttempts++;
        long delay = reconnectAttempts == 1 ? 2000
                : reconnectAttempts == 2 ? 5000 : 10000;
        final int attempt = reconnectAttempts;
        reconnectHandler.postDelayed(() -> {
            if (!running && !expectedStop && appCtx != null) {
                android.util.Log.i(TAG, "auto-reconnect VPN #" + attempt);
                Intent it = new Intent(appCtx, VpnEngine.class);
                it.putExtra("dns_mode", lastDnsMode);
                it.putExtra("dns_ip", lastDnsIp);
                it.putExtra("dns_target", lastDnsTarget);
                it.putExtra("vpn_ip_mode", lastIpMode);
                it.putExtra("vpn_app_mode", lastAppMode);
                it.putExtra("vpn_app_list", lastAppList);
                try {
                    // app berada di background saat drop -> startService biasa
                    // melempar IllegalStateException di API 26+; FGS diizinkan.
                    appCtx.startForegroundService(it);
                } catch (Exception e) {
                    android.util.Log.w(TAG, "reconnect gagal: " + e);
                }
            }
        }, delay);
    }

    /** Notifikasi FGS "VPN aktif". Channel dibuat sekali. */
    private void startVpnForeground() {
        try {
            String ch = "vpn_active";
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm.getNotificationChannel(ch) == null) {
                nm.createNotificationChannel(new NotificationChannel(ch,
                        "VPN aktif", NotificationManager.IMPORTANCE_LOW));
            }
            Intent it = new Intent(this, MainActivity.class);
            it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, 0, it,
                    PendingIntent.FLAG_IMMUTABLE
                            | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification n = new Notification.Builder(this, ch)
                    .setContentTitle("Wpmulti VPN aktif")
                    .setContentText("Trafik dirutekan melalui tunnel")
                    .setSmallIcon(android.R.drawable.ic_secure)
                    .setContentIntent(pi)
                    .setOngoing(true)
                    .build();
            startForeground(1003, n,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } catch (Exception e) {
            android.util.Log.w(TAG, "startForeground gagal: " + e);
        }
    }

    /**
     * Sweeper UDP global: SATU thread memindai udpFlows tiap 15 detik dan
     * menutup flow idle > 60 detik. Menggantikan sleeper 60 detik per flow
     * (dulu: 2 thread pool per flow, dan flow yang aktif pada detik ke-60
     * TIDAK PERNAH dicek lagi -> bocor thread + fd secara perlahan).
     */
    private void startUdpSweeper() {
        Thread t = new Thread(() -> {
            long lastBlockLog = System.currentTimeMillis();
            while (!stopFlag) {
                try { Thread.sleep(15_000); } catch (InterruptedException ignored) { return; }
                if (stopFlag) return;
                long now = System.currentTimeMillis();
                for (java.util.concurrent.ConcurrentHashMap.Entry<String, UdpFlow> e
                        : udpFlows.entrySet()) {
                    if (now - e.getValue().lastActive > 60_000) {
                        try { e.getValue().close(); } catch (Exception ignored) {}
                    }
                }
                // cy10.7 (req g): ringkasan BLOCK per interval 30 dtk
                // (sweeper jalan tiap 15 dtk -> emisi tiap putaran ke-2).
                if (now - lastBlockLog >= 30_000) {
                    lastBlockLog = now;
                    try { emitBlockSummaries(); } catch (Exception ignored) {}
                }
            }
        }, "vpn-udp-sweeper");
        t.setDaemon(true);
        t.start();
    }

    private void alertNotification() {
        String ch = "vpn_alert";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(new NotificationChannel(ch,
                    "Peringatan VPN",
                    NotificationManager.IMPORTANCE_HIGH));
        }
        Intent it = new Intent(this, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_IMMUTABLE
                        | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(this, ch)
                .setContentTitle("VPN terputus tak terduga!")
                .setContentText("Trafik tidak terlindungi. Ketuk untuk buka app.")
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        nm.notify(1002, n);
    }

    @Override
    public void onDestroy() {
        cleanup();
        broadcast(false);
        super.onDestroy();
    }

    private void broadcast(boolean r) {
        running = r;
        Intent i = new Intent(ACTION_VPN_STATE).putExtra(EXTRA_RUNNING, r);
        // setPackage: broadcast implisit tanpa package bisa terdiam di
        // sebagian OEM utk receiver RECEIVER_NOT_EXPORTED.
        i.setPackage(getPackageName());
        sendBroadcast(i);
    }

    private void cleanup() {
        android.util.Log.i(TAG, "cleanup: tcp=" + tcpConns.size()
            + " udp=" + udpFlows.size() + " icmp=" + icmpFlows.size()
            + " queue=" + writeQueue.size());
        stopFlag = true;
        running = false;
        inst = null;
        // cy10.7: emisi ringkasan terakhir (interval parsial) sebelum
        // statistik dibersihkan, lalu reset state BLOCK.
        try { emitBlockSummaries(); } catch (Exception ignored) {}
        blockStats.clear();
        ownerCache.clear();
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (Exception ignored) {}
        for (Map.Entry<String, TcpConn> e : tcpConns.entrySet()) {
            try { e.getValue().close(); } catch (Exception ignored) {}
        }
        tcpConns.clear();
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        if (writerThread != null) {
            writerThread.interrupt();
            writerThread = null;
        }
        writeQueue.clear();
        // tutup semua UDP flows
        for (java.util.Map.Entry<String, UdpFlow> e : udpFlows.entrySet()) {
            try { e.getValue().close(); } catch (Exception ignored) {}
        }
        udpFlows.clear();
        icmpFlows.clear();
        icmpTunCount = 0;
        icmpGoOkCount = 0;
        try {
            if (tunFd != null) tunFd.close();
        } catch (Exception ignored) {}
        tunFd = null;
        closeQuietly(dotSocket);
        dotSocket = null;
        dotServer = "";
        closeQuietly(dotSocketAlt);
        dotSocketAlt = null;
        dotServerAlt = "";
        closeDoq();
    }

    // ================= TUN I/O =================

    private void startWriter() {
        writerThread = new Thread(() -> {
            FileOutputStream out =
                    new FileOutputStream(tunFd.getFileDescriptor());
            try {
                while (!stopFlag) {
                    byte[] pkt = writeQueue.take();
                    try {
                        out.write(pkt);
                        statTx.addAndGet(pkt.length);
                    } catch (IOException e) {
                        if (!stopFlag)
                            android.util.Log.w(TAG, "tun write: " + e);
                        break;
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                try { out.close(); } catch (Exception ignored) {}
            }
        }, "vpn-writer");
        writerThread.start();
    }

    private void startReader() {
        readerThread = new Thread(() -> {
            FileInputStream in =
                    new FileInputStream(tunFd.getFileDescriptor());
            byte[] buf = new byte[32767];
            try {
                while (!stopFlag) {
                    int n;
                    try {
                        n = in.read(buf);
                    } catch (IOException e) {
                        if (!stopFlag)
                            android.util.Log.w(TAG, "tun read: " + e);
                        break;
                    }
                    if (n <= 0) continue;
                    statRx.addAndGet(n);
                    byte[] pkt = Arrays.copyOf(buf, n);
                    try {
                        handlePacket(pkt);
                    } catch (Exception e) {
                        android.util.Log.w(TAG, "handle: " + e);
                    }
                }
            } finally {
                try { in.close(); } catch (Exception ignored) {}
            }
        }, "vpn-reader");
        readerThread.start();
    }

    private void handlePacket(byte[] pkt) {
        if (pkt.length < 20) return;
        int ver = (pkt[0] >> 4) & 0xF;
        if (ver == 4) {
            handlePacket4(pkt);
        } else if (ver == 6) {
            handlePacket6(pkt);
        }
        // versi lain: drop
    }

    private void handlePacket4(byte[] pkt) {
        int ihl = (pkt[0] & 0xF) * 4;
        if (pkt.length < ihl) return;
        int proto = pkt[9] & 0xFF;
        if (proto == 6) handleTcp(pkt, ihl);
        else if (proto == 17) handleUdp(pkt, ihl);
        else if (proto == 1) handleIcmp(pkt, ihl);
        // lain: drop
    }

    // ================= IPv6 =================

    private void handlePacket6(byte[] pkt) {
        if (pkt.length < 40) return;
        int payloadLen = u16(pkt, 4);
        // Paket terpotong (klaim lebih panjang dari data aktual) -> drop.
        if (40 + payloadLen > pkt.length) return;
        int[] nh = new int[]{ pkt[6] & 0xFF };
        int off = skipExtHeaders(pkt, 40, nh);
        if (off < 0) return; // -1 tak dikenal / -2 fragment -> drop
        byte[] src6 = Arrays.copyOfRange(pkt, 8, 24);
        byte[] dst6 = Arrays.copyOfRange(pkt, 24, 40);
        if (nh[0] == 6) handleTcp6(pkt, off, src6, dst6);
        else if (nh[0] == 17) handleUdp6(pkt, off, src6, dst6);
        else if (nh[0] == 58) handleIcmp6(pkt, off, src6, dst6);
        // lain (termasuk NDP yang sudah difilter di handleIcmp6): drop
    }

    /**
     * Menelusuri rantai extension header IPv6.
     *
     * @param pkt paket IP utuh
     * @param off offset awal (40 = setelah fixed header)
     * @param nh  in/out: nh[0] = next-header awal; diisi next-header final
     * @return offset header upper-layer, atau negatif bila harus di-drop:
     *         -1 = header tak dikenal / tak bisa di-skip (ESP/AH/NoNext),
     *         -2 = fragment yang butuh reassembly (lihat keputusan desain).
     */
    private static int skipExtHeaders(byte[] pkt, int off, int[] nh) {
        int cur = nh[0];
        // Batasi 8 header: paket jahat tidak boleh bikin loop tak berujung.
        for (int i = 0; i < 8; i++) {
            switch (cur) {
                case 6: case 17: case 58: // TCP, UDP, ICMPv6
                    nh[0] = cur;
                    return off;
                case 0: case 43: case 60: { // Hop-by-Hop, Routing, DestOpts
                    if (off + 2 > pkt.length) return -1;
                    // Panjang dalam satuan 8-oktet, tidak termasuk 8 oktet pertama.
                    int len = (pkt[off + 1] & 0xFF) * 8 + 8;
                    cur = pkt[off] & 0xFF;
                    off += len;
                    break;
                }
                case 44: { // Fragment (8 byte tetap)
                    if (off + 8 > pkt.length) return -1;
                    int fragField = u16(pkt, off + 2);
                    int fragOffset = fragField >> 3; // 13 bit offset
                    boolean more = (fragField & 0x01) != 0; // flag M
                    // offset=0 & M=0 berarti paket utuh ber-header fragment:
                    // aman diproses setelah skip 8 byte header ini.
                    if (fragOffset != 0 || more) return -2;
                    cur = pkt[off] & 0xFF;
                    off += 8;
                    break;
                }
                default:
                    // ESP(50): terenkripsi, next-header tak terbaca.
                    // AH(51): panjang variabel, skip tak aman tanpa parse penuh.
                    // NoNext(59) & lainnya: tidak ada upper-layer -> drop.
                    return -1;
            }
            if (off > pkt.length) return -1;
        }
        return -1; // rantai terlalu panjang -> drop
    }

    // ================= UDP (DNS saja) =================

    private void handleUdp(byte[] pkt, int ihl) {
        if (pkt.length < ihl + 8) return;
        int srcPort = u16(pkt, ihl);
        int dstPort = u16(pkt, ihl + 2);
        int udpLen = u16(pkt, ihl + 4);
        if (udpLen < 8 || pkt.length < ihl + udpLen) return;
        byte[] srcB = Arrays.copyOfRange(pkt, 12, 16);
        byte[] dstB = Arrays.copyOfRange(pkt, 16, 20);
        byte[] data = Arrays.copyOfRange(pkt, ihl + 8, ihl + udpLen);
        if (dstPort == 53) {
            // DNS tetap via forwardDns (jawaban NODATA utk versi yang
            // diblok ditangani di sana - req 2c)
            pool.execute(() -> forwardDns(srcB, dstB, srcPort, data, false));
            return;
        }
        // cy10.7: UDP non-DNS dari app BLOCK utk versi IP yang diblok
        // -> DROP + ICMP host-unreachable (req 2b) agar socket ter-connect
        // app mendapat error segera (QUIC/DoH-bawaan-app fallback instan).
        // Lookup hanya saat ada app BLOCK (guard anyBlockApp) - jalur UDP
        // app tanpa mode sama sekali tidak tersentuh.
        if (anyBlockApp) {
            FlowOwner o = flowOwner(false, 17, srcB, srcPort, dstB, dstPort);
            if (o != null && o.mode == 3) { // BLOCK v4, paket v4
                countBlockDrop(o.pkg, false);
                sendUnreach(false, srcB, dstB, pkt);
                return;
            }
        }
        // UDP non-DNS: relay via WireGuard
        String key = ipStr(srcB) + ":" + srcPort + ">" + ipStr(dstB) + ":" + dstPort;
        UdpFlow f = udpFlows.get(key);
        if (f == null) {
            f = new UdpFlow(srcB, srcPort, dstB, dstPort, false);
            UdpFlow old = udpFlows.putIfAbsent(key, f);
            if (old != null) {
                f = old;
            } else {
                f.start();
            }
        }
        f.send(data);
    }

    private void handleUdp6(byte[] pkt, int off, byte[] src6, byte[] dst6) {
        if (pkt.length < off + 8) return;
        int srcPort = u16(pkt, off);
        int dstPort = u16(pkt, off + 2);
        int udpLen = u16(pkt, off + 4);
        if (udpLen < 8 || pkt.length < off + udpLen) return;
        byte[] data = Arrays.copyOfRange(pkt, off + 8, off + udpLen);
        if (dstPort == 53) {
            pool.execute(() -> forwardDns(src6, dst6, srcPort, data, true));
            return;
        }
        // cy10.7: BLOCK v6 utk UDP non-DNS - lihat catatan handleUdp.
        if (anyBlockApp) {
            FlowOwner o = flowOwner(true, 17, src6, srcPort, dst6, dstPort);
            if (o != null && o.mode == 4) { // BLOCK v6, paket v6
                countBlockDrop(o.pkg, true);
                sendUnreach(true, src6, dst6, pkt);
                return;
            }
        }
        String key = ipStr(src6) + ":" + srcPort + ">" + ipStr(dst6) + ":" + dstPort + "6";
        UdpFlow f = udpFlows.get(key);
        if (f == null) {
            f = new UdpFlow(src6, srcPort, dst6, dstPort, true);
            UdpFlow old = udpFlows.putIfAbsent(key, f);
            if (old != null) {
                f = old;
            } else {
                f.start();
            }
        }
        f.send(data);
    }

    // replySrc = alamat server DNS yang di-query klien; HARUS dipakai sebagai
    // source address paket balasan, kalau tidak socket klien yang ter-connect()
    // akan me-drop balasan karena source mismatch (V6-1).
    //
    // cy10.7 (req 2c): utk app ber-mode BLOCK, query DNS utk versi IP yang
    // diblok dijawab LOKAL dengan NOERROR + answer kosong (NODATA - BUKAN
    // NXDOMAIN): app percaya nama itu tak punya alamat versi tsb dan
    // langsung memakai versi yang diizinkan, tanpa percobaan koneksi yang
    // hanya berujung RST. Query versi yang diizinkan diteruskan normal,
    // dan upstream-nya diarahkan MENGHINDARI versi yang diblok (remap ke
    // alamat counterpart provider yang sama bila diketahui) - "jangan beri
    // server DNS versi IP yang diblok".
    private void forwardDns(byte[] srcB, byte[] replySrc, int srcPort,
                            byte[] query, boolean v6) {
        byte[] resp;
        // cy10.7: atribusi pemilik query (via conntrack UDP, protokol 17).
        FlowOwner o = null;
        if (anyBlockApp) {
            o = flowOwner(v6, 17, srcB, srcPort, replySrc, 53);
        }
        int blockFamily = 0; // 4/6 bila pemilik query app BLOCK
        if (o != null && o.mode == 3) blockFamily = 4;
        else if (o != null && o.mode == 4) blockFamily = 6;
        int preferFamily = 0; // family upstream yang DIUTAMAKAN (kebalikan blok)
        if (blockFamily != 0) {
            int qEnd = dnsQuestionEnd(query);
            if (qEnd > 0) {
                int qtype = u16(query, qEnd - 4);
                boolean kill = (blockFamily == 4 && qtype == 1)   // A
                        || (blockFamily == 6 && qtype == 28);      // AAAA
                if (kill) {
                    countBlockDns(o.pkg);
                    sendDnsReply(srcB, replySrc, srcPort,
                            dnsNodata(query, qEnd), v6);
                    return;
                }
            }
            // versi yang diizinkan: hindari versi yang diblok di upstream
            preferFamily = blockFamily == 4 ? 6 : 4;
        }
        try {
            switch (dnsMode) {
                case "dot": resp = dotQuery(query, preferFamily); break;
                case "doh": resp = dohQuery(query); break;
                case "doq": resp = doqQuery(query); break;
                default:    resp = plainDnsQuery(replySrc, query, preferFamily); break;
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "dns " + dnsMode + ": " + e);
            return;
        }
        sendDnsReply(srcB, replySrc, srcPort, resp, v6);
    }

    // Bangun paket UDP balasan replySrc:53 -> srcB:srcPort dan antre ke TUN.
    private void sendDnsReply(byte[] srcB, byte[] replySrc, int srcPort,
                              byte[] resp, boolean v6) {
        try {
            byte[] udp = new byte[8 + resp.length];
            put16(udp, 0, 53);
            put16(udp, 2, srcPort);
            put16(udp, 4, 8 + resp.length);
            put16(udp, 6, 0); // placeholder checksum, diisi di bawah
            System.arraycopy(resp, 0, udp, 8, resp.length);
            if (v6) {
                int csum = checksum6(replySrc, srcB, 17, udp);
                put16(udp, 6, csum == 0 ? 0xFFFF : csum);
                writeQueue.offer(buildIpv6(replySrc, srcB, 17, udp));
            } else {
                put16(udp, 6, 0); // checksum UDP boleh 0 untuk IPv4
                writeQueue.offer(buildIpv4(replySrc, srcB, 17, udp));
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "dns reply: " + e);
        }
    }

    /**
     * Offset AKHIR section question pertama (qname + qtype + qclass)
     * query DNS, atau -1 bila tidak bisa diparse dengan aman (pendek,
     * qname tak wajar, QDCOUNT != 1). Fail-open: pemanggil meneruskan
     * query apa adanya.
     */
    static int dnsQuestionEnd(byte[] q) {
        if (q == null || q.length < 17) return -1; // 12 hdr + root + type/class
        int qd = ((q[4] & 0xFF) << 8) | (q[5] & 0xFF);
        if (qd != 1) return -1; // hanya query 1-pertanyaan (umum)
        int i = 12;
        while (true) {
            if (i >= q.length) return -1;
            int len = q[i] & 0xFF;
            if (len == 0) { i++; break; }
            if (len > 63) return -1; // kompresi tidak sah di query
            i += 1 + len;
            if (i > q.length) return -1;
        }
        int end = i + 4;
        return end <= q.length ? end : -1;
    }

    /**
     * Jawaban NODATA (req 2c): header NOERROR (RCODE=0) + QDCOUNT=1 +
     * ANCOUNT/NSCOUNT/ARCOUNT=0 + question diecho. QR=1, RD disalin dari
     * query, RA=1. BUKAN NXDOMAIN - NXDOMAIN membuat resolver menganggap
     * nama TIDAK ADA sama sekali (memicu negatif-cache & lookup ulang
     * nama), sedangkan NODATA berarti "nama ada, tanpa alamat versi
     * ini" - persis semantik BLOCK.
     */
    static byte[] dnsNodata(byte[] q, int qEnd) {
        byte[] r = new byte[qEnd];
        r[0] = q[0]; r[1] = q[1];                       // ID diecho
        r[2] = (byte) (0x80 | (q[2] & 0x01));           // QR=1, RD disalin
        r[3] = (byte) 0x80;                             // RA=1, RCODE=0
        r[4] = 0; r[5] = 1;                             // QDCOUNT=1
        // ANCOUNT/NSCOUNT/ARCOUNT tetap 0 (array baru)
        System.arraycopy(q, 12, r, 12, qEnd - 12);      // question diecho
        return r;
    }

    /**
     * Pasangan alamat v4<->v6 provider resolver yang sama (anycast
     * publik umum). Dipakai utk mengarahkan upstream query app BLOCK
     * MENGHINDARI versi IP yang diblok tanpa berganti provider (jawaban
     * tetap dari resolver yang sama). null = tak diketahui.
     */
    private static String dnsCounterpart(String ip, boolean wantV6) {
        if (ip == null) return null;
        if (wantV6) {
            switch (ip) {
                case "1.1.1.1":         return "2606:4700:4700::1111";
                case "1.0.0.1":         return "2606:4700:4700::1001";
                case "8.8.8.8":         return "2001:4860:4860::8888";
                case "8.8.4.4":         return "2001:4860:4860::8844";
                case "9.9.9.9":         return "2620:fe::fe";
                case "149.112.112.112": return "2620:fe::9";
                case "208.67.222.222":  return "2620:119:35::35";
                case "208.67.220.220":  return "2620:119:53::53";
                default: return null;
            }
        } else {
            switch (ip) {
                case "2606:4700:4700::1111": return "1.1.1.1";
                case "2606:4700:4700::1001": return "1.0.0.1";
                case "2001:4860:4860::8888": return "8.8.8.8";
                case "2001:4860:4860::8844": return "8.8.4.4";
                case "2620:fe::fe":          return "9.9.9.9";
                case "2620:fe::9":           return "149.112.112.112";
                case "2620:119:35::35":      return "208.67.222.222";
                case "2620:119:53::53":      return "208.67.220.220";
                default: return null;
            }
        }
    }

    // Plain DNS: UDP/53 ke server yang di-query (replySrc).
    // cy10.7 preferFamily (6/4): utk query app BLOCK, kirim upstream via
    // versi yang TIDAK diblok bila counterpart provider diketahui; gagal
    // (mis. jaringan riil tanpa v6) -> fallback ke upstream asli - DNS
    // yang jalan lebih penting daripada kemurnian transport socket KITA
    // (app tetap tanpa koneksi versi yang diblok; celah ini tercatat).
    private byte[] plainDnsQuery(byte[] replySrc, byte[] query,
                                 int preferFamily) throws Exception {
        InetAddress upstream = InetAddress.getByAddress(replySrc);
        if (preferFamily != 0
                && upstream.getAddress().length != (preferFamily == 6 ? 16 : 4)) {
            String cp = dnsCounterpart(ipStr(replySrc), preferFamily == 6);
            if (cp != null) {
                try {
                    return plainQueryOnce(InetAddress.getByName(cp), query);
                } catch (Exception e) {
                    android.util.Log.w(TAG, "dns upstream " + cp + " gagal,"
                            + " fallback family asli: " + e);
                }
            }
        }
        return plainQueryOnce(upstream, query);
    }

    private byte[] plainQueryOnce(InetAddress upstream, byte[] query)
            throws Exception {
        DatagramSocket ds = null;
        try {
            if (upstream.getAddress().length == 16) {
                ds = new DatagramSocket(new InetSocketAddress(
                        InetAddress.getByName("::"), 0));
            } else {
                ds = new DatagramSocket();
            }
            protect(ds); // anti-loop: socket ini tidak masuk TUN
            ds.setSoTimeout(8000);
            ds.send(new DatagramPacket(query, query.length, upstream, 53));
            byte[] rbuf = new byte[4096];
            DatagramPacket in = new DatagramPacket(rbuf, rbuf.length);
            ds.receive(in);
            return Arrays.copyOf(in.getData(), in.getLength());
        } finally {
            if (ds != null) ds.close();
        }
    }

    // DoT (RFC 7858): TLS ke target:853, framing 2-byte length prefix.
    // Koneksi persisten + reconnect saat gagal.
    // cy10.7 preferFamily: utk query app BLOCK, bila target ber-family
    // yang diblok dan counterpart provider diketahui, dipakai koneksi
    // KEDUA (slot alt) ke alamat counterpart - query app itu tidak
    // menumpang versi yang diblok. Slot default tidak tersentuh.
    private synchronized byte[] dotQuery(byte[] query, int preferFamily)
            throws Exception {
        boolean alt = false;
        String server = dnsTarget;
        if (preferFamily != 0) {
            boolean tgtV6 = isV6Literal(dnsTarget); // literal (divalidasi UI)
            if ((preferFamily == 6) != tgtV6) {
                String cp = dnsCounterpart(dnsTarget, preferFamily == 6);
                if (cp != null) { alt = true; server = cp; }
            }
        }
        SSLSocket s = alt ? dotSocketAlt : dotSocket;
        String cur = alt ? dotServerAlt : dotServer;
        if (s == null || s.isClosed() || !server.equals(cur)) {
            closeQuietly(s);
            s = null;
            InetAddress addr = InetAddress.getByName(server);
            SSLSocketFactory f =
                    (SSLSocketFactory) SSLSocketFactory.getDefault();
            SSLSocket ns = (SSLSocket) f.createSocket(addr, 853);
            protect(ns); // belt-and-suspenders (app sudah di-disallow)
            ns.setSoTimeout(10000);
            ns.startHandshake();
            s = ns;
        }
        try {
            OutputStream out = s.getOutputStream();
            out.write((query.length >> 8) & 0xFF);
            out.write(query.length & 0xFF);
            out.write(query);
            out.flush();
            InputStream in = s.getInputStream();
            int hi = in.read(), lo = in.read();
            if (hi < 0 || lo < 0) throw new IOException("dot: EOF");
            int len = (hi << 8) | lo;
            if (len <= 0 || len > 65535) throw new IOException("dot: len " + len);
            byte[] resp = new byte[len];
            readFully(in, resp);
            return resp;
        } catch (Exception e) {
            closeQuietly(s);
            s = null;
            throw e;
        } finally {
            if (alt) { dotSocketAlt = s; dotServerAlt = server; }
            else { dotSocket = s; dotServer = server; }
        }
    }

    // DoH (RFC 8484): HTTPS POST application/dns-message.
    // Tidak perlu protect(): trafik app di-disallow dari VPN (anti-loop).
    private byte[] dohQuery(byte[] query) throws Exception {
        URL url = new URL(dnsTarget);
        HttpsURLConnection c = (HttpsURLConnection) url.openConnection();
        c.setRequestMethod("POST");
        c.setRequestProperty("Content-Type", "application/dns-message");
        c.setRequestProperty("Accept", "application/dns-message");
        c.setConnectTimeout(10000);
        c.setReadTimeout(10000);
        c.setDoOutput(true);
        c.setFixedLengthStreamingMode(query.length);
        try {
            OutputStream out = c.getOutputStream();
            try {
                out.write(query);
            } finally {
                try { out.close(); } catch (Exception ignored) {}
            }
            int rc = c.getResponseCode();
            if (rc != 200) throw new IOException("doh: HTTP " + rc);
            InputStream in = c.getInputStream();
            try {
                return readBounded(in, 65535);
            } finally {
                try { in.close(); } catch (Exception ignored) {}
            }
        } finally {
            c.disconnect();
        }
    }

    // DoQ (RFC 9250): QUIC ke target:853, ALPN "doq" (via kwik).
    // Tiap query = 1 stream bidi baru (2-byte length prefix).
    // Koneksi persisten + reconnect saat gagal.
    private synchronized byte[] doqQuery(byte[] query) throws Exception {
        if (doqConn == null || !dnsTarget.equals(doqServer)) {
            closeDoq();
            String host = dnsTarget.contains(":")
                    ? "[" + dnsTarget + "]" : dnsTarget;
            QuicClientConnection conn = QuicClientConnection.newBuilder()
                    .uri(URI.create("https://" + host + ":853"))
                    .applicationProtocol("doq")
                    .build();
            conn.connect();
            doqConn = conn;
            doqServer = dnsTarget;
        }
        try {
            QuicStream stream = doqConn.createStream(true);
            try {
                OutputStream out = stream.getOutputStream();
                out.write((query.length >> 8) & 0xFF);
                out.write(query.length & 0xFF);
                out.write(query);
                out.flush();
                out.close();
                InputStream in = stream.getInputStream();
                int hi = in.read(), lo = in.read();
                if (hi < 0 || lo < 0) throw new IOException("doq: EOF");
                int len = (hi << 8) | lo;
                if (len <= 0 || len > 65535)
                    throw new IOException("doq: len " + len);
                byte[] resp = new byte[len];
                readFully(in, resp);
                return resp;
            } finally {
                try { stream.abortReading(0); } catch (Exception ignored) {}
            }
        } catch (Exception e) {
            closeDoq();
            throw e;
        }
    }

    private void closeDoq() {
        if (doqConn != null) {
            try { doqConn.close(); } catch (Exception ignored) {}
            doqConn = null;
        }
        doqServer = "";
    }

    private static void readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("EOF prematur");
            off += n;
        }
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        byte[] buf = new byte[4096];
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        int total = 0, n;
        while ((n = in.read(buf)) >= 0) {
            total += n;
            if (total > max) throw new IOException("respons terlalu besar");
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try { c.close(); } catch (Exception ignored) {}
        }
    }

    // ================= ICMPv6 =================

    // cy10.7 (req 2b): balasan CEPAT utk paket versi IP yang diblok.
    // TCP -> RST (sendTcp, jalur yang sama dgn cap/dial-fail). UDP &
    // lainnya -> ICMP/ICMPv6 destination unreachable dari "tujuan":
    //   v4: type 3 code 1 (host unreachable)
    //   v6: type 1 code 0 (no route to destination)
    // Paket asli dikutip (RFC 792/4443) agar kernel bisa mengaitkan
    // error ke socket app yang tepat (EHOSTUNREACH/EHOSTUNREACHv6 pada
    // socket ter-connect) -> fallback instan, bukan timeout.
    private void sendUnreach(boolean isV6, byte[] srcB, byte[] dstB,
                             byte[] origPkt) {
        try {
            if (isV6) {
                // kutip maksimal utk tetap <= MTU minimum IPv6 (1280)
                int ql = Math.min(origPkt.length, 1232);
                byte[] icmp = new byte[8 + ql];
                icmp[0] = 1; icmp[1] = 0;
                System.arraycopy(origPkt, 0, icmp, 8, ql);
                put16(icmp, 2, 0);
                put16(icmp, 2, checksum6(dstB, srcB, 58, icmp));
                writeQueue.offer(buildIpv6(dstB, srcB, 58, icmp));
            } else {
                int ql = Math.min(origPkt.length, 536);
                byte[] icmp = new byte[8 + ql];
                icmp[0] = 3; icmp[1] = 1;
                System.arraycopy(origPkt, 0, icmp, 8, ql);
                put16(icmp, 2, 0);
                put16(icmp, 2, checksum(icmp, 0, icmp.length));
                writeQueue.offer(buildIpv4(dstB, srcB, 1, icmp));
            }
        } catch (Exception e) {
            android.util.Log.w(TAG, "unreach: " + e);
        }
    }

    // ================= statistik BLOCK (req g) =================

    // pkg -> {0: drop v4 interval, 1: drop v6 interval, 2: total v4,
    //         3: total v6, 4: dns NODATA interval, 5: dns NODATA total}
    private final ConcurrentHashMap<String, long[]> blockStats =
            new ConcurrentHashMap<>();
    /** Antrean ringkasan BLOCK utk logView (dibatasi 200; didrain thread
     *  monitor MainActivity saat foreground). TIDAK per paket - diringkas
     *  per interval 30 dtk per app (req g). */
    private static final java.util.concurrent.ConcurrentLinkedQueue<String>
            blockLog = new java.util.concurrent.ConcurrentLinkedQueue<>();

    public static String pollBlockLog() { return blockLog.poll(); }

    private static void blockLogOffer(String line) {
        android.util.Log.i(TAG, "BLOCK " + line);
        blockLog.offer(line);
        while (blockLog.size() > 200) blockLog.poll();
    }

    private void countBlockDrop(String pkg, boolean isV6) {
        if (pkg == null) return;
        long[] v = blockStats.computeIfAbsent(pkg, k -> new long[6]);
        synchronized (v) {
            v[isV6 ? 1 : 0]++;
            v[isV6 ? 3 : 2]++;
        }
    }

    private void countBlockDns(String pkg) {
        if (pkg == null) return;
        long[] v = blockStats.computeIfAbsent(pkg, k -> new long[6]);
        synchronized (v) { v[4]++; v[5]++; }
    }

    /** Ringkasan per interval (dipanggil sweeper tiap ~30 dtk + saat
     *  cleanup): satu baris per app yang punya aktivitas di interval
     *  ini - jumlah paket di-drop + total kumulatif + jumlah jawaban
     *  DNS NODATA. */
    private void emitBlockSummaries() {
        for (Map.Entry<String, long[]> e : blockStats.entrySet()) {
            long[] v = e.getValue();
            synchronized (v) {
                if (v[0] <= 0 && v[1] <= 0 && v[4] <= 0) continue;
                StringBuilder sb = new StringBuilder("BLOCK[30s] ")
                        .append(e.getKey()).append(":");
                if (v[0] > 0 || v[2] > 0)
                    sb.append(" drop v4=").append(v[0])
                      .append(" (tot ").append(v[2]).append(")");
                if (v[1] > 0 || v[3] > 0)
                    sb.append(" drop v6=").append(v[1])
                      .append(" (tot ").append(v[3]).append(")");
                if (v[4] > 0)
                    sb.append("; dns NODATA=").append(v[4])
                      .append(" (tot ").append(v[5]).append(")");
                blockLogOffer(sb.toString());
                v[0] = v[1] = v[4] = 0;
            }
        }
    }

    private void handleIcmp(byte[] pkt, int ihl) {
        if (pkt.length < ihl + 8) return;
        int type = pkt[ihl] & 0xFF;
        if (type == 8) icmpTunCount++;
        if (type != 8) return; // hanya Echo Request
        byte[] srcB = Arrays.copyOfRange(pkt, 12, 16);
        byte[] dstB = Arrays.copyOfRange(pkt, 16, 20);
        int id = u16(pkt, ihl + 4);
        int seq = u16(pkt, ihl + 6);
        byte[] data = Arrays.copyOfRange(pkt, ihl + 8, pkt.length);
        String key = ipStr(srcB) + ">" + ipStr(dstB) + ":" + id;
        IcmpFlow f = icmpFlows.get(key);
        if (f == null) {
            f = new IcmpFlow(srcB, dstB, id, false);
            IcmpFlow old = icmpFlows.putIfAbsent(key, f);
            if (old != null) f = old;
        }
        f.ping(seq, data);
    }

    private void handleIcmp6(byte[] pkt, int off, byte[] src6, byte[] dst6) {
        // Panjang ICMPv6 menurut header IPv6 (bukan pkt.length, untuk presisi).
        int icmpLen = 40 + u16(pkt, 4) - off;
        if (icmpLen < 8 || off + icmpLen > pkt.length) return;
        int type = pkt[off] & 0xFF;
        if (type != 128) return; // bukan Echo Request (termasuk NDP) -> drop
        if (tunAddr6 != null && Arrays.equals(dst6, tunAddr6)) {
            // Ping ke gateway VPN: jawab lokal (perilaku lama)
            byte[] icmp = Arrays.copyOfRange(pkt, off, off + icmpLen);
            icmp[0] = (byte) 129;
            icmp[1] = 0;
            put16(icmp, 2, 0);
            put16(icmp, 2, checksum6(tunAddr6, src6, 58, icmp));
            writeQueue.offer(buildIpv6(tunAddr6, src6, 58, icmp));
            return;
        }
        // Relay via WireGuard
        int id = u16(pkt, off + 4);
        int seq = u16(pkt, off + 6);
        byte[] data = Arrays.copyOfRange(pkt, off + 8, off + icmpLen);
        String key = ipStr(src6) + ">" + ipStr(dst6) + ":" + id + "6";
        IcmpFlow f = icmpFlows.get(key);
        if (f == null) {
            f = new IcmpFlow(src6, dst6, id, true);
            IcmpFlow old = icmpFlows.putIfAbsent(key, f);
            if (old != null) f = old;
        }
        f.ping(seq, data);
    }

    // ================= TCP via SOCKS5 =================

    // UID pemilik koneksi via ConnectivityManager (API 29+).
    // proto: 6 = TCP, 17 = UDP (cy10.7: UDP dipakai utk atribusi query
    // DNS & aliran UDP ke app BLOCK).
    // -1 bila tak diketahui/gagal (mis. izin) -> fail-open.
    private int connOwnerUid(int proto, java.net.InetSocketAddress local,
                             java.net.InetSocketAddress remote) {
        try {
            android.net.ConnectivityManager cm =
                    (android.net.ConnectivityManager) getSystemService(
                            android.content.Context.CONNECTIVITY_SERVICE);
            if (cm == null) return -1;
            return cm.getConnectionOwnerUid(proto, local, remote);
        } catch (Exception ignored) {}
        return -1;
    }

    private void handleTcp(byte[] pkt, int ihl) {
        if (pkt.length < ihl + 20) return;
        int dataOff = ((pkt[ihl + 12] >> 4) & 0xF) * 4;
        if (pkt.length < ihl + dataOff) return;
        int srcPort = u16(pkt, ihl);
        int dstPort = u16(pkt, ihl + 2);
        long seq = u32(pkt, ihl + 4);
        long ack = u32(pkt, ihl + 8);
        int flags = pkt[ihl + 13] & 0xFF;
        String srcIp = ipStr(pkt, 12);
        String dstIp = ipStr(pkt, 16);
        byte[] srcB = Arrays.copyOfRange(pkt, 12, 16);
        byte[] dstB = Arrays.copyOfRange(pkt, 16, 20);
        String key = srcIp + ":" + srcPort + ">" + dstIp + ":" + dstPort;

        TcpConn c = tcpConns.get(key);
        boolean syn = (flags & 0x02) != 0;
        boolean rst = (flags & 0x04) != 0;
        if (c == null) {
            if (!syn || rst) {
                // bukan SYN baru -> kirim RST
                sendTcp(dstB, dstPort, srcB, srcPort,
                        0, seq + 1, 0x14, 0, null);
                return;
            }
            // Cap koneksi konkuren: lebih dari MAX_TCP_CONNS -> RST agar
            // klien tahu (bukan diam yang menunggu timeout).
            if (tcpConns.size() >= MAX_TCP_CONNS) {
                diag("SYN4 DROP cap " + key);
                sendTcp(dstB, dstPort, srcB, srcPort,
                        0, seq + 1, 0x14, 0, null);
                return;
            }
            // Mode IP per aplikasi (cy10.3 + cy10.7). v4/v6 (lama): drop
            // diam agar klien mencoba versi IP lain (Happy Eyeballs) -
            // hanya saat global dual (perilaku lama). BLOCK v4/v6 (baru):
            // TOLAK CEPAT - RST dari "tujuan" supaya connect() gagal
            // seketika dan app langsung fallback (req 2b); berlaku di
            // semua mode global (TUN dual-capture saat ada app BLOCK).
            FlowOwner own = flowOwner(false, 6, srcB, srcPort, dstB, dstPort);
            int act = synAction(own, false);
            if (act == 2) {
                countBlockDrop(own.pkg, false);
                sendTcp(dstB, dstPort, srcB, srcPort,
                        0, seq + 1, 0x14, 0, null);
                return;
            }
            if (act == 1) {
                diag("SYN4 DROP by appIpMode " + key);
                return;
            }
            TcpConn nc = new TcpConn(key, srcB, srcPort, dstB, dstPort, seq);
            if (tcpConns.putIfAbsent(key, nc) == null) {
                pool.execute(nc::connectViaSocks);
            }
            return;
        }
        c.onPacket(seq, ack, flags, pkt, ihl + dataOff);
    }

    private void handleTcp6(byte[] pkt, int off, byte[] src6, byte[] dst6) {
        if (pkt.length < off + 20) return;
        int dataOff = ((pkt[off + 12] >> 4) & 0xF) * 4;
        if (dataOff < 20 || pkt.length < off + dataOff) return;
        int srcPort = u16(pkt, off);
        int dstPort = u16(pkt, off + 2);
        long seq = u32(pkt, off + 4);
        long ack = u32(pkt, off + 8);
        int flags = pkt[off + 13] & 0xFF;
        // String kanonis dari byte (bukan dari teks paket) agar key stabil.
        String srcIp = ip6Str(src6, 0);
        String dstIp = ip6Str(dst6, 0);
        // Prefix "6:" agar tak mungkin clash dengan key IPv4.
        String key = "6:" + srcIp + ":" + srcPort + ">" + dstIp + ":"
                + dstPort;

        TcpConn c = tcpConns.get(key);
        boolean syn = (flags & 0x02) != 0;
        boolean rst = (flags & 0x04) != 0;
        if (c == null) {
            if (!syn || rst) {
                sendTcp(dst6, dstPort, src6, srcPort,
                        0, seq + 1, 0x14, 0, null);
                return;
            }
            if (tcpConns.size() >= MAX_TCP_CONNS) {
                diag("SYN6 DROP cap " + key);
                sendTcp(dst6, dstPort, src6, srcPort,
                        0, seq + 1, 0x14, 0, null);
                return;
            }
            // cy10.7: mode per-app + BLOCK - lihat catatan handleTcp.
            FlowOwner own6 = flowOwner(true, 6, src6, srcPort, dst6, dstPort);
            int act6 = synAction(own6, true);
            if (act6 == 2) {
                countBlockDrop(own6.pkg, true);
                sendTcp(dst6, dstPort, src6, srcPort,
                        0, seq + 1, 0x14, 0, null);
                return;
            }
            if (act6 == 1) {
                diag("SYN6 DROP by appIpMode " + key);
                return;
            }
            TcpConn nc = new TcpConn(key, src6, srcPort, dst6, dstPort, seq);
            if (tcpConns.putIfAbsent(key, nc) == null) {
                pool.execute(nc::connectViaSocks);
            }
            return;
        }
        c.onPacket(seq, ack, flags, pkt, off + dataOff);
    }

    private class TcpConn {
        final String key;
        /** Alamat sebagai byte: 4 byte = IPv4, 16 byte = IPv6. */
        final byte[] srcB, dstB;
        final int srcPort, dstPort;
        volatile android.net.LocalSocket socks;
        volatile int state; // 0=connecting, 1=established, 2=closed
        long clientSeq;   // seq berikutnya yang diharapkan dari klien
        long serverSeq;   // seq berikutnya yang kita kirim
        final Object lock = new Object();

        TcpConn(String k, byte[] sB, int sP, byte[] dB, int dP,
                long synSeq) {
            key = k; srcB = sB; srcPort = sP; dstB = dB; dstPort = dP;
            clientSeq = (synSeq + 1) & 0xFFFFFFFFL;
            // ThreadLocalRandom: new Random() per koneksi = alokasi + seed
            // contention di jalur koneksi.
            serverSeq = java.util.concurrent.ThreadLocalRandom.current()
                    .nextInt() & 0xFFFFFFFFL;
        }

        boolean isV6() { return srcB.length == 16; }

        void connectViaSocks() {
            try {
                // Unix socket: tidak tersentuh routing VPN sama sekali.
                // Path socket MILIK PROSES :goengine — diambil dari snapshot
                // GET_STATUS (EngineClient), bukan dari runtime Go lokal.
                String path = EngineClient.get().socksPath();
                if (path == null || path.isEmpty())
                    throw new IOException("path socket engine belum tersedia (engine mati?)");
                android.net.LocalSocket s = new android.net.LocalSocket();
                s.connect(new android.net.LocalSocketAddress(
                        path,
                        android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                s.setSoTimeout(0);
                // SOCKS5 handshake tanpa auth
                OutputStream o = s.getOutputStream();
                InputStream in = s.getInputStream();
                o.write(new byte[]{0x05, 0x01, 0x00});
                o.flush();
                byte[] r = new byte[2];
                readFully(in, r, 2);
                if (r[0] != 0x05 || r[1] != 0x00) throw new IOException(
                        "socks5 method reject");
                ByteBuffer req;
                if (isV6()) {
                    // ATYP 0x04 = IPv6 (16 byte). Server go-socks5
                    // mendukungnya (statute/addr.go: ATYPIPv6).
                    req = ByteBuffer.allocate(4 + 16 + 2);
                    req.put((byte) 0x05).put((byte) 0x01)
                       .put((byte) 0x00).put((byte) 0x04);
                    req.put(dstB);
                } else {
                    req = ByteBuffer.allocate(4 + 4 + 2);
                    req.put((byte) 0x05).put((byte) 0x01)
                       .put((byte) 0x00).put((byte) 0x01);
                    req.put(dstB);
                }
                req.putShort((short) dstPort);
                o.write(req.array());
                o.flush();
                byte[] rh = new byte[4];
                readFully(in, rh, 4);
                if (rh[0] != 0x05 || rh[1] != 0x00) throw new IOException(
                        "socks5 connect gagal: " + (rh[1] & 0xFF));
                int atyp = rh[3] & 0xFF;
                int alen = atyp == 0x01 ? 4 : atyp == 0x03 ? (in.read() & 0xFF)
                        : atyp == 0x04 ? 16 : 0;
                if (alen > 0) {
                    byte[] dummy = new byte[alen + 2];
                    readFully(in, dummy, dummy.length);
                }
                synchronized (lock) {
                    if (state == 2) { // sudah ditutup saat connecting
                        try { s.close(); } catch (Exception ignored) {}
                        return;
                    }
                    socks = s;
                    state = 1;
                }
                // SYN-ACK ke klien
                // MSS: 1500 MTU - header IP - header TCP = 1460 (v4) / 1440 (v6)
                int mss = dstB.length == 16 ? 1440 : 1460;
                sendTcp(dstB, dstPort, srcB, srcPort,
                        serverSeq, clientSeq, 0x12, mss, null);
                serverSeq = (serverSeq + 1) & 0xFFFFFFFFL; // SYN makan 1
                startRelay();
            } catch (Exception e) {
                diag("DIAL-FAIL " + key + ": " + e);
                android.util.Log.w(TAG, "socks connect " + key + ": " + e);
                // tolak koneksi: RST
                sendTcp(dstB, dstPort, srcB, srcPort,
                        0, clientSeq, 0x14, 0, null);
                removeSelf();
            }
        }

        void onPacket(long seq, long ack, int flags, byte[] pkt,
                      int dataOff) {
            if (state != 1) return;
            boolean fin = (flags & 0x01) != 0;
            boolean rst = (flags & 0x04) != 0;
            if (rst) {
                close();
                return;
            }
            int dataLen = pkt.length - dataOff;
            synchronized (lock) {
                if (seq == clientSeq && dataLen > 0) {
                    try {
                        byte[] data = Arrays.copyOfRange(pkt, dataOff,
                                pkt.length);
                        socks.getOutputStream().write(data);
                        socks.getOutputStream().flush();
                    } catch (Exception e) {
                        sendFin();
                        return;
                    }
                    clientSeq = (clientSeq + dataLen) & 0xFFFFFFFFL;
                }
                // ACK selalu (juga untuk keep-alive / retransmit)
                sendTcp(dstB, dstPort, srcB, srcPort,
                        serverSeq, clientSeq, 0x10, 0, null);
                if (fin) {
                    clientSeq = (clientSeq + 1) & 0xFFFFFFFFL;
                    sendFin();
                }
            }
        }

        void startRelay() {
            pool.execute(() -> {
                byte[] buf = new byte[16384];
                try {
                    InputStream in = socks.getInputStream();
                    while (state == 1) {
                        int n = in.read(buf);
                        if (n < 0) break;
                        byte[] data = Arrays.copyOf(buf, n);
                        synchronized (lock) {
                            if (state != 1) break;
                            sendTcp(dstB, dstPort, srcB, srcPort,
                                    serverSeq, clientSeq, 0x18, 0, data);
                            serverSeq = (serverSeq + n) & 0xFFFFFFFFL;
                        }
                    }
                } catch (Exception ignored) {
                }
                sendFin();
            });
        }

        void sendFin() {
            synchronized (lock) {
                if (state != 1) return;
                state = 2;
            }
            sendTcp(dstB, dstPort, srcB, srcPort,
                    serverSeq, clientSeq, 0x11, 0, null);
            serverSeq = (serverSeq + 1) & 0xFFFFFFFFL;
            removeSelf();
            closeSocket();
        }

        void close() {
            synchronized (lock) { state = 2; }
            removeSelf();
            closeSocket();
        }

        /**
         * cy10.8 (live apply): putus keras karena mode app ini berubah
         * menjadi mode yang bertentangan dgn koneksi ini. RST dikirim
         * dari "tujuan" dgn seq/ack yang benar utk state kini sehingga
         * socket app langsung mendapat ECONNRESET (bukan timeout):
         * - SYN-SENT (state 0): ack = clientSeq = ISS+1 - persis syarat
         *   RST yang diterima kernel pada state ini (RFC 9293).
         * - ESTABLISHED (state 1): seq = serverSeq (snd.nxt kita = byte
         *   berikutnya yang diharapkan app dari "server") -> masuk
         *   receive window app, RST diterima.
         * Setelah state=2, relay/dial berhenti sendiri (guard state di
         * connectViaSocks/startRelay/onPacket) dan socket SOCKS ditutup.
         */
        void resetHard() {
            synchronized (lock) {
                if (state == 2) return;
                state = 2;
                sendTcp(dstB, dstPort, srcB, srcPort,
                        serverSeq, clientSeq, 0x14, 0, null);
            }
            removeSelf();
            closeSocket();
        }

        private void closeSocket() {
            try {
                if (socks != null) socks.close();
            } catch (Exception ignored) {}
            socks = null;
        }

        private void removeSelf() {
            tcpConns.remove(key, this);
        }
    }

    /**
     * Membangun segmen TCP + header IP dan mengantrekannya ke TUN.
     * Versi IP ditentukan dari panjang alamat: 4 byte = IPv4, 16 = IPv6.
     */
    private void sendTcp(byte[] srcB, int srcPort, byte[] dstB, int dstPort,
                         long seq, long ack, int flags, int mss,
                         byte[] data) {
        try {
            boolean v6 = srcB.length == 16;
            int dlen = data != null ? data.length : 0;
            int optLen = mss > 0 ? 4 : 0;
            int tcpLen = 20 + optLen + dlen;
            byte[] tcp = new byte[tcpLen];
            put16(tcp, 0, srcPort);
            put16(tcp, 2, dstPort);
            put32(tcp, 4, seq);
            put32(tcp, 8, ack);
            tcp[12] = (byte) (((20 + optLen) / 4) << 4);
            tcp[13] = (byte) flags;
            put16(tcp, 14, 65535); // window
            if (mss > 0) {
                tcp[20] = 0x02; tcp[21] = 0x04;
                put16(tcp, 22, mss);
            }
            if (dlen > 0) System.arraycopy(data, 0, tcp, 20 + optLen, dlen);
            byte[] ip;
            if (v6) {
                put16(tcp, 16, checksum6(srcB, dstB, 6, tcp));
                ip = buildIpv6(srcB, dstB, 6, tcp);
            } else {
                // checksum TCP dengan pseudo-header IPv4
                byte[] pseudo = new byte[12 + tcpLen];
                System.arraycopy(srcB, 0, pseudo, 0, 4);
                System.arraycopy(dstB, 0, pseudo, 4, 4);
                pseudo[8] = 0; pseudo[9] = 6;
                put16(pseudo, 10, tcpLen);
                System.arraycopy(tcp, 0, pseudo, 12, tcpLen);
                put16(tcp, 16, checksum(pseudo));
                ip = buildIpv4(srcB, dstB, 6, tcp);
            }
            writeQueue.offer(ip);
        } catch (Exception e) {
            android.util.Log.w(TAG, "sendTcp: " + e);
        }
    }

    // ================= util IP =================

    private static String ipStr(byte[] b) {
        try {
            return java.net.InetAddress.getByAddress(b).getHostAddress();
        } catch (Exception e) {
            return "?";
        }
    }

    /** Test ping synchronous via relay ICMP. Return latency ms, atau -1 bila gagal.
     *  reason[0] diisi pesan diagnostik. */
    public static long testPing(boolean v6, String ipStr, String[] reason) {
        long t0 = System.currentTimeMillis();
        try {
            byte[] dstB = ipStrToBytes(ipStr);
            if (dstB == null) { reason[0] = "IP tidak valid"; return -1; }
            android.net.LocalSocket s = new android.net.LocalSocket();
            try {
                String path = EngineClient.get().icmpPath();
                if (path == null || path.isEmpty())
                    throw new Exception("engine mati");
                s.connect(new android.net.LocalSocketAddress(
                        path,
                        android.net.LocalSocketAddress.Namespace.FILESYSTEM));
            } catch (Exception e) {
                reason[0] = "Unix socket gagal: " + e.getMessage();
                try { s.close(); } catch (Exception ignored) {}
                return -1;
            }
            s.setSoTimeout(8000);
            java.io.OutputStream o = s.getOutputStream();
            int id = 0x1234, seq = 1;
            byte[] data = "test".getBytes();
            o.write(v6 ? 0x04 : 0x01);
            o.write(dstB);
            o.write((id >> 8) & 0xFF); o.write(id & 0xFF);
            o.write((seq >> 8) & 0xFF); o.write(seq & 0xFF);
            o.write(0); o.write(data.length);
            o.write(data);
            o.flush();
            java.io.InputStream in = s.getInputStream();
            byte[] hdr = new byte[6];
            try {
                readFully(in, hdr, 6);
            } catch (Exception e) {
                reason[0] = "Timeout baca reply (Go tidak balas 8 dtk)";
                try { s.close(); } catch (Exception ignored) {}
                return -1;
            }
            int rlen = ((hdr[4] & 0xFF) << 8) | (hdr[5] & 0xFF);
            byte[] rdata = new byte[rlen];
            if (rlen > 0) readFully(in, rdata, rlen);
            try { s.close(); } catch (Exception ignored) {}
            long ms = System.currentTimeMillis() - t0;
            reason[0] = "OK id=" + (((hdr[0]&0xFF)<<8)|(hdr[1]&0xFF)) + " len=" + rlen;
            return ms;
        } catch (Exception e) {
            reason[0] = "Exception: " + e;
            return -1;
        }
    }

    private static byte[] ipStrToBytes(String s) {
        try {
            if (s.contains(":")) {
                // IPv6 sederhana via InetAddress
                java.net.InetAddress a = java.net.InetAddress.getByName(s);
                byte[] b = a.getAddress();
                return b.length == 16 ? b : null;
            } else {
                String[] p = s.split("\\.");
                if (p.length != 4) return null;
                byte[] b = new byte[4];
                for (int i = 0; i < 4; i++) {
                    int n = Integer.parseInt(p[i]);
                    if (n < 0 || n > 255) return null;
                    b[i] = (byte) n;
                }
                return b;
            }
        } catch (Exception e) { return null; }
    }

    /** Satu sesi ping: TUN <-> Go via Unix socket. */
    private class IcmpFlow {
        final byte[] srcB, dstB;
        final int id;
        final boolean v6;
        final String key;

        IcmpFlow(byte[] srcB, byte[] dstB, int id, boolean v6) {
            this.srcB = srcB; this.dstB = dstB;
            this.id = id; this.v6 = v6;
            this.key = ipStr(srcB) + ">" + ipStr(dstB) + ":" + id + (v6 ? "6" : "");
        }

        void ping(int seq, byte[] data) {
            final byte[] d = data;
            pool.execute(() -> {
                android.net.LocalSocket s = null;
                try {
                    s = new android.net.LocalSocket();
                    String path = EngineClient.get().icmpPath();
                    if (path == null || path.isEmpty()) throw new Exception("engine mati");
                    s.connect(new android.net.LocalSocketAddress(
                            path,
                            android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                    java.io.OutputStream o = s.getOutputStream();
                    // [ATYP][DST][ID 2B][SEQ 2B][LEN 2B][DATA]
                    o.write(v6 ? 0x04 : 0x01);
                    o.write(dstB);
                    o.write((id >> 8) & 0xFF); o.write(id & 0xFF);
                    o.write((seq >> 8) & 0xFF); o.write(seq & 0xFF);
                    o.write((d.length >> 8) & 0xFF); o.write(d.length & 0xFF);
                    o.write(d);
                    o.flush();
                    // Baca balasan: [ID 2B][SEQ 2B][LEN 2B][DATA]
                    java.io.InputStream in = s.getInputStream();
                    byte[] hdr = new byte[6];
                    readFully(in, hdr, 6);
                    int rlen = ((hdr[4] & 0xFF) << 8) | (hdr[5] & 0xFF);
                    byte[] rdata = new byte[rlen];
                    if (rlen > 0) readFully(in, rdata, rlen);
                    icmpGoOkCount++;
                    // Bangun echo reply
                    byte[] icmp = new byte[8 + rlen];
                    icmp[0] = (byte) (v6 ? 129 : 0);
                    icmp[1] = 0;
                    icmp[4] = hdr[0]; icmp[5] = hdr[1]; // ID
                    icmp[6] = hdr[2]; icmp[7] = hdr[3]; // SEQ
                    System.arraycopy(rdata, 0, icmp, 8, rlen);
                    if (v6) {
                        put16(icmp, 2, 0);
                        put16(icmp, 2, checksum6(dstB, srcB, 58, icmp));
                        writeQueue.offer(buildIpv6(dstB, srcB, 58, icmp));
                    } else {
                        put16(icmp, 2, 0);
                        put16(icmp, 2, checksum(icmp, 0, icmp.length));
                        byte[] ippkt = buildIpv4(dstB, srcB, 1, icmp);
                        StringBuilder hb = new StringBuilder();
                        for (int i = 0; i < Math.min(48, ippkt.length); i++) {
                            hb.append(String.format("%02x", ippkt[i]));
                        }
                        icmpLastHex = hb.toString() + " src=" + ipStr(dstB) + " dst=" + ipStr(srcB);
                        writeQueue.offer(ippkt);
                    }
                } catch (Exception e) {
                    // ping gagal, diam
                } finally {
                    try { if (s != null) s.close(); } catch (Exception ignored) {}
                    // hapus dari map: one-shot, tidak perlu cache
                    icmpFlows.remove(key, this);
                }
            });
        }
    }

    /** Satu aliran UDP: TUN <-> Go via Unix socket. */
    private class UdpFlow {
        final byte[] srcB, dstB;
        final int srcPort, dstPort;
        final boolean v6;
        final String key;
        volatile android.net.LocalSocket sock;
        volatile long lastActive = System.currentTimeMillis();

        UdpFlow(byte[] srcB, int srcPort, byte[] dstB, int dstPort, boolean v6) {
            this.srcB = srcB; this.srcPort = srcPort;
            this.dstB = dstB; this.dstPort = dstPort;
            this.v6 = v6;
            this.key = ipStr(srcB) + ":" + srcPort + ">" + ipStr(dstB) + ":" + dstPort + (v6 ? "6" : "");
        }

        void start() {
            pool.execute(() -> {
                try {
                    android.net.LocalSocket s = new android.net.LocalSocket();
                    String path = EngineClient.get().udpPath();
                    if (path == null || path.isEmpty()) throw new Exception("engine mati");
                    s.connect(new android.net.LocalSocketAddress(
                            path,
                            android.net.LocalSocketAddress.Namespace.FILESYSTEM));
                    // Kirim tujuan: [ATYP][ADDR][PORT]
                    java.io.OutputStream o = s.getOutputStream();
                    if (v6) {
                        o.write(0x04);
                        o.write(dstB);
                    } else {
                        o.write(0x01);
                        o.write(dstB);
                    }
                    o.write((dstPort >> 8) & 0xFF);
                    o.write(dstPort & 0xFF);
                    o.flush();
                    sock = s;
                    // Reader: Go -> TUN
                    java.io.InputStream in = s.getInputStream();
                    byte[] lb = new byte[2];
                    while (true) {
                        readFully(in, lb, 2);
                        int n = ((lb[0] & 0xFF) << 8) | (lb[1] & 0xFF);
                        if (n <= 0 || n > 65535) break;
                        byte[] data = new byte[n];
                        readFully(in, data, n);
                        lastActive = System.currentTimeMillis();
                        // Bangun paket UDP balasan
                        byte[] udp = new byte[8 + n];
                        put16(udp, 0, dstPort);
                        put16(udp, 2, srcPort);
                        put16(udp, 4, 8 + n);
                        System.arraycopy(data, 0, udp, 8, n);
                        if (v6) {
                            int csum = checksum6(dstB, srcB, 17, udp);
                            put16(udp, 6, csum == 0 ? 0xFFFF : csum);
                            writeQueue.offer(buildIpv6(dstB, srcB, 17, udp));
                        } else {
                            put16(udp, 6, 0);
                            writeQueue.offer(buildIpv4(dstB, srcB, 17, udp));
                        }
                    }
                } catch (Exception e) {
                    // flow selesai
                } finally {
                    close();
                }
            });
            // TANPA sleeper 60 detik per flow: (1) menahan satu thread pool
            // selama 60 detik PER flow, (2) hanya mengecek SEKALI sehingga
            // flow yang aktif pada detik ke-60 tidak pernah dicek lagi ->
            // bocor thread + fd. Pengganti: startUdpSweeper() global.
        }

        void send(byte[] data) {
            try {
                android.net.LocalSocket s = sock;
                if (s == null) return; // belum connect, drop
                lastActive = System.currentTimeMillis();
                java.io.OutputStream o = s.getOutputStream();
                synchronized (o) {
                    o.write((data.length >> 8) & 0xFF);
                    o.write(data.length & 0xFF);
                    o.write(data);
                    o.flush();
                }
            } catch (Exception e) {
                close();
            }
        }

        void close() {
            try {
                android.net.LocalSocket s = sock;
                sock = null;
                if (s != null) s.close();
            } catch (Exception ignored) {}
            udpFlows.remove(key, this);
        }

        /**
         * cy10.8 (live apply): tutup flow + kirim ICMP/ICMPv6 destination
         * unreachable dari "tujuan" dgn paket kutipan SINTETIS (header IP
         * + header UDP flow ini - cukup utk kernel mengaitkan error ke
         * socket app yang ter-connect(), RFC 792/4443 hanya mensyaratkan
         * kutipan header). Socket app mendapat EHOSTUNREACH seketika ->
         * app langsung fallback/reconnect versi yang diizinkan, bukan
         * menunggu timeout atau idle-timeout flow.
         */
        void unreachAndClose() {
            try {
                byte[] q;
                if (v6) {
                    q = new byte[40 + 8];
                    q[0] = 0x60; // version 6 (hasil audit harness cy10.8)
                    q[6] = 17; // next header = UDP
                    put16(q, 4, 8); // payload length
                    System.arraycopy(srcB, 0, q, 8, 16);
                    System.arraycopy(dstB, 0, q, 24, 16);
                } else {
                    q = new byte[20 + 8];
                    q[0] = 0x45;
                    put16(q, 2, q.length);
                    q[8] = 64; // TTL
                    q[9] = 17; // protocol = UDP
                    System.arraycopy(srcB, 0, q, 12, 4);
                    System.arraycopy(dstB, 0, q, 16, 4);
                }
                // header UDP di ujung kutipan: sport/dport/len/csum(0)
                put16(q, q.length - 8, srcPort);
                put16(q, q.length - 6, dstPort);
                put16(q, q.length - 4, 8);
                put16(q, q.length - 2, 0);
                sendUnreach(v6, srcB, dstB, q);
            } catch (Exception ignored) {
                // kutipan gagal dibangun -> tetap tutup flow (app berhenti
                // mendapat balasan; paket berikutnya kena verdict baru).
            }
            close();
        }
    }

    private static byte[] buildIpv4(byte[] srcB, byte[] dstB, int proto,
                                   byte[] payload) {
        byte[] ip = new byte[20 + payload.length];
        ip[0] = 0x45;
        put16(ip, 2, ip.length);
        // ID paket: ThreadLocalRandom (dipanggil PER PAKET; new Random()
        // di sini = alokasi + contention per paket di throughput tinggi).
        put16(ip, 4,
                java.util.concurrent.ThreadLocalRandom.current().nextInt(65536));
        ip[8] = 64; // TTL
        ip[9] = (byte) proto;
        System.arraycopy(srcB, 0, ip, 12, 4);
        System.arraycopy(dstB, 0, ip, 16, 4);
        put16(ip, 10, checksum(ip, 0, 20));
        System.arraycopy(payload, 0, ip, 20, payload.length);
        return ip;
    }

    /**
     * Membangun header IPv6 (40 byte, tanpa extension header) + payload.
     * Tidak ada header checksum di IPv6 (diandalkan ke upper-layer).
     */
    private static byte[] buildIpv6(byte[] src6, byte[] dst6, int nextHeader,
                                    byte[] payload) {
        byte[] ip = new byte[40 + payload.length];
        ip[0] = 0x60; // version 6, traffic class 0
        ip[1] = 0x00; // traffic class (4 bit bawah) + flow label (4 bit atas)
        ip[2] = 0x00; // flow label
        ip[3] = 0x00;
        put16(ip, 4, payload.length);
        ip[6] = (byte) nextHeader;
        ip[7] = 64; // hop limit
        System.arraycopy(src6, 0, ip, 8, 16);
        System.arraycopy(dst6, 0, ip, 24, 16);
        System.arraycopy(payload, 0, ip, 40, payload.length);
        return ip;
    }

    /**
     * Checksum upper-layer (TCP/UDP/ICMPv6) dengan pseudo-header IPv6:
     * src(16) + dst(16) + upper-layer-length(32bit) + 3 byte nol +
     * next-header(1) + segmen.
     */
    private static int checksum6(byte[] src6, byte[] dst6, int nextHeader,
                                 byte[] segment) {
        byte[] pseudo = new byte[40 + segment.length];
        System.arraycopy(src6, 0, pseudo, 0, 16);
        System.arraycopy(dst6, 0, pseudo, 16, 16);
        put32(pseudo, 32, segment.length);
        // byte 36..38 sudah nol
        pseudo[39] = (byte) nextHeader;
        System.arraycopy(segment, 0, pseudo, 40, segment.length);
        return checksum(pseudo);
    }

    private static int checksum(byte[] b) {
        return checksum(b, 0, b.length);
    }

    private static int checksum(byte[] b, int off, int len) {
        long sum = 0;
        int i = off;
        while (i + 1 < off + len) {
            sum += ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
            i += 2;
        }
        if (i < off + len) sum += (b[i] & 0xFF) << 8;
        while ((sum >> 16) != 0) sum = (sum & 0xFFFF) + (sum >> 16);
        return (int) (~sum & 0xFFFF);
    }

    /**
     * Parse alamat IPv6 literal -> 16 byte. Memakai InetAddress karena
     * menangani semua bentuk penulisan ("::", "fd00::2", dsb). Untuk
     * literal IP tidak ada lookup DNS.
     */
    private static byte[] parseIp6(String ip) throws IOException {
        byte[] b = InetAddress.getByName(ip).getAddress();
        if (b.length != 16) throw new IOException("bukan IPv6: " + ip);
        return b;
    }

    private static String ipStr(byte[] pkt, int off) {
        return (pkt[off] & 0xFF) + "." + (pkt[off + 1] & 0xFF) + "."
                + (pkt[off + 2] & 0xFF) + "." + (pkt[off + 3] & 0xFF);
    }

    /**
     * 16 byte -> string IPv6 kanonis. Selalu diturunkan dari byte (bukan
     * dari teks paket) agar key koneksi stabil.
     */
    private static String ip6Str(byte[] b, int off) {
        try {
            return InetAddress.getByAddress(
                    Arrays.copyOfRange(b, off, off + 16)).getHostAddress();
        } catch (Exception e) {
            return "invalid";
        }
    }

    private static int u16(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static long u32(byte[] b, int off) {
        return ((b[off] & 0xFFL) << 24) | ((b[off + 1] & 0xFFL) << 16)
                | ((b[off + 2] & 0xFFL) << 8) | (b[off + 3] & 0xFFL);
    }

    private static void put16(byte[] b, int off, int v) {
        b[off] = (byte) ((v >> 8) & 0xFF);
        b[off + 1] = (byte) (v & 0xFF);
    }

    private static void put32(byte[] b, int off, long v) {
        b[off] = (byte) ((v >> 24) & 0xFF);
        b[off + 1] = (byte) ((v >> 16) & 0xFF);
        b[off + 2] = (byte) ((v >> 8) & 0xFF);
        b[off + 3] = (byte) (v & 0xFF);
    }

    private static void readFully(InputStream in, byte[] b, int len)
            throws IOException {
        int off = 0;
        while (off < len) {
            int n = in.read(b, off, len - off);
            if (n < 0) throw new IOException("EOF");
            off += n;
        }
    }
}
