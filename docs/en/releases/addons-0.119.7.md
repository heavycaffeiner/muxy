# muxy addons 0.119.7

Coordinated source-built releases for muxy 0.119.7. All six organization forks are updated and use the existing private signer. Package names, shared UID, permissions, and minimum Android versions are unchanged.

## Touch behavior

Main muxy and Float now use the same gestures:

- One confirmed touchscreen tap opens the keyboard without a terminal click.
- Two taps send one TUI mouse press/release pair at the second tap's position when mouse reporting is active. They do not request the keyboard.
- The first tap no longer sends a mouse click. Cancelled, moved, held, or multitouch second contacts do not click.
- Float's outer window restores focus on touch down without opening the keyboard on every release. Its confirmed single-tap callback requests the keyboard.
- Physical mouse, touchpad scrolling, long press, pinch, explicit keyboard controls, and existing IME fixes retain their paths.

Single-tap keyboard access waits for Android's double-tap decision. Double taps do not hide an already open keyboard. Terminal protocols do not expose TUI text-field focus.

API, Tasker, and Widget pin the same shared library revision. Styling and Boot do not embed a terminal; their app behavior is unchanged, and their version codes identify this coordinated release.

| App | Version | Minimum Android |
| --- | --- | --- |
| Termux:API | 0.53.0+muxy.4 | 7.0 |
| Termux:Styling | 0.32.1+muxy.4 | 5.0 |
| Termux:Boot | 0.8.1+muxy.4 | 5.0 |
| Termux:Float | 0.17.0+muxy.4 | 7.0 |
| Termux:Tasker | 0.9.0+muxy.4 | 5.0 |
| Termux:Widget | 0.15.0+muxy.4 | 7.0 |

## Upgrade and downloads

Install over earlier muxy-signed builds without uninstalling. Keep the main app's existing bootstrap variant. Original F-Droid, upstream GitHub, and Play APKs signed by another certificate cannot join muxy's UID.

Signer SHA-256:

```text
756768af514d3fa0d1c214d54dabd7b0cd088a1723a22f520b90e8e8545262ce
```

Each fork publishes its APK. The combined release includes all six APKs, `ADDONS.json`, `ADDON-SHA256SUMS`, GPLv3 text, and corresponding sources for the six forks and pinned muxy libraries. Verify downloads with `sha256sum -c ADDON-SHA256SUMS`.

## Verification

- Clean builds use the pinned organization fork commits and muxy `7a03b9938e9d9eb4a1456045d60147dd54848d02`.
- All six APKs pass checksum, certificate, package, SDK, shared UID, and 16 KiB native alignment checks. Float packages the native terminal for all four ABIs.
- All six upgrade alongside signed muxy on API 35. Styling, Boot, and Tasker also upgrade on API 21.
- Float's seven-scenario overlay/PTY suite completes without failures on API 35, with optional live input skipped. Its eight-case gesture matrix asserts keyboard visibility and exact mouse bytes for ordinary/alternate screens and modes 0/1000/1002/1003.
- The signed main app passes the same eight-case keyboard gesture matrix, shell execution, rotation/session preservation, and extra-key editing on API 35. Signed shell/rotation and extra-key editing also pass on API 21.
- Shared host checks pass 159 emulator, 51 View, and eight main-app tests in each build type. The Android connection/View/pointer suites complete 60 scenarios on API 21/34/35 without failures, with unsupported/manual cases skipped. API 21's first run aborts during JNI subprocess startup; the complete rerun passes. This is not reported as a fixed JNI issue.

Physical devices, other live IMEs, real reboot scheduling, automation-host execution, and launcher widget behavior remain outside this release's verification matrix.
