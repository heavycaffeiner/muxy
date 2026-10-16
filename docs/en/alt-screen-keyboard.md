# Touchscreen keyboard and TUI clicks

A single touchscreen tap opens the software keyboard without sending a terminal mouse click. This works on ordinary and alternate screens, including when an application enables mouse reporting. Single-tap handling waits for Android to rule out a double tap.

A completed double tap sends one mouse press/release pair at the second tap's position when the application requests mouse reports. It does not request the keyboard or run the single-tap URL action. Without mouse reporting, a double tap does not send terminal input. It does not hide a keyboard that is already open.

The first tap no longer sends an immediate click. Moving the second contact beyond touch slop, holding it, adding another finger, or cancelling the gesture prevents the click. Long-press selection, pinch scaling, physical mouse input, and touchpad scrolling keep their existing paths.

The terminal protocol does not expose TUI widget focus. This gesture split is explicit: tap once for keyboard access and twice for an application click, rather than guessing whether a cell contains a text field or a button.

The drawer's Keyboard button, extra-key keyboard toggle, and Ctrl+Alt+K remain available. Keyboard-disabled and hardware-keyboard policies still apply. Physical mouse and touchpad events do not open the keyboard.

## Regression coverage

Real PTY/View tests check that a single tap requests keyboard access without mouse bytes, a double tap sends exactly one SGR click without a single-tap callback, cancelled or dragged second taps do not click, and a double tap without mouse reporting does not create input.

The installed-app test covers ordinary and alternate screens with mouse reporting disabled and with modes 1000, 1002, and 1003. It checks single-tap keyboard access, double-tap keyboard suppression, and physical mouse exclusion. Existing pointer tests cover physical press/release and touchpad scrolling.

The signed test APK passes the eight-case ordinary/alternate-screen and mouse-mode matrix on API 35 with Gboard 18.4.1. Android connection/View/pointer suites complete 60 scenarios without failures on API 21, 34, and 35, with unsupported/manual cases skipped. The initial API 21 run aborted during JNI subprocess startup; the affected scenario passes alone and the complete suite passes on rerun. Host checks pass 159 emulator, 51 View, and eight app tests in each of debug and release. Release build and View lint pass.

The coordinated addon release pins the same shared View in Float. Its overlay only restores window focus on touch down; it no longer opens the keyboard on every release. The confirmed single-tap callback requests the keyboard. Double taps, drags, and cancelled gestures therefore do not trigger the overlay's keyboard path. Explicit window controls and initial launch keep their existing keyboard access.
