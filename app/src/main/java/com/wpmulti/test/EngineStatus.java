package com.wpmulti.test;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * EngineStatus — SATU sumber kebenaran status engine (dikirim dari proses
 * :goengine lewat IEngineControl.getStatus()). Semua label UI (header,
 * proxy, VPN) dan angka monitor wajib diturunkan dari objek ini; tidak
 * ada lagi pembacaan Mobile.* langsung di proses utama.
 */
public class EngineStatus implements Parcelable {
    /** true = engine (Mobile.start) sudah sukses dan proxy listen. */
    public boolean running;
    /** Jumlah sesi WireGuard aktif (0 bila mati). */
    public long sessions;
    /** Jumlah goroutine runtime Go (0 bila mati). */
    public int goroutines;
    /** JSON mentah memStats runtime Go (null bila mati). */
    public String memStats;
    /** JSON per sesi (hanya diisi bila pemanggil minta includeSessions). */
    public String sessionStats;
    /** Path unix socket relay SOCKS5 (null/"" bila engine mati). */
    public String socksPath;
    /** Path unix socket relay UDP (null/"" bila engine mati). */
    public String udpPath;
    /** Path unix socket relay ICMP (null/"" bila engine mati). */
    public String icmpPath;
    /** Pesan error transisi terakhir (null bila tidak ada). */
    public String lastError;

    public EngineStatus() {}

    /** Status "engine mati" (semua nol/null). */
    public static EngineStatus stopped() { return new EngineStatus(); }

    protected EngineStatus(Parcel in) {
        running = in.readByte() != 0;
        sessions = in.readLong();
        goroutines = in.readInt();
        memStats = in.readString();
        sessionStats = in.readString();
        socksPath = in.readString();
        udpPath = in.readString();
        icmpPath = in.readString();
        lastError = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeByte((byte) (running ? 1 : 0));
        dest.writeLong(sessions);
        dest.writeInt(goroutines);
        dest.writeString(memStats);
        dest.writeString(sessionStats);
        dest.writeString(socksPath);
        dest.writeString(udpPath);
        dest.writeString(icmpPath);
        dest.writeString(lastError);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<EngineStatus> CREATOR =
            new Creator<EngineStatus>() {
                @Override
                public EngineStatus createFromParcel(Parcel in) {
                    return new EngineStatus(in);
                }

                @Override
                public EngineStatus[] newArray(int size) {
                    return new EngineStatus[size];
                }
            };
}
