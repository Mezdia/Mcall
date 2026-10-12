# Mcall

**Automatic call recorder with an encrypted vault** for Samsung Galaxy phones (One UI).

Mcall records every call by itself, encrypts each recording, and keeps a player inside the app so the
audio can only be heard through Mcall.

<sub>ضبط خودکار تماس‌ها با گاوصندوق رمزنگاری‌شده — برای گوشی‌های سامسونگ (One UI)</sub>

---

## What it does

| | |
|---|---|
| **Auto recording** | Starts on call pickup, stops on hang-up. A foreground service survives the app being closed, and restarts after reboot. |
| **Encrypted vault** | Each recording is written to `CVAULT2`: PBKDF2-HMAC-SHA256 (600,000 iterations) → AES-128-CBC + HMAC-SHA256 (Fernet layout). The file is **not** a playable MP4, so no other app can open it. |
| **In-app player** | Decrypts into app-private cache, plays there, then zero-fills and deletes the copy. Full transport: play/pause, scrub, ±10s, previous/next, 1x/1.25x/1.5x/2x, plus background playback with a `MediaSession` (lock-screen and notification controls). |
| **Password eras** | Changing the password affects only future recordings. Older files keep the password they were made with and still play inside the app, because a short history of previous passwords is retained. |
| **Encryption switch** | Recording can be left unencrypted; that also applies only to new files. |
| **Languages** | Persian and English. Persian is used when the system language is Persian, English otherwise — with full RTL. Vazirmatn is bundled. |

## Requirements

- Android 10 (API 29) or newer — built and tested on a Galaxy A36 (Android 16, One UI 8.5)
- JDK 17, Android SDK platform 36 + build-tools 36.1.0, Kotlin compiler
- Permissions: microphone, call state + call log, notifications, "All files access" (for the vault folder),
  and unrestricted battery usage so recording survives idle.

## Building

There is deliberately **no Gradle** in this project. `tools/build.ps1` runs the toolchain directly:

```powershell
cd callvault-recorder
.\tools\build.ps1              # build, then install on the connected phone
.\tools\build.ps1 -NoInstall   # build only -> out/mcall.apk
.\tools\build.ps1 -Serial <serial>
```

`aapt2 compile` → `aapt2 link` → `kotlinc` → `d8` → `zipalign` → `apksigner`.
A debug keystore is generated on first run if `tools/debug.keystore` is absent.

Kotlin dependencies are vendored in `libs/` (currently `kotlinx-coroutines`) because the build resolves
nothing from the network.

## Layout

```
src/com/mcall/app/
  crypto/   VaultCrypto (file format), PasswordStore (KeyStore-wrapped passwords)
  data/     Recording, RecordingRepository, AppSettings
  player/   PlaybackEngine (decrypt + MediaPlayer), PlaybackService (MediaSession)
  service/  CallRecordService (foreground recorder), RecordingController, BootReceiver
  ui/       MainActivity, SettingsActivity, PlayerActivity, widget/SettingRow
  util/     Format, Insets, OneUiDialog, RecordingLabels
res/        One UI 8.5 design tokens: values/colors.xml, values/dimens.xml, values/themes.xml
tools/      build.ps1, ic_launcher_source.png
```

`RecordingController` is the only way recording is turned on or off, so the main-screen button and the
settings switch can never disagree.

## Companion toolkit

Recordings are meant to be recoverable outside the phone. A Python CLI for decrypting, encrypting,
pulling over `adb` and on-device watching lives in a separate `calls-decrypt` repository — same
`CVAULT1`/`CVAULT2` format, verified byte-compatible with this app.

```bash
python -m mcall.cli decrypt file.m4a.enc --passwords "new-pass,old-pass"
```

## Notes

- The password is never stored in plaintext: it is wrapped by a device-bound `AndroidKeyStore` AES-GCM
  key, so vault files do not decrypt on another device.
- Losing the password of a given era means the files of that era are unrecoverable, inside or outside the app.
- Recording calls is regulated differently by jurisdiction. Check your local law before using it.

## Credits

Coded by [Mezdia](https://github.com/Mezdia) · UI follows Samsung's One UI 8.5 (SESL8) metrics ·
Persian typography by [Vazirmatn](https://github.com/rastikerdar/vazirmatn) (SIL OFL).
