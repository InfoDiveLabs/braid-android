<p align="center">
  <img src="docs/brand/icon.png" alt="Braid" width="112">
</p>

<h1 align="center">Braid for Android</h1>

<p align="center">
  <b>Lend your phone's mobile data to Braid on your computer.</b><br>
  Your laptop downloads over its own Wi-Fi and your phone's 5G at the same time, and the file comes out exactly right.
</p>

<p align="center">
  <a href="#install">Install</a> &middot;
  <a href="#how-it-works">How it works</a> &middot;
  <a href="#privacy-and-safety">Privacy and safety</a> &middot;
  <a href="#build-from-source">Build from source</a>
</p>

<p align="center">
  <img alt="Android 8.0 and later" src="https://img.shields.io/badge/Android-8.0%2B-14110E?style=flat-square&logo=android&logoColor=white">
  <img alt="Kotlin" src="https://img.shields.io/badge/Kotlin-D07A40?style=flat-square&logo=kotlin&logoColor=white">
  <img alt="Licence" src="https://img.shields.io/badge/PolyForm%20Noncommercial-3FA292?style=flat-square">
  <img alt="APK size" src="https://img.shields.io/badge/APK-0.5%20MB-14110E?style=flat-square">
</p>

---

<p align="center">
  <img src="docs/screenshots/sharing-light.png" alt="Sharing on, light theme" width="300">
  &nbsp;&nbsp;
  <img src="docs/screenshots/off-dark.png" alt="Sharing off, dark theme" width="300">
</p>

## What it does

[Braid](https://github.com/InfoDiveLabs/braid) is a desktop download manager that splits one file across every network path a computer has. A laptop with one Wi-Fi card has one path. The phone next to it has another: its mobile data.

This app turns the phone into that second path. Braid on the computer sends part of each download through the phone, the phone fetches it over mobile data, and Braid stitches the pieces back together and checks every byte.

- **One lane per network.** Mobile data and Wi-Fi are offered separately, each bound to its own network. Braid weighs them by measured speed and moves work away from any that stalls.
- **Never pretends.** A lane asked to use mobile data uses mobile data or refuses. It never quietly falls back to Wi-Fi.
- **Never touches your data.** Bytes pass through unchanged. No caching, no compression, no retries. Braid's per-chunk checks would catch anything else.
- **You decide what it costs.** Switch each network on or off, set a mobile data limit, and see the live speed and total for the session.

## Install

Download the latest `braid-android-<version>.apk` from [Releases](../../releases) and open it on your phone. Android asks you to allow installs from your browser or file manager the first time.

**There is one APK, and it is the right one for every phone.** The app contains no native code, so the same file runs on every processor type Android supports:

| Your device | Architecture | Which file |
|---|---|---|
| Almost every phone from 2017 onwards (Pixel, Samsung Galaxy, OnePlus, Xiaomi, and others) | `arm64-v8a` | `braid-android-<version>.apk` |
| Older or entry-level 32-bit phones | `armeabi-v7a` | the same file |
| Chromebooks and Intel-based tablets | `x86_64` | the same file |
| The Android emulator on a PC or Mac | `x86_64` / `x86` | the same file |

You need Android 8.0 or later. The in-app code scanner uses Google Play services. On a phone without them, point the camera app at the pairing code instead; Braid opens from there.

Braid on the computer comes from the [desktop repository](https://github.com/InfoDiveLabs/braid).

## Getting started

1. Open the app and tap **Start sharing**.
2. Switch on **Mobile data** (and **Wi-Fi** if you want it offered too).
3. On your computer, open Braid and click **Add phone** at the bottom of the interfaces list.
4. On the phone, tap **Add to a computer by code** and scan the code on the computer's screen. Check the name and address, then tap **Add**.

If the computer can't show a code, press **Scan** in its Add phone sheet instead. It finds the phone on the network, and the phone asks you to allow it. If Scan can't find it either, copy an address from the bottom of the app and add the phone by address.

## How it works

```
  Computer (Braid)                     Phone (this app)                     Internet
  ────────────────        Wi-Fi or     ─────────────────                    ────────
  splits a file into      USB cable    HTTP proxy on port 8710
  byte ranges  ─────────────────────▶  checks the pairing key   ── 5G ──▶   origin
  checks each chunk   ◀──────────────  sends each request out   ◀────────   server
                                       the network it names
```

- The phone runs an HTTP forward proxy on port 8710, plus a small control API: `/braid/hello`, `/braid/status` and `/braid/pair`.
- Every request carries `Proxy-Authorization: Basic base64("<lane>:<key>")`. The username names the network to use and the password is the key the phone issued when you paired.
- Each lane's sockets are bound to their Android `Network`, and name lookups go through it too, so neither a connection nor its DNS can leave by another route.
- Each lane reports its public IPv4 and IPv6 address. Braid uses them to spot a lane that is really the computer's own connection, such as your phone on the same Wi-Fi, and leaves it off.
- Sharing runs as a foreground service, so it keeps working with the screen off. It holds a wake lock only while a transfer is in progress.

The wire protocol is defined by the desktop in `crates/dl-net/src/control.rs`. Where the two disagree, the desktop wins.

## Privacy and safety

- **Nothing is served until you pair.** Requests without a valid key get `407`, including encrypted (`CONNECT`) ones, and nothing is fetched for them.
- **Pairing always asks you.** A request from the computer, or a scanned code, shows the computer's name and address and waits for you to tap.
- **Codes only work on your own network.** A code pointing at an address outside the networks this phone is on is refused, so a code on a web page or a poster can't give a stranger your data.
- **No secrets on the phone.** The phone stores only a SHA-256 hash of each key. Forgetting a computer cuts its open connections immediately.
- **No location permission, no accounts, no analytics.** The app asks only for network, notification and foreground-service permissions.
- **Backups are off,** so a restore can't carry your pairings onto someone else's phone.

## Build from source

Requirements: JDK 17 and the Android SDK (platform 35).

```sh
./gradlew :relay:test          # the proxy, pairing and protocol tests, on the JVM
./gradlew :app:assembleDebug   # a debug APK
./gradlew :app:dist            # the signed release APK, written to dist/
```

The proxy is plain Kotlin in `relay/`, with no Android dependencies, so its tests run on any JVM. `app/` is the Android app around it.

### Release signing

Release builds are signed with a key that never goes into git. The build reads it from a `.env` file at the root of the repository, or from environment variables of the same names in CI:

| Variable | Meaning |
|---|---|
| `BRAID_KEYSTORE` | path to the keystore, relative to the repository |
| `BRAID_KEYSTORE_PASSWORD` | keystore password |
| `BRAID_KEY_ALIAS` | key alias |
| `BRAID_KEY_PASSWORD` | key password |
| `BRAID_KEYSTORE_BASE64` | the keystore itself, base64, for CI secrets |

Keep an offline copy of the keystore and `.env`. Without them, no future release can update an installed copy.

Pushing a tag such as `v0.1.0` runs [the release workflow](.github/workflows/release.yml), which tests, builds and signs the APK and attaches it to a GitHub release.

## Licence

Source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE.md). You may use, study, modify and share it for any noncommercial purpose, including personal use, research, education, and use by charities and public bodies. Commercial use is not permitted. For a commercial licence, contact [InfoDive Labs](https://www.infodivelabs.com).

Braid is a product of InfoDive Labs Pvt Ltd. See [AUTHORS](AUTHORS).
