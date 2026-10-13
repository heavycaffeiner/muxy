#!/usr/bin/env bash
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
module="$(cd "$here/.." && pwd)"
java_home="${1:?Java home is required}"
bash "$here/build.sh" host
output="$module/build/native/host/jni"
mkdir -p "$output"
temporary="$(mktemp "$output/libmuxy_terminal.XXXXXX.so")"
trap 'rm -f "$temporary"' EXIT
cc -shared -fPIC -std=c11 -Wall -Wextra -Werror \
    -I "$java_home/include" -I "$java_home/include/linux" \
    -I "$module/build/native/ghostty/include" "$module/src/main/jni/muxy_terminal.c" \
    -L "$module/build/native/host/lib" -lghostty-vt \
    -Wl,-rpath,'$ORIGIN/../lib' -o "$temporary"
mv "$temporary" "$output/libmuxy_terminal.so"
trap - EXIT
