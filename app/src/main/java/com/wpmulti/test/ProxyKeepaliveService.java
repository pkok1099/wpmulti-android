package com.wpmulti.test;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * Foreground service penjaga proses saat proxy wpmulti berjalan.
 *
 * Masalah yang diperbaiki (QA C1): engine (thread + socket Go) hidup di
 * proses aplikasi. Tanpa foreground service, proses berprioritas background
 * dan sistem boleh membunuhnya kapan saja -> proxy mati diam-diam.
 * Service ini menaikkan prioritas proses ke foreground via notifikasi
 * persisten, sehingga sistem tidak membunuh proxy saat app di-background.
 *
 * KEPUTUSAN DESAIN (didokumentasikan agar tidak ditebak ulang):
 *
 * 1. Service ini TIDAK meng-host engine; ia hanya menahan prioritas
 *    proses. Engine tetap di-start/stop dari MainActivity. Alasan: tidak
 *    mengubah arsitektur lifecycle yang sudah bekerja, dan tidak ada
 *    masalah sinkronisasi service<->UI yang baru.
 *
 * 2. START_NOT_STICKY (bukan STICKY): jika proses benar-benar mati,
 *    engine Go ikut mati dan tidak bisa dibangkitkan oleh service saja.
 *    Restart otomatis hanya akan menampilkan notifikasi bohong
 *    ("proxy berjalan" padahal mati). User me-restart manual dari UI.
 *
 * 3. Tipe specialUse (targetSdk 34 mewajibkan deklarasi tipe FGS di
 *    manifest). Tidak ada tipe "proxy" bawaan; specialUse adalah tipe
 *    yang jujur untuk kasus ini, dengan subtype dideklarasikan di
 *    manifest via PROPERTY_SPECIAL_USE_FGS_SUBTYPE.
 *
 * 4. POST_NOTIFICATIONS dideklarasikan di manifest tapi TIDAK diminta
 *    saat runtime: notifikasi foreground service tetap ditampilkan
 *    sistem (exemption FGS) walau izin belum diberikan, dan prioritas
 *    foreground tidak bergantung pada izin itu. Menghindari prompt
 *    izin yang mengganggu alur START.
 *
 * 5. Tidak ada tombol STOP di notifikasi: tap notifikasi membuka
 *    MainActivity, user STOP dari sana. Menghindari jalur stop kedua
 *    yang butuh sinkronisasi state service<->activity.
 */
public class ProxyKeepaliveService extends Service {
    private static final String CHANNEL_ID = "proxy_keepalive";
    private static final int NOTIF_ID = 1001;

    /** Idempoten: aman dipanggil walau service sudah berjalan. */
    public static void start(Context ctx) {
        ctx.startForegroundService(new Intent(ctx, ProxyKeepaliveService.class));
    }

    /** Idempoten: aman dipanggil walau service tidak berjalan. */
    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, ProxyKeepaliveService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        EngineClient.get().init(this);
        Notification n = buildNotification();
        // minSdk 34: 3-arg startForeground + tipe selalu tersedia (API 29+).
        // Tipe specialUse dideklarasikan di manifest (wajib targetSdk 34+).
        startForeground(NOTIF_ID, n,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    private Notification buildNotification() {
        ensureChannel();
        Intent it = new Intent(this, MainActivity.class);
        it.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        // FLAG_IMMUTABLE aman: minSdk 24 >= 23.
        PendingIntent pi = PendingIntent.getActivity(this, 0, it,
                PendingIntent.FLAG_IMMUTABLE
                        | PendingIntent.FLAG_UPDATE_CURRENT);
        // TAHAP 1: jumlah sesi dari snapshot EngineClient (GET_STATUS via
        // binder) — bukan Mobile.sessionCount() di proses utama.
        EngineStatus es = EngineClient.get().snapshot();
        String text = es.running ? es.sessions + " sesi aktif" : "proxy berjalan";
        Notification.Builder b = new Notification.Builder(this, CHANNEL_ID);
        return b.setContentTitle("Wpmulti proxy berjalan")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(new NotificationChannel(
                    CHANNEL_ID, "Proxy berjalan",
                    NotificationManager.IMPORTANCE_LOW));
        }
    }
}
