<img src="docs/banner.svg" alt="Zrec — screen recorder for Android" width="100%">

<p align="center">
  <a href="https://github.com/pancasilayangpertama-maker/zrec-lite/releases/latest">
    <img alt="Download APK" src="https://img.shields.io/github/v/release/pancasilayangpertama-maker/zrec-lite?style=for-the-badge&label=Download%20APK&color=F4F1EC&labelColor=121110">
  </a>
</p>

Screen recorder Android tanpa iklan, tanpa watermark, dan tanpa akun. Dibuat native dengan Kotlin dan framework Android saja, jadi APK-nya di bawah 100 KB.

Satu file, tidak ada tambahan lain. Ambil di [Releases](../../releases/latest).

> [!NOTE]
> Ini **Zrec lite**: tanpa izin jaringan, jadi tidak bisa memeriksa pembaruan sendiri. Kalau mau yang bisa update otomatis dari dalam aplikasi, pakai [Zrec](https://github.com/pancasilayangpertama-maker/zrec).

> [!WARNING]
> **MAKS FPS TETAP BERGANTUNG KEMAMPUAN ASLI DEVICE.**
> Angka di bawah itu batas atas dari Zrec, bukan janji. Kalau encoder HP cuma sanggup 1080p60, ya 60 fps yang muncul dan yang terekam. Zrec membaca kemampuan encoder lewat `MediaCodecList`, jadi yang ditawarkan hanya yang benar-benar bisa dijalankan.

## Features

| Feature | Description |
|---|---|
| **Screen recording** | MediaProjection langsung ke hardware encoder, tanpa re-encode |
| **Floating control** | Bola yang bisa digeser di luar aplikasi: record, stop, pause, screenshot |
| **Hide while recording** | Bola hilang otomatis supaya hasil rekaman bersih |
| **Shake to stop** | Goyang dua kali saat merekam |
| **Screenshot** | Bisa saat merekam atau tanpa merekam, tersimpan ke `Pictures/Zrec` |
| **Audio** | Off, Mic, Internal, atau Mic + internal |
| **Rotation handling** | Rekaman dipecah ke file baru saat layar diputar, jadi tidak ada video miring |
| **Honest options** | Resolusi dan frame rate difilter dari kemampuan encoder HP, bukan daftar karangan |

Hasil rekaman masuk ke `Movies/Zrec`, screenshot ke `Pictures/Zrec`.

## Quality limits

Frame rate dibatasi per resolusi supaya file tidak meledak dan encoder tidak tersendat. Batas HP tetap berlaku dan selalu menang.

| Resolution | Max frame rate |
|---|---|
| 720p | 144 fps |
| 1080p | 120 fps |
| 1440p | 90 fps |
| 4K | 60 fps |

Resolusi terendah 720p. Di bawah itu teks di layar berhenti terbaca setelah dikompres.

## Permissions

- **Screen capture** — dialog bawaan Android, muncul setiap kali mulai merekam.
- **Microphone** — hanya kalau audio disetel ke Mic atau Mic + internal.
- **Display over other apps** — hanya kalau floating control dinyalakan.
- **Notifications** — untuk kontrol rekam saat bola sedang disembunyikan.

Tidak ada akses jaringan. Aplikasi ini tidak mengirim apa pun ke mana pun.

## Build

Butuh JDK 17 dan Android SDK. Tidak perlu Android Studio.

```bash
git clone https://github.com/pancasilayangpertama-maker/zrec-lite.git
cd zrec-lite
gradle assembleRelease
```

APK keluar di `app/build/outputs/apk/release/app-release.apk`.

Build lokal menghasilkan APK **tanpa tanda tangan**. Kunci rilis tidak ada di repo; CI menyimpannya sebagai secret dan menyuntikkannya saat build. Jadi kalau mau memasang hasil build sendiri, tandatangani dulu dengan keystore milik sendiri.

Repo ini juga punya GitHub Actions: setiap push ke `main` menghasilkan APK sebagai artifact, dan setiap tag `v*` menerbitkan release berisi APK saja.

## Structure

```
app/src/main/java/com/zrec/recorder/
  MainActivity.kt      layar utama dan pengaturan
  RecorderService.kt   foreground service, notifikasi, guncangan, rotasi
  ScreenRecorder.kt    MediaCodec + MediaMuxer, audio, jeda
  OverlayService.kt    floating control
  Shot.kt              screenshot sekali jalan
  Caps.kt              pembacaan kemampuan encoder
  Config.kt            preferensi
```

## Known limits

- Floating control tidak bisa terlihat di layar tapi hilang dari rekaman. Android tidak menyediakan itu. Karena itu bola disembunyikan selama merekam, dan kontrol pindah ke notifikasi.
- Audio internal bisa kosong di aplikasi yang melarang perekaman suaranya. Ini keputusan aplikasi tersebut, bukan bug Zrec.
- Kalau "Hide while recording" dimatikan, bola ikut terekam.
- Rotasi di tengah rekaman menghasilkan dua file, bukan satu file yang berputar.

## License

[MIT](LICENSE). Pakai, ubah, dan bagikan bebas.
