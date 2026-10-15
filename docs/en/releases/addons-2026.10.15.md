# muxy addons 2026.10.15

Release builds of the six official Termux addon apps, signed with the same private certificate as muxy. Use them with muxy 0.119.4. Package names and normal Android permission checks are unchanged.

| App | Version | Minimum Android |
| --- | --- | --- |
| Termux:API | 0.53.0 | 7.0 |
| Termux:Styling | 0.32.1 | 5.0 |
| Termux:Boot | 0.8.1 | 5.0 |
| Termux:Float | 0.17.0 | 7.0 |
| Termux:Tasker | 0.9.0 | 5.0 |
| Termux:Widget | 0.15.0 | 7.0 |

## Install

Install the required addon APKs alongside muxy. Termux:API commands also need `pkg install termux-api` inside muxy. The Android requirements above are upstream requirements, not muxy's minimum SDK.

Original F-Droid, GitHub, and Play APKs signed by another key cannot share muxy's UID. If a conflicting addon is installed, back up its settings before uninstalling only that addon and installing the muxy-signed version. Do not uninstall muxy or disable signature checks.

Signer SHA-256:

```text
756768af514d3fa0d1c214d54dabd7b0cd088a1723a22f520b90e8e8545262ce
```

Verify all downloads with `sha256sum -c ADDON-SHA256SUMS`. `ADDONS.json` records exact upstream commits and hashes. The corresponding-source archive includes the patched source, build scripts, instructions, and licenses. These are source-built release APKs, not downloaded debug APKs re-signed for distribution.

## Verification

- All six APKs pass checksum, signer, package, minimum SDK, shared UID, non-debuggable manifest, and native 16 KiB alignment checks.
- All six install alongside signed muxy on API 35 and use the same UID.
- API battery-status socket output, Styling color application, Float service startup, and Boot/Tasker/Widget launcher startup pass on the API 35 emulator.
- Styling, Boot, and Tasker also install, share muxy's UID, and launch on API 21.

No claim is made that every API hardware function, reboot job, automation-host integration, or launcher widget behavior has been tested. Float keeps its own upstream terminal core and does not include muxy's libghostty-vt or IME changes.

Build and installation details: [Termux addons for muxy](../addons.md).
