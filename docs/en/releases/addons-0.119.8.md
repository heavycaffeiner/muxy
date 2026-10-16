# muxy addons 0.119.8

Coordinated source-built `muxy.5` releases for all six organization forks. Shared-library consumers pin muxy `4f19a15cdbdce7334a7f2429bf45aa086b698896`.

## Touch routing

Main muxy and Float restore original Termux behavior instead of the custom double-tap click gesture:

- Mouse reporting enabled: one tap sends one immediate press/release pair without requesting the keyboard.
- Mouse reporting disabled: a confirmed single tap opens the keyboard on primary or alternate screens.
- Double tap has no custom action. Two taps in a mouse-reporting application are two ordinary clicks.
- A TUI exit, mouse-mode change, screen change, or input-generation change cannot redirect an old contact into a new action. Delayed confirmation of an application click does not open the keyboard after the application exits.

Float's outer overlay does not open the keyboard on every touch release. Its client uses the same mouse-state rule. Explicit keyboard controls and shortcuts remain available. TUI widget focus is not exposed by the terminal protocol.

API, Tasker, and Widget refresh their pinned shared libraries. Styling and Boot retain standalone runtime behavior. Existing native core, CJK input, excess-Backspace, image, color, and terminal disposal paths are retained.

| App | Version | Minimum Android |
| --- | --- | --- |
| Termux:API | 0.53.0+muxy.5 | 7.0 |
| Termux:Styling | 0.32.1+muxy.5 | 5.0 |
| Termux:Boot | 0.8.1+muxy.5 | 5.0 |
| Termux:Float | 0.17.0+muxy.5 | 7.0 |
| Termux:Tasker | 0.9.0+muxy.5 | 5.0 |
| Termux:Widget | 0.15.0+muxy.5 | 7.0 |

## Upgrade and artifacts

Install over previous muxy-signed builds without uninstalling. Package names, shared UID, permissions, minimum SDK requirements, and release certificate are unchanged. Keep the main app's existing bootstrap variant. Differently signed original upstream, F-Droid, and Play APKs cannot share muxy's UID.

Signer SHA-256:

```text
756768af514d3fa0d1c214d54dabd7b0cd088a1723a22f520b90e8e8545262ce
```

Each fork publishes its APK. The combined release includes all six APKs, `ADDONS.json`, `ADDON-SHA256SUMS`, GPLv3 text, and corresponding source for all six forks and pinned muxy. Check downloads with `sha256sum -c ADDON-SHA256SUMS`.

## Verification

- Clean pinned-source builds and all six signed APKs pass checksum, signer, package, SDK, shared UID, and native 16 KiB alignment checks. Float includes the native core for all four ABIs.
- All six upgrade and share muxy's UID on API 35. Styling, Boot, and Tasker also upgrade on API 21.
- The shared connection/View/pointer suites complete 66 scenarios on API 21/34/35 without failures. API 21 skips 12 scenarios; API 34/35 skip optional live input. Some newer-API assertions return early on older Android.
- Debug and release host tests pass 159 emulator, 51 View, and eight main-app tests per build type. Main release APK builds and View lint pass.
- Signed main upgrades pass shell commands, rotation/session preservation, and extra-key editing on API 21/35. The signed API 35 app passes ordinary/alternate-screen keyboard policy with modes 0/1000/1002/1003 and explicit drawer access.
- Float's seven-scenario overlay/PTY suite completes without failures on API 35, with optional live input skipped. Its eight-case mouse/screen matrix checks keyboard visibility, explicit access, and exact click bytes alongside CJK, deletion, native colors, and disposal regressions.

Physical devices, other live IMEs, per-widget focus in arbitrary TUIs, real reboot scheduling, automation-host execution, and launcher widget behavior remain outside this verification matrix.
