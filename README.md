# QR Utility — Android

A small, offline, instrument-styled QR **scanner + generator**. Black / blue / white,
no network permission, no accounts, no tracking. Reads codes with the camera or from a
saved image, generates codes from any text/URL, and keeps a local history log.

---

## Why this is a project and not a prebuilt .apk

An `.apk` has to be **compiled** with the Android SDK. That couldn't happen in the
environment this was authored in (no SDK, no network), so instead you get the complete,
buildable project plus a one-click cloud build. Pick whichever route below fits you.

---

## Route A — GitHub Actions (no local tooling) ← easiest

1. Create a new GitHub repo and push this folder to it (or drag-drop upload).
2. Open the **Actions** tab → pick **Build APK** → **Run workflow**.
3. When it goes green (~3–5 min), open the run and download the
   **`qr-utility-debug-apk`** artifact. Inside is `app-debug.apk`.
4. Copy it to your phone and install (enable "install unknown apps" for your file
   manager / browser the first time).

The workflow is at `.github/workflows/build-apk.yml`. It uses system Gradle 8.7 + JDK 17,
so it does **not** need a Gradle wrapper committed to the repo.

## Route B — Android Studio (GUI)

1. **File → Open** and select this folder.
2. Let it sync (it provisions the Gradle wrapper + downloads dependencies on first open).
3. **Build → Build App Bundle(s) / APK(s) → Build APK(s)**, or just press **Run** with a
   phone plugged in.
4. Output lands at `app/build/outputs/apk/debug/app-debug.apk`.

Requires a JDK **17** toolchain (Android Studio bundles one).

## Route C — Command line

If you have the Android SDK + Gradle 8.7 + JDK 17 already:

```bash
gradle assembleDebug
# -> app/build/outputs/apk/debug/app-debug.apk
```

(There's intentionally no `gradlew` wrapper jar committed — use system `gradle`, or let
Android Studio generate the wrapper on first open.)

---

## Build config

| Setting        | Value                                             |
|----------------|---------------------------------------------------|
| Package / appId| `com.qrutility`                                   |
| minSdk         | 26 (Android 8.0)                                  |
| compile/target | 34                                                |
| AGP / Kotlin   | 8.5.2 / 1.9.24                                     |
| Gradle / JDK   | 8.7 / 17                                           |
| Scan engine    | `com.journeyapps:zxing-android-embedded:4.3.0`    |
| Encode/decode  | `com.google.zxing:core:3.5.3`                      |
| UI             | Classic XML views + ViewBinding (no Compose)      |

## Notes

- **Camera** needs a real device (or an emulator with a virtual/webcam camera) and a
  runtime permission prompt on first use. That's exactly why the HTML version couldn't
  open the camera — a sandboxed web preview can't grant it.
- **No INTERNET permission.** Fully offline; safe for air-gapped use.
- **IMAGE** button decodes a QR from any picture in your gallery — a fallback when the
  live camera isn't available.
- **Saved PNGs** go to `Pictures/QRUtility` (via MediaStore on Android 10+, or a direct
  file write with a storage prompt on 8–9).
- **History** is stored locally in SharedPreferences (last 200 entries), never uploaded.
