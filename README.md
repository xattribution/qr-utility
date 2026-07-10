# QR Utility — Android

A small, instrument-styled QR **scanner + generator**. Black / blue / white,
no accounts, no tracking. Reads codes with the camera or from a saved image,
generates codes from any text/URL, keeps a local history log, and can optionally
read/write a database (on-device, LAN, or remote) to batch-generate codes and
record scans.

The camera opens automatically on launch, and the scan reticle shows activity by
cycling its corner brackets grey → blue → green.

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

## Self-hosted download site (Docker)

Want a little web page to hand the APK to your own devices? There's a one-container
setup under [`web/`](web/README.md) that **compiles the APK from source on your host**
(inside the Docker build) and serves it with a short description page.

```bash
# needs only Docker + the Compose plugin
./update.sh          # clones/updates source, compiles the APK, starts the site on :49730
```

Re-run `./update.sh` anytime to pull the latest source, recompile, and redeploy.
See [`web/README.md`](web/README.md) for details and configuration.

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
- **IMAGE** button decodes a QR from any picture in your gallery — a fallback when the
  live camera isn't available.
- **Saved PNGs** go to `Pictures/QRUtility` (via MediaStore on Android 10+, or a direct
  file write with a storage prompt on 8–9).
- **History** is stored locally in SharedPreferences (last 200 entries), never uploaded.

## Database (DATA tab)

Optional. Three source modes, chosen per connection:

- **On-device (SQLite)** — fully offline. Import a CSV into a local `records`
  table, batch-generate a QR PNG per row into `Pictures/QRUtility`, save scans to
  a local `scans` table, and export them to CSV in `Download/QRUtility`.
- **LAN / remote database** — connect directly to **PostgreSQL** or
  **MySQL/MariaDB**. A `SELECT` supplies the rows to generate from; an `INSERT`
  records scans. Test the connection before saving.
- **Autodiscover** — "Scan local network" probes your `/24` for open Postgres
  (5432) and MySQL (3306) ports and pre-fills a connection.

Notes:
- The app now declares the **`INTERNET`** permission (plus network-state) for the
  LAN/remote modes. On-device mode uses no network. This is a change from the
  earlier air-gapped design.
- Connection **credentials are stored encrypted** (EncryptedSharedPreferences,
  Android Keystore-backed). All database I/O runs off the main thread.
- Direct phone→database access means the DB must be reachable from the phone and
  its credentials live on the device — fine for a trusted LAN, worth weighing for
  anything exposed to the internet.
