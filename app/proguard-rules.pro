# === wpmulti ===
# gomobile: method native didaftarkan by name via JNI.
# Jangan strip/rename kelas mobile.*.
-keep class mobile.** { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
# kwik (QUIC untuk DoQ): simpan utuh.
-keep class net.luminis.** { *; }
# View custom monitoring (belum dipakai layout, jangan dibuang R8).
-keep class com.wpmulti.test.TrafficGraphView { *; }

# kwik memakai javax.crypto.spec.ChaCha20ParameterSpec (JDK 11+) yang TIDAK
# ADA di Android SDK -> R8 warning disuppress. CATATAN: ini hanya
# membungkam build; bila DoQ menegosiasikan cipher ChaCha20 di runtime,
# kwik akan NoClassDefFoundError (latent crash, ada bahkan tanpa R8).
# DoQ wajib dites di HP.
-dontwarn javax.crypto.spec.ChaCha20ParameterSpec
