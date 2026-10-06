package com.wpmulti.test;

parcelable EngineStatus;

/**
 * Kontrak AIDL proses-utama <-> :goengine.
 * Semua kontrol engine harus lewat interface ini; komponen proses utama
 * dilarang memanggil mobile.Mobile.* langsung (runtime gomobile hanya
 * hidup di proses :goengine — pemanggilan di proses lain membuat runtime
 * Go kedua = split-brain).
 */
interface IEngineControl {
    /**
     * START: menjalankan engine (N sesi WireGuard + SOCKS5 + HTTP proxy).
     * MEMBLOKIR sampai semua sesi siap (~2 dtk utk 100 sesi, ~8 dtk utk
     * 1200 sesi). Return "" = sukses; selain itu pesan error.
     */
    String startEngine(String confDir);

    /**
     * STOP: hard kill proses :goengine (oneway — tidak ada reply karena
     * proses pemanggil perintah ini memang mati). Di dalam: stopForeground,
     * lalu Process.killProcess(myPid()). Tanpa System.exit(), tanpa
     * Mobile.stop() (dev.Close() satu per satu bisa menggantung).
     */
    oneway void stopEngine();

    /**
     * GET_STATUS: satu sumber kebenaran status engine (running, sessions,
     * goroutines, path unix socket relay, memStats; sessionStats hanya
     * bila includeSessions=true karena JSON-nya besar).
     */
    EngineStatus getStatus(boolean includeSessions);

    /**
     * GET_DEBUG_MEM: detail memori runtime Go (memStats mentah + jumlah
     * goroutine) untuk diagnostik.
     */
    String getDebugMem();

    /**
     * Tulis profil goroutine/heap dari PROSES ENGINE ke path (dump dari
     * proses utama tidak akan melihat runtime Go yang benar).
     */
    String writeProfile(boolean heap, String path);
}
