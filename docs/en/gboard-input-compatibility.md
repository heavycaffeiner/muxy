# Gboard input compatibility

## Failure and fix

On Android 15, Gboard `18.1.3.962075747-beta-arm64-v8a` delivered these calls for `안`:

```text
commitText("ㅇ", 1)
beginBatchEdit()
replaceText(0, 1, "아", 1, null)
endBatchEdit()
beginBatchEdit()
replaceText(0, 1, "안", 1, null)
endBatchEdit()
```

The inherited `BaseInputConnection.replaceText` updated its private Editable instead of the terminal. Only the initial consonant reached the PTY. The same beta used replacement calls for English autocorrection and its trailing space.

The connection now implements replacement against a bounded input-only editor document. It retracts the changed suffix with DEL and sends the replacement text. UTF-16 ranges and surrogate boundaries are checked before any write. The editor retains at most 4,096 UTF-16 units of committed context with an absolute start offset; trimming context does not discard terminal input. Replacements requiring more than 64 retraction keys are rejected.

Submitted lines and terminal navigation invalidate editable ranges. Terminal output and scrollback never become editable IME context. Arbitrary caret movement or replacement containing a line break is not translated into terminal edits.

Legacy `setComposingText` remains supported. Settled Hangul syllables are released once; the last cluster stays local until commit. Selection notifications are deferred until a commit batch finishes. Surrounding text, snapshots, selection, and cursor anchors use the same offsets.

Android can call `onCreateInputConnection` and discard its result while retaining an earlier connection. New connection wrappers share the editor session without closing the served wrapper. An explicit terminal session reset invalidates all wrappers. Closed wrappers reject callbacks.

## Physical Shift and touchpad follow-up (muxy only)

A modifier-only `KeyEvent` was treated as new terminal input. In the committed-jamo path, pressing Shift after `이` invalidated the range Gboard needed to replace with `있`. Modifier down/up events now leave the editor document and preedit intact. Printable Shift chords and Ctrl shortcuts keep their existing paths.

Touchpad two-finger travel can arrive as classified `ACTION_MOVE` events rather than `ACTION_SCROLL`. muxy now handles those gesture axes, unclassified two-pointer `SOURCE_TOUCHPAD` travel, and wheel events from mouse or touchpad sources. Historical travel samples are summed. Partial rows accumulate only for the same device and destination. Duplicate touch/generic deliveries are consumed once. Pinch and horizontal-only gestures do not create vertical scroll or remote button drags.

The existing scroll destinations remain: normal screen history, mouse wheel reports when requested by the application, and arrow keys on an alternate screen without mouse tracking.

Verification for this follow-up:

- 159 emulator and 46 input/layout/scroll JVM tests pass. Debug app build and terminal-view lint pass.
- The 41-scenario Android connection/View/pointer suite completes without failures on API 21 x86 and API 35 x86_64. The manually driven IME case and API 34-only gesture cases are skipped where their prerequisites are absent.
- Injected Linux keyboard events on the emulator's keyboard device passed through actual Gboard `18.1.3.962075747-beta-arm64-v8a` and `18.4.1.985164140-release-arm64-v8a`. `D`, `L`, left Shift+`T` produced `있`; subsequent left/right Shift sequences produced `쌍 때`. The real PTY received `있 쌍 때` as the expected UTF-8.
- Pointer tests exercise mouse wheels, fractional touchpad wheels, classified two-finger travel in both directions, batched travel, late classification without a false mouse click, duplicate delivery, primary mouse press/release, remote wheel output, and alternate-screen arrow output.

No physical keyboard/touchpad device was attached to the test host. Keyboard verification used the emulator's Linux input device; pointer verification constructed Android MotionEvents. OEM event variations and touchpad hardware remain unverified. Remotly was not changed in this follow-up.

## Emulator evidence

Tested on API 35, x86_64, using Gboard's Korean 2-set layout. ARM64 builds run through the system ARM translation layer. The AVD used two CPU cores; four cores caused these ARM64 Gboard builds to crash in their native decoder before input testing.

| Gboard build | muxy real PTY capture | Remotly native encoder capture |
| --- | --- | --- |
| `12.4.05.482060964-preload-x86_64` | Passed | Passed |
| `17.8.4.939743344-beta-arm64-v8a` | Passed | Passed |
| `17.8.7.939743344-release-x86_64` | Passed | Passed |
| `18.0.3.954559732-beta-arm64-v8a` | Passed | Passed |
| `18.1.3.962075747-beta-arm64-v8a` | Passed | Passed |
| `18.4.1.985164140-release-arm64-v8a` | Passed | Passed |

Each test tapped actual Gboard keys and checked `안녕 한글 안자 과의` after Enter. This covers ordinary syllables, spaces, a compound final consonant splitting into two syllables, and compound vowels. Beta 18.1.3 also passed repeated Backspace, decomposition of the previous syllable, deletion of a separator, and retyping. Actual English key taps produced `teh quick test ` after deleting two characters and accepting the automatic `quick ` and `test ` replacements, including their spaces, in both apps.

JVM regressions replay committed-jamo replacement, final-consonant carry, English autocorrection, malformed ranges, submitted-line protection, and bounded context. Android tests cover real connection methods, wrapper replacement, closure, nested dispatch, and PTY ordering. API 21 connection and PTY tests pass, including class loading with the newer API overrides present.

Final JVM results: 159 muxy emulator tests, 43 muxy input/layout tests, and 639 Remotly app tests pass. The Android connection/View suites report 26 muxy scenarios on API 21 and API 35, and 19 Remotly scenarios on API 35. Each suite includes one manually driven test that is skipped when its argument is absent. API 21 tests do not invoke API 34 replacement methods.

The manual instrumentation entry points accept `gboardExpected`. Without that argument they are skipped. No diagnostic build logs typed text. The test assertions compare bytes or reconstructed native encoder input, not screenshots.

This is evidence for the listed builds and both observed input protocols, not an exhaustive test of every Gboard release or server-side experiment. Physical devices, other keyboard layouts, and arbitrary remote TUI key bindings remain untested. The six-version Remotly matrix covers its IME-to-native-encoder path. The follow-up signed-release check also delivered `안녕 한글` through an actual SSH PTY on Gboard beta 18.1.3. The test server required `stty iutf8` so its line discipline erased a UTF-8 character rather than one byte. Signed muxy 0.119.1 passed the same byte capture through its local PTY.
