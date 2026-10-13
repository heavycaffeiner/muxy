# libghostty-vt core and CJK migration

Status: native core, read adapters, renderer, and CJK input integration are implemented and packaged for 0.119.0. API 21 x86 and API 34 x86_64 emulator tests pass. Preserve every existing muxy/Termux feature, API 21, and x86. Equal-or-better history memory and performance still require measurements. Release coverage and signing instructions are recorded in [0.119.0](releases/0.119.0.md).

Implementation progress:

- `TerminalEmulator` now delegates VT state to libghostty-vt. The production Java parser is removed. `libtermux.so`, local PTYs, existing module boundaries, and session ownership remain in place.
- Native row snapshots drive the existing renderer, text selection, transcript export, and history reads. Native hooks provide Sixel/iTerm2 dispatch and image-cell anchoring, DEC rectangles, and Termux query/reset behavior. Sixel/iTerm2 decoding, placement, and Canvas rendering tests pass on API 21 x86 and API 34 x86_64.
- Native blank cells retain the existing foreground, background, and effects. Top-margin scrolling retains departing rows even when the fixed area crosses native pages. History pruning retains the boundary page, and the emulator schedules bounded compression after idle output. Comparative memory/throughput checks remain pending.
- Composition state, the dedicated input connection, native Unicode layout, preedit drawing, and cursor anchors are connected to `TerminalView`. Connection closure rejects stale IME callbacks. Enter/Space flush ordering and local Backspace releases have regression coverage.
- Legacy key events use Termux's existing byte encoding. Extended keyboard protocols use the native encoder. Each event uses one path, not both.
- The latest host checks pass 159 emulator tests, 37 composition/layout tests, and two app tests in each of debug and release. Android input-connection and real PTY/View tests pass 21 scenarios on both API 21 x86 and API 34 x86_64. Live Gboard Korean 2-Bulsik delivered `안녕 한글` as exactly 13 UTF-8 bytes once on the API 34 debug app. Japanese/Chinese have callback tests, not live candidate-IME coverage.
- All four native archives and JNI libraries build for API 21. Every packaged native library's non-weak imports resolve against the NDK's API 21 dependency libraries. All ELF LOAD segments have at least 16 KiB alignment. Debug/release emulator/view AARs and the complete signed release APK matrix build for both bootstrap variants. Embedded bootstrap hashes are checked per ABI. Universal signed APKs are 122,312,550 bytes for `apt-android-7` and 76,687,206 bytes for `apt-android-5`; no baseline size comparison has been made.
- Signed `apt-android-5` x86 and `apt-android-7` x86_64 release APKs install and run on API 21 and API 34 respectively. Two installed-app tests per emulator pass for shell commands, rotation/session retention, touch extra-arrow input, and forward deletion. Startup now withholds an input connection until the emulator exists. Ready-view first-key preservation has PTY coverage. Early external input injection before readiness is not guaranteed.
- Gboard batch-editor queries originally lost already released Hangul syllables. The input connection now reports the complete editor composition while keeping only undecided text local. Batch-query regression tests and live Korean input pass. ARM runtime, live Japanese/Chinese/Samsung IMEs, custom fonts, and history performance measurements remain pending.
- CI and JitPack include pinned Zig setup. These remote workflows have not been executed from this working tree.

Intentional native semantics: valid but unassigned Unicode scalars are preserved; malformed UTF-8 uses maximal-subpart replacement; column reflow retains the leading blank row and cursor content in the covered pending-wrap case. The tests assert these behaviors rather than using the Java runtime's older Unicode assignment table.

## 1. Design boundary

muxy remains muxy. Replace its terminal core with libghostty-vt and implement the CJK behavior using Remotly as a reference.

Remotly supplies two references:

- The binding approach: a pinned libghostty-vt build, a C JNI boundary, thread-confined native state, and Java-side application integration.
- CJK behavior: composition state, commit ordering, Hangul handling, preedit layout, font fallback, caret anchoring, and regression scenarios.

Remotly's implementation is not a template to copy wholesale. Do not import its View, renderer architecture, binary frame format, font set, image feature set, SSH core, connection store, themes, or Compose UI.

The authoritative VT parser, cursor, modes, screen, and history belong to libghostty-vt. Java owns muxy's existing application behavior and read-only representations used to display that native state. There is no second production VT parser and no runtime backend selector.

### Preserve

- muxy's app, UI, settings, appearance, session management, local subprocesses, PTYs, and byte queues.
- `libtermux.so` and the current local process I/O path.
- Existing `terminal-emulator` / `terminal-view` module boundaries, package names, Java source, and Java 8 language target.
- Existing view/client callbacks, extra keys, modifiers, shortcuts, mouse handling, scrolling, selection handles/actions, styling, transcript export, and accessibility behavior.
- Custom `font.ttf`, existing default typeface, font-size settings, palette, and cursor preferences.
- Existing supported terminal features, including Sixel and iTerm2 inline images. Missing native support is implementation work, not permission to remove a feature.
- API 21, the four existing ABIs, and both existing package variants as compatibility targets.

Keep the required changes at the native boundary, screen read adapters, and CJK input/rendering seams. Do not turn this into a general UI or application rewrite.

## 2. Source findings

Remotly's current terminal core is libghostty-vt, not gomobile:

```text
SSH output -> RemotlyTerminal.kt -> remotly_terminal.c -> libghostty-vt
IME / keyboard -> composition controller -> native key encoder -> SSH input
```

`mobile/sshcore` and `sshcore.aar` provide SSH/SFTP transport. Importing that AAR would not replace the VT emulator or implement CJK composition.

Inspected source revisions:

| Source | Revision |
| --- | --- |
| muxy | `8629e632fcb95da272221be327db653fb24befe9` |
| Remotly HEAD | `38f8eead5fa9f7f4261d89808d336a9969ede512` |
| libghostty-vt, from Remotly's `terminal-native/PIN.txt` | `0ba6250388641f52135414b38c4259aa682c489b` |

Remotly has uncommitted terminal changes and another session is editing its working tree. Treat it as a read-only reference. Before implementation, record the selected reference files and their hashes without resetting, checking out, or modifying Remotly.

The Ghostty pin establishes the initial API baseline. Any required native parity changes must be recorded as revision-bound patches or a pinned fork revision, not untracked edits in a developer checkout.

## 3. Integration architecture

```text
Local PTY output
  -> existing TerminalSession main-thread handler
  -> TerminalEmulator facade
  -> muxy's NativeTerminal JNI binding
  -> libghostty-vt
  -> read-only row/cell snapshots
  -> existing TerminalRenderer / TerminalView

IME
  -> dedicated TerminalInputConnection
  -> composition controller
  -> normalized text/key input
  -> legacy Termux key encoding or native extended-protocol encoding
  -> existing TerminalOutput / TerminalSession.write
  -> local PTY

Clipboard paste
  -> native paste encoder, separate from IME key/text encoding
  -> existing PTY write path
```

### Native ownership

`TerminalSession` owns one emulator. The emulator owns one native handle. All terminal operations and callbacks stay on the main thread. I/O threads continue to use byte queues, not native state.

A View does not own the handle. Rotation and view replacement retain it. Process exit retains the final screen and transcript until the session is removed. Add explicit, idempotent disposal at session removal and service teardown.

Callbacks bind to the emulator/session, not an Activity or View global reference. Handle partial native creation, failed allocation, JNI exceptions, and library-load failure explicitly. Reject stale input-connection callbacks after session switching or disposal.

### Existing Java API

Keep `TerminalEmulator` as the facade used by muxy. Dimensions, cursor/mode queries, title, paste, reset, colors, mouse operations, and callbacks read or modify native state.

Keep the `TerminalBuffer` read surface used by the app, view, selection controller, and `ShellUtils`. Replace its authoritative storage with native-backed operations for history counts, words, selection text, transcript variants, styles, and history clearing.

Use reusable row/cell snapshots for rendering. If `TerminalRow` remains as the read representation, populate text offsets, complete cluster text, occupancy, styles, and wrap information from native state. Do not reconstruct column positions with the old `WcWidth` table. Do not mirror the full native history into another mutable Java screen.

The JNI read format is designed for muxy's existing renderer. It must preserve indexed/default colors as well as truecolor, effects, wide-cell spacers, wrap state, and image placements. Remotly's serialized frame is evidence of one workable binding technique, not a required format.

Preserve joined/unjoined transcript exports. Range extraction must not change the user's selection or viewport. Keep exported text valid and complete, including wide cells and combining sequences.

Synchronize palette/OSC changes and background callbacks. Adapt direct `mColors.reset()` calls so native defaults change too. Map cursor enums explicitly because Ghostty and muxy use different numeric orders.

### Scrolling and selection

Keep muxy's existing scrolling UI, negative `mTopRow` convention, auto-scroll setting, fling behavior, and selection handles. Translate their coordinates at the native boundary.

Native state owns viewport/history positions. Use native references and actual scroll state for anchoring on output or resize. Do not infer scrolling from history length: a full buffer or margin scroll can move content without increasing that length.

Selection endpoints and CJK hit tests use native cell boundaries, including the trailing half of a wide cell. Update the existing selection controller's `WcWidth`-based calculations without replacing its UI.

## 4. Reference-to-change map

Remotly filenames below are under `app/android/terminal-native/` or `app/android/app/src/main/java/com/remotly/app/terminal/`.

| Reference | Required muxy change |
| --- | --- |
| `PIN.txt`, `build-android.sh`, `remotly_terminal.c` | Implement muxy's pinned native build and C JNI binding; retain existing PTY JNI |
| `RemotlyTerminal.kt` | Define a Java `NativeTerminal` binding around muxy's own callback and error contracts |
| `TerminalFrame.kt`, native render-state iteration | Design reusable native-backed row/cell reads for the existing renderer; do not adopt the source wire format by default |
| `CompositionState.kt` | Implement composition state, UTF-16 sanitation, selection mapping, and native Unicode-backed preedit layout |
| `TerminalImeController.kt`, `TerminalInputConnection.kt` | Implement the composition state machine and Android input connection, preserving muxy's surrounding key/client behavior |
| `KeyMap.kt` | Adapt Android key mapping to native encoding; preserve muxy modifiers, AltGr, shortcuts, and extra keys |
| CJK-related parts of `TerminalView.kt` | Add input-connection generations, preedit lifecycle, cursor-anchor updates, and drawing integration in muxy's existing View |
| CJK-related parts of `TerminalRenderer.kt`, `TerminalFontSet.kt`, `GlyphWidthCache.kt` | Add full-cluster drawing, CJK fallback/fit, and measurement caching only where muxy's renderer needs them |
| Composition/key/layout regression tests | Port the scenarios to muxy's Java/JUnit and Android tests, rather than translating unrelated source tests |

muxy's `TerminalRenderer`, `TerminalView`, and text-selection classes stay in place. Internal loops and data reads will change where the core representation or CJK geometry requires it.

## 5. CJK behavior

The reference implementation is not proof that every IME is correct. Port its intended rules and address boundary mismatches found during review.

1. Sanitize malformed UTF-16 at the input boundary. Keep native output byte-oriented so UTF-8 may span PTY reads. Do not normalize valid NFC/NFD user text into a different string.
2. Keep undecided preedit local. Draw it at the terminal insertion point without sending it to the PTY.
3. Preserve the intended release of settled Hangul syllables while holding the final cluster. Japanese and Chinese candidate text remains local until committed. Test corrections to already released Hangul explicitly: arbitrary application input cannot generally be retracted safely.
4. For Enter or Space passed through by the IME, commit the remaining preedit before forwarding the key. Suppress only the matching late echo. Do not swallow an unrelated later commit or new suffix.
5. Local preedit deletion must not also delete shell text. Respect separate UTF-16 and codepoint deletion contracts without splitting a surrogate pair. A forwarded Backspace edits complete preedit clusters; IME deletion requests retain their requested unit semantics. Preserve normal Backspace/Delete outside composition.
6. `finishComposingText` must not create another PTY commit. Closing or replacing a connection does not submit undecided text. Do not restart the IME after every commit.
7. Preserve Ctrl/Alt/Shift, AltGr, dead keys, action-multiple input, key releases, extra-key modifiers, and system keys. Use legacy `KeyHandler`/text encoding when no extended protocol is active, and native encoding for extended protocols. Never encode the same event twice.
8. Paste is not typing. Use the paste encoder and bracketed-paste mode; do not turn each pasted newline into an Enter key.
9. Preedit segmentation/width must match native semantics. The pinned API provides `ghostty_unicode_codepoint_width` and `ghostty_unicode_grapheme_width`. Replace Remotly's approximate range table/platform `BreakIterator` approach where they disagree. Respect application mode 2027 rather than forcing a new width mode.
10. Map UTF-16 offsets to cluster/cell boundaries. Draw a complete native cluster once and skip spacers. Use the same layout for preedit, selection, caret, backgrounds, IME anchors, and right-margin wrapping.

Use a valid `TYPE_CLASS_TEXT` editor configuration while preserving muxy's action/key behavior. Candidate keys consumed by the IME do not get forwarded again. Report cursor anchors in local coordinates with the view-to-screen transform and correct visibility flags. Guard post-API-21 calls.

### Rendering and fonts

Keep muxy's primary typeface and cell metrics. Native occupancy determines columns; Android glyph measurements only determine fitting inside those columns.

Add CJK fallback and full-cluster drawing to the existing renderer. Preserve custom fonts, style effects, cursor shapes, palette handling, and selection appearance. Invalidate caches on font, size, or locale changes.

Do not bundle Remotly's JetBrains/Nerd/CJK font set automatically. Its source assets total 37.4 MiB and would also change muxy's default appearance. First verify existing font/fallback coverage. If a bundled CJK fallback is necessary, propose only the required asset set, with size and licenses, before adding it. Check Korean, Japanese, Simplified Chinese, and Traditional Chinese regional fallback explicitly; Remotly's `zh` to SC mapping is not a universal regional solution.

## 6. Compatibility work, not automatic exclusions

| Area | Observed difference | Required treatment |
| --- | --- | --- |
| API level | muxy uses API 21; Remotly builds native code for API 24 | Target API 21 and verify native symbols/loading. No unapproved minimum-SDK increase. |
| ABIs | muxy ships x86, x86_64, armeabi-v7a, arm64-v8a; Remotly builds three | All four JNI builds compile and link for API 21. API 21 x86 and API 34 x86_64 runtime tests pass; ARM runtime coverage remains pending. |
| Toolchain | Ghostty declares minimum Zig 0.16.0; muxy declares NDK `29.0.14206865` | The declared NDK is installed locally and used for the foundation build. No NDK or minimum-SDK requirement was changed. |
| Sixel | muxy supports it; the inspected native DCS handler does not | Add native DCS integration and native-anchored placement while reusing muxy's applicable decoding/drawing code. |
| iTerm2 inline images | muxy supports OSC 1337 inline/multipart images; Remotly's image path is Kitty | Add native OSC integration for the existing behavior. Clipboard OSC 1337 support is not image support. |
| DEC rectangles | muxy has rectangle-operation tests; matching native stream handlers were not found | Check the test inventory and add the required native operations. Do not delete assertions to disguise a regression. |
| Queries | Responses/device identities can differ between cores | Preserve muxy's required DA/DSR/DECRQM/XTGETTCAP/DECRQSS and size behavior where applicable; report only implemented capabilities. |
| Unicode | muxy's width table describes Unicode 15; Ghostty has pinned tables/grapheme semantics | Native occupancy is authoritative. Adapt renderer, selection, preedit, and tests together. |
| History | muxy has a fixed total-row buffer; native line/byte pruning is page-granular | Preserve at least the configured legacy retention, accounting for visible rows. Use native page storage and compression, and compare retention, memory, and resize behavior with the old implementation. |
| Mutable internal APIs | legacy public buffer classes and tests expose Java storage | Preserve repository consumers; document any published mutable-buffer API break separately. |

The current unknown-sequence callback reports APC only. Preserving unsupported CSI/DCS/OSC behavior may require changes to the pinned native parser/dispatch API. Do not assume an existing JNI callback can provide those missing hooks.

For images, retain muxy's applicable Bitmap decoding, placement drawing, and bounds checks. Native dispatch hooks feed the existing Java decoders, and native image cells retain placement metadata through scrolling/reflow. Do not feed PTY output through both engines or build a separate Java VT interception layer. API 21/API 34 emulator Canvas rendering checks pass for Sixel and iTerm2. Physical-device rendering and image memory-limit measurements remain pending.

Kitty image support is not a new requirement merely because Remotly has it. Do not add its image cache or protocol UI as part of this migration unless needed to preserve an existing muxy feature or separately requested.

Every existing feature must be preserved. Native parity work is required even when the pinned core lacks a handler. API 21 and x86 are mandatory, not optional fallbacks. History may retain more data under Ghostty's page model, but must not retain less than the legacy configuration. These requirements still need build, runtime, and measurement evidence.

## 7. Native build and safety

- Keep `libtermux.so`. Add `libmuxy_terminal.so` through the existing native module/build.
- Have Gradle prepare pinned static archives before NDK linking for APK/AAR builds. Do not require manually staged `.so` files.
- Use build-cache source/dependencies with pinned revisions and hashes. CI cannot depend on a local Remotly tree, `/home/hyun/Toolchains/ghostty`, or a personal shell configuration file.
- Match bridge and headers. Cache by source/patch hash, ABI/API, Zig/NDK versions, and build options. Write staged outputs atomically.
- Update build/test workflows and JitPack as required. Preserve both `apt-android-5` and `apt-android-7`, all APK splits, and emulator/view AAR publication.
- Check ELF dependencies, required Android symbols, and 16 KiB page compatibility. Do not inherit the old PTY module's disabled stack-protector flag for the new bridge.
- Validate dimensions, array offsets, text lengths, direct-buffer sizes, integer conversions, and serialized reads. Rejected operations cannot leave Java/native dimensions out of sync.
- Handle creation/disposal, allocation/encoding failures, and callback exceptions explicitly. Never silently lose input on a failed write/encoding path.
- Bound image data, decoded pixels, placements, clipboard strings, and exports. Do not enable unneeded remote filesystem/shared-memory graphics access.
- Preserve muxy's clipboard policy and callbacks. No new permission bypasses, unsolicited clipboard reads, notifications, or automatic URL launches.
- Preserve accessible text extraction, keyboard reachability, focus, selection/copy actions, and non-color-only composition/caret cues. IME diagnostics must not log typed text or PTY bytes.
- Retain applicable Remotly/Ghostty MIT notices and existing module licenses. Include font notices only for any assets actually redistributed. Do not relabel the repository's overall license.

## 8. Implementation sequence after review

### Step 1: native foundation

Record the reference baseline and pin. Implement portable source/archive preparation, patch tracking, C JNI packaging, and the Java binding. Compile the API 21 four-ABI matrix and inspect link requirements. Resolve native feasibility blockers before deleting the current implementation.

### Step 2: authoritative core and read adapters

Move VT state behind `TerminalEmulator` to libghostty-vt. Implement ownership/disposal, callbacks, metadata, modes, colors, reset, history, transcript variants, and row/cell reads. Preserve PTY/session integration. Establish native hooks needed for existing image/rectangle behavior.

### Step 3: preserve renderer and terminal features

Adapt muxy's renderer and selection reads to native clusters/cells. Preserve current draw/UI conventions, font metrics, styles, cursor, scrolling, selection, and export. Complete existing protocol parity through native dispatch/state and existing applicable image code.

### Step 4: CJK input and display

Implement the composition state machine, dedicated input connection, native Unicode-backed preedit layout, overlay drawing, and cursor anchors in muxy's View. Connect keyboard, extra-key, shortcut, and paste paths to one encoder. Preserve muxy client behavior and reject stale connection events.

### Step 5: review and verification

After the integrated implementation is complete, review ownership and boundaries, fix issues, and run the checks below. Remove unused Java parser/storage paths without deleting unapproved behavior expectations. Report actual compatibility, coverage gaps, APK size, memory, and redraw measurements.

Changes stay in muxy. Implementation is approved under the compatibility requirements above.

## 9. Acceptance checks

### Automated

- Relevant Remotly composition scenarios: settled syllables, commit-once, finish/restart, carry/flush, Hangul Backspace/typing, and Enter ordering.
- Adversarial callback ordering, identical consecutive words, echo plus new suffix, candidate revisions, UTF-16/codepoint deletion, and stale input connections.
- NFC/NFD Hangul, compatibility jamo, kana, Han, fullwidth punctuation, combining accents, supplementary ideographs, variation selectors, emoji modifiers, and ZWJ sequences.
- Agreement between native cells and preedit under mode 2027 on/off, right/bottom margins, resize, and split UTF-8 output.
- Existing muxy VT behavior, especially images, rectangle operations, modes, colors, query replies, paste, history, selection, and transcript variants.
- Adapt `TerminalTestCase` away from authoritative `mLines`, `mMainBuffer`, and `mAltBuffer` assumptions. Keep behavioral assertions and distinguish intentional native semantic differences from regressions.
- Pure Java tests do not depend on Android binaries. Use a same-pin host JNI build for native facade tests and Android instrumentation for View/Canvas/JNI integration. Missing required native artifacts fail CI instead of causing silent skips.
- Malformed native reads, extreme input sizes, partial creation, disposal, failed callbacks, image bounds, and clipboard policy.

### Device and application

- Gboard Korean/Japanese/Chinese; Samsung Keyboard Korean on a Samsung device if available. Report the actual IME/device versions and missing coverage.
- Hardware keyboards, dead keys, AltGr, Ctrl/Alt/Shift, key releases, Enter/Space/Backspace, and extra keys.
- Shell/readline, vim, tmux, and a key-driven TUI. Confirm exact PTY byte ordering with no missing or duplicate CJK commits.
- Candidate selection, edits, right-margin wrapping, bottom-row composition, rotation, session switching, focus loss, keyboard reopen, and finished-session disposal.
- Existing muxy UI behavior: plain/bracketed paste, wide-cell/soft-wrap selection, copy, exports, output while scrolled, auto-scroll disabled, palettes, custom fonts, and accessibility navigation.
- API 21 and modern Android smoke checks where suitable devices/emulators exist. Build both package variants, all four ABI splits, universal APKs, and both terminal AARs.
- Compare current/native builds for APK size, large-output/history/image memory, and redraw cost. State measurements rather than unverified performance claims.

CJK completion means the agreed input/layout matrix passes with correct PTY ordering and cell geometry. It does not establish compatibility with every untested IME, font, or remote application's Unicode-width convention.

## 10. Review decisions

The architectural direction is fixed by the user's clarification: keep muxy, replace its authoritative core with libghostty-vt, and implement Remotly-informed CJK behavior within muxy's existing structure.

The user approved full existing-feature preservation, mandatory API 21/x86, and Ghostty-based history optimization with equal or better results. Missing Sixel/iTerm2/rectangle handlers must be implemented. Any additional CJK font assets remain subject to size/license review rather than automatic import.
