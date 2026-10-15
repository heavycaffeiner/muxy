# Termux addons for muxy

Use the muxy-signed addon APKs with the muxy-signed `com.termux` app. Original F-Droid, upstream GitHub, or Play APKs signed by another key cannot join muxy's shared UID. Android signature checks remain enabled.

Download the APKs from [muxy addon releases](https://github.com/heavycaffeiner/muxy/releases/tag/addons-2026.10.15).

## Included apps

| App | Version | Package | Minimum Android |
| --- | --- | --- | --- |
| Termux:API | 0.53.0 | `com.termux.api` | 7.0 (API 24) |
| Termux:Styling | 0.32.1 | `com.termux.styling` | 5.0 (API 21) |
| Termux:Boot | 0.8.1 | `com.termux.boot` | 5.0 (API 21) |
| Termux:Float | 0.17.0 | `com.termux.window` | 7.0 (API 24) |
| Termux:Tasker | 0.9.0 | `com.termux.tasker` | 5.0 (API 21) |
| Termux:Widget | 0.15.0 | `com.termux.widget` | 7.0 (API 24) |

These APKs retain official package names and integration protocols. They are release builds, not debug APKs re-signed after download. Both muxy bootstrap variants use the same signer. The listed Android requirements come from upstream and have not been lowered.

Termux:API shell commands also require `pkg install termux-api` inside muxy. Installing the Android app alone does not install the command-line package. Launch Boot once after installation. Float requires Android's overlay permission. Tasker requires a compatible automation host and its normal run-command permission. Widget requires launcher widget or shortcut setup.

## Installation and updates

Install muxy first, then the desired addon APKs. The certificate SHA-256 for both is:

```text
756768af514d3fa0d1c214d54dabd7b0cd088a1723a22f520b90e8e8545262ce
```

If an addon from another distributor is already installed, Android will reject an update with the muxy-signed APK. Back up that addon's settings before uninstalling only the conflicting addon. Keep muxy and its home directory installed. Removing muxy deletes its application data.

Do not mix APKs from different signing families. Do not disable package signature verification. All shared-UID apps can access the common Termux data; only install addons you trust.

Verify downloaded artifacts with:

```sh
sha256sum -c ADDON-SHA256SUMS
```

`ADDONS.json` records package names, upstream commits, SDK levels, APK hashes, and the corresponding-source archive hash.

## Source and build

The six addons use pinned upstream tags. Their `termux-shared`, `terminal-view`, and `terminal-emulator` dependency is the upstream revision `7bceab88e2272f961d1b94ef736f1a9e20173247` declared by those releases, not muxy's replacement core. Float therefore retains its own upstream terminal implementation. It does not acquire muxy's libghostty-vt or IME fixes.

JitPack no longer resolves the pinned shared library in the checked environment. The build uses that exact library source locally. Recorded patches add current AGP namespaces, build the native library with the declared NDK and 16 KiB alignment, connect project dependencies, and enable the Widget app's required desugaring. Addon runtime logic, permission checks, and package names are unchanged.

Requirements: Linux, Python 3.11 or newer, Git, Java 17 or 21, Android SDK platforms 34 and 35, build tools 36.0.0, and NDK 29.0.14206865. Use the checked-in Gradle wrappers. The main muxy build is separate.

```sh
python3 scripts/build-addons.py build/addons-new \
  --label addons-2026.10.15 \
  --keystore /path/to/muxy-release.p12 \
  --password-file /path/to/muxy-release.pass
python3 scripts/verify-addons.py build/addons-new/artifacts
```

The destination must not exist. Passwords are read from the specified file by `apksigner`, not passed as command-line values or stored in the repository. Private signing material is never included in the source archive.

Each distribution includes exact patched sources, build patches, instructions, and the GPLv3 text. Preserve upstream licenses and asset notices. Styling's font and color notices remain in its assets. The source archive accompanies the APKs under the upstream licenses; the muxy repository's top-level license does not replace them.

## Verification limits

API 35 emulator checks establish installation of all six APKs alongside signed muxy, matching shared UID, API battery-status socket output, Styling color-file application, Float service startup, and launcher startup for Boot, Tasker, and Widget. API 21 checks establish installation, matching UID, and launcher startup for Styling, Boot, and Tasker. They do not establish every API hardware permission, physical-device behavior, boot scheduling after an actual reboot, automation-host execution, or launcher widget behavior. Those need separate device checks.
