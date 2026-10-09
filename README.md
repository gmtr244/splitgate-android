# SplitGate

**English** · [Türkçe](README.tr.md)

A DPI bypass app for Android that does not need root.
It sets up a local "fake VPN" with `VpnService`. Your traffic is not routed through any other server; it is
processed on the phone and goes out normally. Only the DNS lookups go to the DoH provider you pick.

## What does it do?

| Feature | Description |
|---|---|
| **SNI splitting** | Splits the TLS ClientHello into TCP pieces around the host name (`discord.com` → `disco` / `rd.com`). DPI cannot see the name in a single packet. |
| **TLS + TCP splitting** | Optional: cuts the ClientHello in the middle of the site name into two separate TLS records, sent as two TCP pieces. If the ClientHello is incomplete, it is still TCP-split at the same point. |
| **Disorder (no root)** | The first piece of the ClientHello is sent with TTL 1 (`setsockopt` on the app's own socket), so it dies at the first router before the DPI sees it; the kernel resends it and the server gets the pieces out of order. For DPI that puts the pieces back together. |
| **Method per network** | Wi-Fi and each mobile operator get their own method. When you switch networks, open connections are reset so apps reconnect at once over the new network, and the saved method is used. |
| **Real check** | Every time you connect or switch networks, the chosen method is really tried on the test sites. The app says *Working* only if that succeeds; otherwise it tries the other methods, and if none works it says *Not working on this network* with the likely reason (DNS, IP block, interception, DPI). |
| **HTTP Host splitting** | Splits the Host value in port 80 requests. |
| **DNS-over-HTTPS** | All DNS queries sent over UDP (including hardcoded 8.8.8.8) are resolved over DoH through Quad9 (default), Cloudflare or Google. Gets around DNS poisoning. AAAA queries return empty (forces IPv4). |
| **QUIC block** | UDP 443 is dropped; Chrome/Discord fall back to TCP+TLS, where splitting can be applied. |
| **Other UDP** | (e.g. Discord voice) is forwarded as is. |

The app itself is excluded from the VPN (`addDisallowedApplication`), so there is no loop.

## Interface and notification

- Dark theme, a big power button, live **downloaded / uploaded** amounts (B, KB, MB, GB) and current
  speed, counters for active connections / split / DNS, connection time.
- **Presets** (same position tokens as the Linux version, splitgate-linux): DNS only · Light (`midsld`) ·
  SNI start (`1`, `sni`, default) · Aggressive · **OOB byte** (1 urgent byte after the first piece) · TLS + TCP split ·
  **Disorder** (`1`) · **Disorder (site name)** (`midsld`) · **TLS + disorder**.
  A QUIC switch. Changing the method or the QUIC switch applies at once, no restart needed.
- **Switch to a working method by itself** (on by default): the method you choose, or the one the test recommends,
  is saved for the current network (Wi-Fi, or mobile per operator). If the check finds that the method does not open
  the test sites, the other methods are tried and the one that works is used and saved. A method you picked by hand
  is never changed. The Method card lists which method is saved for which network; *Forget saved networks* clears
  them.
- **Report:** *Send report* opens your e-mail app with a report addressed to the maintainer (*Share* sends it with any
  other app). It contains the app and Android version, the phone model, the network and operator, the settings, the
  last test results and the app's own status lines. It does **not** contain the sites you visit: connection and DNS
  lines are left out. Nothing is sent until you press send in your e-mail app.
- **DNS provider:** Quad9 / Cloudflare / Google; the selected one becomes primary, the others are fallbacks.
- **Test:** *Try methods* tries every preset on this network with a real TLS handshake, verifies the
  certificate, recommends the best one and selects it with *Apply recommended*. The sites it tries are in a box
  above the button; change them to sites that are blocked for you (up to 8, e.g. `example.org, news.example.net`).
  The defaults (discord.com, roblox.com, x.com, wattpad.com) are only examples.
- A collapsible **Log** section (closed by default): "Copy" and "Clear".
- **Notification:** On first start (Android 13+) the notification permission is requested. While connected
  a quiet, persistent notification shows: time, total ↓/↑ and speed, and a "Stop" button. While
  disconnected there is **no notification at all**. If the permission is not granted the VPN still works,
  only the notification is not shown.
- App icon: a shield split in the middle (adaptive icon).
- **Language:** English by default. The app asks for your language on first launch; the **🌐** button at the
  top right switches between English and Turkish at any time. Engine and service logs are always in
  English.

## Privacy / encryption

- This app does **not encrypt traffic itself**; it does not tunnel to a VPN server.
- DNS queries **are encrypted** (DoH). HTTPS sites already use their own TLS encryption.
- Plain HTTP (port 80) is unencrypted; your carrier can still see which IP addresses you connect to.
- The log is kept in memory only, never written to disk; it is erased when the app closes.

## Installing

Download the latest **`SplitGate-<version>.apk`** from the repository's **Releases** page and open it on your
phone ("allow installing from unknown sources" when asked). Android 7.0 (API 24) or newer is required.
The app is not on the Play Store, so Android or Play Protect may ask you to confirm the install; that is normal.

To check that the file was not tampered with, compare its signing certificate with the fingerprint published in
the release notes:

```bash
apksigner verify --print-certs SplitGate-<version>.apk
```

## Building from source

You do not need this to use the app: just download the APK from Releases (see above). This section is for
developers who want to build their own copy.

Needs JDK 17 or newer (CI uses JDK 25) and the Android SDK (platform 36, build-tools 36). The build uses Gradle 9.8.1 and Android Gradle Plugin 9.4.1; the wrapper downloads Gradle by itself.

```bash
./gradlew assembleDebug      # app/build/outputs/apk/debug/app-debug.apk (debug key, for development)
./gradlew assembleRelease    # app/build/outputs/apk/release/ (R8-shrunk; signed only if keystore.properties exists)
```

`assembleRelease` makes an unsigned, R8-shrunk APK, and Android will not install an unsigned APK. You can sign it
with your own key, but that is not the maintainer's key: your build has a different signature, so it cannot update
(or be updated by) the official releases. If a git-ignored `keystore.properties` file (`storeFile`,
`storePassword`, `keyAlias`, `keyPassword`) exists in the project root, Gradle signs the release build with it.
Never commit a keystore or that file. In Android Studio: open the project folder, let the Gradle sync finish, then
**Build → Build APK(s)** or press ▶ (Run) with the phone connected over USB.

The GitHub Actions workflow runs the engine self-test and builds both variants on every push; the debug APK is
attached to the run as the `SplitGate-debug-apk` artifact (for testing only - releases come from a locally signed build).

## Testing

1. Open the app → press the power button → approve the VPN permission.
2. In the **Test** card press *Try methods*; apply the recommended preset (or pick one by hand). Open
   Discord or a blocked site.
3. If it does not work, change the DNS provider and repeat the test, and try turning the QUIC block on/off.
4. The log at the bottom shows lines like these for every connection:
   - `DNS discord.com (A) ok 41ms`
   - `discord.com:443  TLS OOB split @1,158`
5. When you switch between Wi-Fi and mobile data, the log shows
   `Network changed (Wi-Fi -> Mobile (…)): reset … connections` and, if needed, the automatic test result.
6. If it still fails, use **Copy** to take the log and the Test result and attach them to a bug report.

## Testing the engine on a computer (no Android needed)

Needs JDK 17 or newer.

```bash
./tools/run-selftest.sh
```

A fake "phone" TCP/UDP client writes packets to the TUN; the engine goes out to real loopback sockets.
Verified: checksums, TCP handshake/FIN, retransmission on packet loss, ClientHello splitting, DNS, UDP
forwarding, QUIC dropping, the strategy probe against a real TLS server, and the DoH client (SNI, Host
header, keep-alive, chunked replies, fallback).

## Known limits

- **Only IPv4** is tunneled. AAAA queries return empty, so apps fall back to IPv4.
- DNS over TCP and DNS-over-TLS (port 853) are not intercepted; they go out directly.
- Without root, **fake packets** (a decoy ClientHello sent before the real one) are not possible. Out-of-order
  sending (*Disorder*) works without root. On networks where none of the methods is enough, the app says so instead
  of claiming to work.
- If the DPI does not only look at the SNI (IP blocking, ECH, etc.) this method does not help.
- Whether the pieces leave as separate packets depends on the operating system; a delay of 0-3 ms (depending on the
  preset) is placed between the pieces.
- If the *Private DNS* setting on the phone is set to a host name in strict mode, DNS queries may bypass this
  app; leave it "Off" or "Automatic".
- Android runs only one VPN at a time.

## Project structure

```
app/src/main/java/com/projectgamers/splitgate/
  MainActivity.java        UI (power button, stats, method, test, language, log)
  NetworkId.java           which network we are on; method saved per network
  AndroidTtl.java          TTL of our own socket (for Disorder)
  Report.java              problem report (e-mail / share)
  LocaleHelper.java        in-app language selection
  PowerButton.java         power button (custom drawing)
  Fmt.java                 B/KB/MB/GB formatting
  BypassVpnService.java    VpnService + TUN I/O + status notification + network changes, auto test
  LogBuffer.java           on-screen log
  engine/                  pure Java engine (no Android dependency)
    TunEngine.java         packet routing, DNS, UDP
    TcpSession.java        TCP termination + real socket + ClientHello splitting
    Strategy.java          split plan (position tokens, OOB, disorder)
    Sender.java            sends the planned pieces (TTL for Disorder)
    Presets.java           ready-made settings
    Probe.java             strategy probe (real TLS handshake)
    TlsSni.java            SNI parser
    DohResolver.java       DNS-over-HTTPS
app/src/main/res/values*/  translations (strings.xml: English, values-tr: Turkish)
tools/selftest/            JVM test
```

## Adding a language

Create `app/src/main/res/values-<code>/strings.xml` with the same keys as `values/strings.xml`, and add the
language to the `CODES` and `NAMES` arrays in `LocaleHelper.java`.

## Forks and community editions

Forks and community editions are welcome under the GPL: you may change, redistribute or sell them as long as you
share the source under the same license and keep the copyright notices. Please use a different name, your own
signing key and your own applicationId so users do not confuse them with the official releases.

## License

Copyright © 2026 Project Gamers. [GNU GPL v3 or later](LICENSE) (GPL-3.0-or-later). Anyone who modifies and distributes the program must also
share the modified source code under the same license.
