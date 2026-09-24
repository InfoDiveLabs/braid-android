# Development

How the code is laid out, how to build and release it, and the rules worth knowing
before changing anything.

## Building

```console
$ ./gradlew :relay:test          # 99 tests
$ ./gradlew :app:assembleDebug   # debug APK in app/build/outputs/apk/debug
$ ./gradlew :app:dist            # signed release APK in dist/
```

JDK 17 and the Android SDK with platform 35. Point `local.properties` at the SDK
(`sdk.dir=...`), or set `ANDROID_HOME`.

## Layout

Two Gradle modules. The split is the point: everything that can be wrong about bytes,
credentials or the protocol lives where it can be tested without a phone.

**`relay/`**: plain JVM Kotlin with no Android dependencies.

| File | What it is |
|---|---|
| `RelayServer.kt` | The proxy on port 8710: request heads, `CONNECT` tunnels, splicing, per-lane metering |
| `RequestHead.kt` | Request-line and target parsing: absolute-form, `CONNECT`, origin-form |
| `Router.kt` | `Proxy-Authorization` parsing, and the `Via` / `Refused` / `Unavailable` decision |
| `LaneTable.kt` | Which lane is on, which network it is bound to, and its egress |
| `ControlPlane.kt` | `/braid/hello`, `/braid/status`, `/braid/pair` |
| `Pairings.kt` | One key per desktop, stored as SHA-256 |
| `PairingCode.kt`, `LocalScope.kt`, `Registrar.kt` | Pairing by QR: parse the code, check it is local, register |
| `Egress.kt` | Public-address lookups, and DNS-free address validation |
| `Json.kt` | The small JSON encoder and parser the control plane needs |
| `Traffic.kt`, `Upstream.kt` | Byte counting, and how a lane opens a socket |

**`app/`**: the Android app around it.

| File | What it is |
|---|---|
| `NetworkLanes.kt` | Binds each lane to an Android `Network`: requests mobile data, listens for Wi-Fi, resolves egress through the lane |
| `AndroidPhone.kt` | Device name, `device_id`, pairings and routing, as the relay's `Phone` and `Router` |
| `RelayHost.kt` | Starts and stops relay, lanes and mDNS together; limits, notes, history |
| `RelayService.kt` | The `connectedDevice` foreground service, wake lock and notifications |
| `MainActivity.kt`, `ThroughputChart.kt` | The screen, built in code; the live chart |
| `AddComputer.kt`, `PairingPrompt.kt` | Pairing by code, and the pairing dialog |
| `Advertiser.kt` | `_braid-relay._tcp` over `NsdManager` |

## The rule everything hangs on

> A request leaves by the network it names, or not at all.

`Upstream` owns name resolution as well as the socket, so a lane's DNS and its
connection both go through its `Network`. Resolving through the default network and
connecting through another would measure, and leak, the wrong path.

When a lane cannot serve, `LaneTable.route` answers `Route.Unavailable` (`503`). Nothing
in the code substitutes another lane, and nothing should. A cellular lane served over
Wi-Fi is the one failure the desktop cannot detect.

## Other rules that are not obvious

- **The bytes are not ours.** Request heads are rewritten only as a proxy must:
  absolute-form to origin-form, hop-by-hop headers removed. Bodies and tunnels are
  copied without being read. Never add `Accept-Encoding`, decompress, cache or retry.
- **One request per client connection.** Plain-HTTP requests go upstream with
  `Connection: close`. After the first head the connection is spliced, so a second
  keep-alive request would reach the origin unread, carrying its `Proxy-Authorization`
  past a check that had already happened.
- **Never pass untrusted text to `InetAddress`.** On Android, text that does not parse
  as a numeric address goes to DNS. `Egress` validates by hand, so a captive portal's
  answer cannot make the phone resolve a name of its choosing.
- **The egress hostnames are part of the protocol.** The desktop asks `api.ipify.org`
  and `api6.ipify.org` too, and compares the answers. Change one side only and duplicate
  detection breaks without an error.
- **Stale egress is discarded, not stored.** Every attach bumps a generation, and a
  lookup that finishes after its network was replaced is dropped.
- **Report the stable IPv6.** A socket's IPv6 source is a temporary privacy address
  that rotates within a day, so registration reports the non-temporary address on the
  same /64.
- **A pairing code must point somewhere local.** `LocalScope` accepts only addresses
  inside a non-cellular interface's subnet, and `PairingCode` rejects link-local,
  loopback and the `192.0.0.0/29` 464XLAT range before that check runs.

## Protocol

The desktop defines the wire format in `crates/dl-net/src/control.rs` in the
[desktop repository](https://github.com/InfoDiveLabs/braid). Where this code and that
disagree, the desktop wins.

| Request | Answer |
|---|---|
| `GET /braid/hello` | `{"name", "device_id", "version": 1}`, never authenticated |
| `GET /braid/status` with `X-Braid-Key` | `{"lanes": [{"id", "kind", "label", "egress", "egress6", "note"}]}`, or `401` |
| `POST /braid/pair` `{"desktop"}` | `{"key"}` after the person taps Allow, `403` otherwise |
| Proxy request, no known key | `407` |
| Proxy request, lane not on offer | `503` |
| QR `braid://pair?v=1&h&p&t&n` | the phone POSTs `{"token", "device_id", "name", "address", "key"}` to `/braid/register` |

## Testing

`./gradlew :relay:test` runs the JVM suite. It covers the proxy against a loopback
origin (byte-identical bodies, ranges, `CONNECT`), header handling, authorisation on
both request forms, pairing storage, JSON, lane routing, egress validation, QR parsing
against a code a real desktop produced, the local-network check, and registration
against a stand-in desktop.

What needs a phone was checked on one, over `adb`, with the Mac and the phone on the
same network:

```console
$ curl -x "http://cell:$KEY@[phone]:8710" https://api.ipify.org   # leaves from cell's egress
$ curl -x "http://cell:$KEY@[phone]:8710" -r 100-199 <url>         # 206, exactly 100 bytes
$ adb shell svc data disable                                       # cell becomes 503, never Wi-Fi
$ adb shell dumpsys deviceidle force-idle                          # still serving in Doze
```

## Releasing

Release builds are shrunk with R8 and signed with a key that never enters git. The build
reads the key from `.env` at the repository root, or from the environment:

| Variable | Meaning |
|---|---|
| `BRAID_KEYSTORE` | path to the keystore, relative to the repository |
| `BRAID_KEYSTORE_PASSWORD` | keystore password |
| `BRAID_KEY_ALIAS` | key alias |
| `BRAID_KEY_PASSWORD` | key password |
| `BRAID_KEYSTORE_BASE64` | the keystore as base64, for a CI secret |

`.env` and `keystore/` are git-ignored. Keep an offline copy of both: an app signed with
a different key cannot update an installed one, so losing the key ends the update path.

A new key, if one is ever needed:

```console
$ keytool -genkeypair -storetype PKCS12 -keystore keystore/braid-release.jks \
    -alias braid -keyalg RSA -keysize 4096 -validity 10000
```

Pushing a `v*` tag runs `.github/workflows/release.yml`. It needs the four `BRAID_*`
values above, other than `BRAID_KEYSTORE`, as repository secrets. The workflow tests,
builds, signs, and attaches the APK to a GitHub release.

### Architectures

There is one APK. The app has no native code, `aapt2 dump badging` reports no
`native-code` entry, and the same bytecode runs on `arm64-v8a`, `armeabi-v7a`, `x86_64`
and `x86`. Per-ABI splits would produce identical files. Revisit this only if a native
library is ever added.

## What is not verified

- **Automated end-to-end coverage.** A real download through Braid, with pairing by
  the desktop's own QR code, was run by hand on 2026-09-24 and reported working. The
  phone side of that run was observed; nothing repeats it automatically. The JVM suite
  and the `adb` checks above are what guard regressions.
- **One phone, one carrier.** A Pixel 7 Pro on Android 17, on Jio, which is IPv6-only
  with NAT64. Other manufacturers' builds, IPv4 carriers, dual-SIM phones and Android
  versions before 17 have not been tried.
- **Google Play.** Whether Play accepts an app that shares mobile data, and the
  justification its `connectedDevice` service will need, are not settled. The APK is
  distributed directly for now.
