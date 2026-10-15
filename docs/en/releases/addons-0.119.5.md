# muxy addons 0.119.5

All six addon repositories now live under [muxy-terminal](https://github.com/muxy-terminal) as forks of the official upstream repositories. This distribution replaces the earlier same-signer-only builds with organization-source builds integrated with muxy's terminal libraries.

## Changes

- Float embeds muxy's libghostty-vt core, native Unicode layout, CJK input connection, preedit rendering, and key encoding instead of its old Java terminal stack.
- Float virtual Fn keys use the View's input route, preserving composition and extended keyboard protocol handling.
- Styling broadcasts update Float's native colors. Service teardown closes the native terminal and invalidates input connections.
- Removed legacy input workarounds from Float and clean their keys from its property files.
- API, Tasker, and Widget use pinned muxy shared libraries. Styling and Boot retain their existing standalone integration protocols.
- Preserve package names, shared UID, permissions, signing certificate, and upstream minimum Android versions. Version codes increase for in-place upgrades.

| App | Version | Minimum Android |
| --- | --- | --- |
| Termux:API | 0.53.0+muxy.2 | 7.0 |
| Termux:Styling | 0.32.1+muxy.2 | 5.0 |
| Termux:Boot | 0.8.1+muxy.2 | 5.0 |
| Termux:Float | 0.17.0+muxy.2 | 7.0 |
| Termux:Tasker | 0.9.0+muxy.2 | 5.0 |
| Termux:Widget | 0.15.0+muxy.2 | 7.0 |

Use with muxy 0.119.5. Install over previous muxy-signed addons without uninstalling. Differently signed original F-Droid, upstream GitHub, and Play APKs cannot share muxy's UID. Android signature verification is not bypassed.

Termux:API commands also require `pkg install termux-api` inside muxy. Float needs normal overlay permission; Boot must be launched once; Tasker and Widget need their normal host or launcher setup.

Signer SHA-256:

```text
756768af514d3fa0d1c214d54dabd7b0cd088a1723a22f520b90e8e8545262ce
```

## Verification

- Clean source builds use pinned organization commits and muxy revision `e0e1b398b5fef5f0edf288b2df49134ab6641de1`.
- All six signed APKs pass checksum, signer, package, SDK, shared UID, and native 16 KiB alignment checks. Float contains the muxy native core for all four ABIs.
- All six upgrade and share muxy's UID on API 35. Styling, Boot, and Tasker also upgrade on API 21.
- API battery-status socket output and Boot/Tasker/Widget launcher startup pass on API 35.
- Float's overlay/PTY instrumentation passes native CJK commits, repeated deletion, navigation followed by Hangul replacement, native color reset, and terminal disposal.
- Actual Gboard `18.4.1.985164140-release-arm64-v8a` on API 35 passes Float input for `안녕 한글`, eight touch Backspaces followed by `안녕`, and a left arrow between `안녕` and `한글`. Assertions compare actual PTY bytes.
- Shared terminal host tests pass 159 emulator and 49 View tests in debug and release. The main app's eight tests pass per build type. Android connection/View/pointer suites complete 48 scenarios on API 21, 34 and 35 without failures; unsupported and manual cases are skipped.

Physical devices, other live CJK candidate keyboards, arbitrary TUI bindings, hardware API coverage, real reboot scheduling, automation-host execution, and launcher widget behavior remain outside the verified matrix.

## Downloads and source

Each fork publishes its own APK. The combined distribution provides all six APKs, `ADDONS.json`, `ADDON-SHA256SUMS`, GPLv3, and corresponding source for all forks plus the pinned muxy libraries. Verify downloads with `sha256sum -c ADDON-SHA256SUMS`.

Build instructions and compatibility details: [Termux addons for muxy](../addons.md).
