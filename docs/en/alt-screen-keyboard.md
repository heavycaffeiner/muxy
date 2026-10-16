# Terminal taps and alternate-screen keyboard access

Touch routing follows the original Termux policy:

- With mouse reporting enabled, a touchscreen tap sends one immediate mouse press/release pair on finger release. It does not request the software keyboard.
- Without mouse reporting, a confirmed single tap requests the keyboard. This works on both primary and alternate screens.
- Double tap has no custom action. With mouse reporting, each tap is an ordinary click. Without mouse reporting, the double tap does not request the keyboard.

Alternate-screen mode alone does not indicate whether an application wants mouse input or keyboard input. The terminal protocol does not expose TUI widget focus. For an application that keeps mouse reporting enabled, use the drawer Keyboard button, extra-key keyboard toggle, or Ctrl+Alt+K. Float's explicit window controls and keyboard shortcut remain available.

## Mode transitions

Each contact records its emulator, input generation, mouse-reporting state, and active screen at touch down. A changed session or changed screen/mouse state invalidates that contact's action. A click that exits a TUI cannot turn into a delayed keyboard request when Android later confirms the same tap. A new tap on the resulting shell works normally.

Dragging, holding, cancellation, multitouch, physical pointers, text selection, and touchpad scrolling keep separate paths. Finger movement beyond touch slop or a held/cancelled contact does not become an application click. A selection-dismissal tap does not also click the application.

Float uses the same shared View policy and also checks mouse reporting in its single-tap client. Its outer overlay restores window focus on touch down without opening the keyboard on every release. Launch and explicit controls retain keyboard access.

## Verification coverage

PTY/View regressions cover immediate single clicks, two ordinary clicks from a double tap, no custom double-tap action without mouse reporting, alternate-screen keyboard access without mouse reporting, mode changes during contact, TUI exit before delayed confirmation, screen changes before confirmation, and input-generation invalidation. Existing cancellation, movement, hold, multitouch, physical mouse, and IME regressions remain included.

Installed main-app and Float tests pass on API 35 with ordinary/alternate screens, mouse reporting disabled, and modes 1000/1002/1003. The main test verifies explicit drawer keyboard access during mouse reporting; Float's overlay/PTY test verifies the corresponding explicit control path and mouse bytes.

Debug/release host tests pass 159 emulator, 51 View, and eight app tests per build type. Android connection/View/pointer suites complete 66 scenarios without failures on API 21, 34, and 35. API 21 skips 12 scenarios; API 34/35 skip optional live input. Release APK build and View lint pass.

Float was checked against an exported working-source pair, not a newly published dependency pin. Its checked-in pin still references the previous release until the coordinated update is committed. Physical devices and per-widget focus in specific TUIs remain outside this protocol-level coverage.
