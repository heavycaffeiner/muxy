# Alternate-screen keyboard access

A touchscreen tap reached the terminal client, but the app refused to show the software keyboard while the terminal application requested mouse reports. Alternate-screen applications commonly enable those reports, so dismissing the keyboard could leave no way to reopen it by tapping the terminal.

The app now permits touchscreen keyboard requests independently of mouse reporting. Physical mouse and touchpad taps do not open the software keyboard. The existing disabled-keyboard flag, URL-tap behavior, text-selection behavior, and remote click reports remain unchanged.

## Verification

- Published muxy 0.119.2 reproduces the failure: enter alternate screen with mouse reporting enabled, dismiss the keyboard, and tap the screen. The keyboard does not return.
- A locally signed APK containing the fix passes the same installed-app test on API 35 x86_64 with Gboard `18.1.3.962075747-beta-arm64-v8a`.
- The installed-app test covers alternate screen without mouse reporting and with modes 1000, 1002, and 1003. A physical mouse tap leaves the keyboard hidden; a touchscreen tap shows it; exiting alternate screen leaves keyboard access intact.
- A real PTY/View regression verifies one touchscreen tap callback and one mouse press/release pair in alternate screen with mode 1000 and SGR mouse reporting.
- 2 app, 159 emulator, and 46 terminal-view JVM tests pass in debug and release. Debug/release APK builds and terminal-view debug lint pass.
- The 42-scenario Android connection/View/pointer suite completes without failures on API 21 x86 and API 35 x86_64. Manual-input and newer-API cases are skipped when their prerequisites are absent.

The local signed APK retains version 0.119.2 for this verification only. It is not a new published release. Physical devices and OEM keyboard variations remain unverified. Remotly was not changed.
