# Native build

`PIN.txt` records the Ghostty revision, Zig version, and selected Remotly reference hashes. The build uses muxy's declared NDK version and Android API 21 for all four ABIs.

## Requirements

- Linux, Bash, Git, `flock`, and SHA-256 tools.
- Zig 0.16.0 on `PATH`, or an executable path in `ZIG`.
- Android SDK with NDK 29.0.14206865.
- The project's Gradle wrapper and Java runtime.

Gradle runs `native/build.sh` before NDK configuration and linking. The script fetches the pinned source into `terminal-emulator/build/native/ghostty`, applies the recorded patches, and stages static archives under the ABI directories. It does not read Remotly or a developer's Ghostty checkout.

```sh
./gradlew :terminal-emulator:assembleDebug :terminal-view:assembleDebug
```

For CI, `install-zig.py DESTINATION` installs the pinned compiler into a new directory. It verifies the release archive size and SHA-256 checksum before extraction. Python must provide `tarfile.data_filter`. Existing installations are checked rather than replaced.

## Patches

- `0001-android-api21.patch`: use the positional-read fallback before Android API 24, including the 64-bit file-offset symbol for 32-bit Android.
- `0002-history-retention-floor.patch`: retain the boundary history page when removing it would leave fewer than the requested history lines. Configured byte limits still apply independently. The JNI binding disables the default byte cap when history is enabled.
- `0003-muxy-protocol-hooks.patch`: native integration hooks, Termux protocol/reset/scroll/style parity, and parser/color boundary fixes. `muxy_compat.zig` is copied into the same pinned build for CSI/DCS/OSC handling.

The row limit can be exceeded by the retained boundary page. The emulator schedules bounded native compression after one second without output. Retention/memory and throughput comparisons remain pending.

## Integration

The JNI library and Java binding are packaged alongside `libtermux.so`. `TerminalEmulator` delegates VT state to libghostty-vt. Native snapshots feed the existing renderer and selection APIs. Native image dispatch uses muxy's Sixel/iTerm2 decoders and image-cell metadata. Kitty image storage and file/shared-memory transports are disabled.

`TerminalView` uses the composition controller, dedicated input connection, native preedit layout, overlay drawing, and cursor anchors. Session removal and service teardown dispose of the native handle. Process exit retains the final screen until removal.

## Tests

Host JNI uses the same source pin and patches as Android. Missing native artifacts fail the build.

```sh
./gradlew :terminal-emulator:testDebugUnitTest :terminal-view:testDebugUnitTest
./gradlew :terminal-view:connectedDebugAndroidTest
```

The Android suite passes 21 input-connection and real PTY/View tests on API 21 x86 and API 34 x86_64, including Sixel/iTerm2 Canvas rendering. Signed app smoke tests pass on both emulators. Live Gboard Korean 2-Bulsik input also passes on the API 34 debug app. Other live CJK IMEs, ARM runtime, and comparative memory/throughput checks remain pending. See `docs/en/releases/0.119.0.md` for exact release coverage and signing requirements.

To test an installed signed APK without replacing it with a debug build:

```sh
./gradlew :terminal-view:assembleDebugAndroidTest
adb -s DEVICE install -r terminal-view/build/outputs/apk/androidTest/debug/terminal-view-debug-androidTest.apk
adb -s DEVICE shell am instrument -w -r \
  -e class com.termux.view.PublishedApkSmokeTest \
  -e expected_certificate CERTIFICATE_SHA256 \
  com.termux.view.test/androidx.test.runner.AndroidJUnitRunner
```

The release verifier checks APK checksums, certificate, manifest, ABI set, embedded bootstrap, API 21 imports, and 16 KiB ELF alignment:

```sh
python3 scripts/verify-release.py RELEASE_DIR 0.119.0 CERTIFICATE_SHA256
```
