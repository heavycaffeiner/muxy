#!/usr/bin/env bash
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
module="$(cd "$here/.." && pwd)"
build="$module/build/native"
source_dir="$build/ghostty"
pin="$(awk '$1 == "commit:" {print $2}' "$here/PIN.txt")"
repo="$(awk '$1 == "repo:" {print $2}' "$here/PIN.txt")"
zig_version="$(awk '$1 == "zig:" {print $2}' "$here/PIN.txt")"
zig_bin="${ZIG:-zig}"
ndk_version="$(awk -F= '$1 == "ndkVersion" {print $2}' "$module/../gradle.properties")"

if [[ ! "$pin" =~ ^[a-f0-9]{40}$ ]] || [[ "$repo" != https://github.com/ghostty-org/ghostty ]]; then
    printf '%s\n' 'Invalid Ghostty source pin' >&2
    exit 1
fi
if [[ "$("$zig_bin" version)" != "$zig_version" ]]; then
    printf 'Zig %s is required\n' "$zig_version" >&2
    exit 1
fi

mkdir -p "$build"
# Serialize changes to the shared source checkout and static archive outputs.
exec 9>"$build/build.lock"
flock 9

if [[ -L "$source_dir" ]] || [[ -e "$source_dir" && ! -d "$source_dir/.git" ]]; then
    printf '%s\n' 'Ghostty cache must be a dedicated Git checkout' >&2
    exit 1
fi
if [[ ! -d "$source_dir/.git" ]]; then
    checkout="$(mktemp -d "$build/checkout.XXXXXX")"
    trap 'rm -rf "$checkout"' EXIT
    git -C "$checkout" init --quiet
    git -C "$checkout" remote add origin "$repo"
    git -C "$checkout" fetch --depth=1 origin "$pin"
    git -C "$checkout" checkout --detach --quiet FETCH_HEAD
    mv "$checkout" "$source_dir"
    trap - EXIT
fi
if [[ "$(git -C "$source_dir" rev-parse HEAD)" != "$pin" ]]; then
    printf '%s\n' 'Ghostty cache revision does not match PIN.txt' >&2
    exit 1
fi

if [[ -d "$here/patches" ]]; then
    for patch in "$here"/patches/*.patch; do
        [[ -f "$patch" ]] || continue
        if git -C "$source_dir" apply --reverse --check "$patch" 2>/dev/null; then
            continue
        fi
        if ! git -C "$source_dir" apply --check "$patch"; then
            printf 'Ghostty patch cannot be applied: %s\n' "$patch" >&2
            exit 1
        fi
        git -C "$source_dir" apply "$patch"
    done
fi

cp "$here/muxy_compat.zig" "$source_dir/src/terminal/muxy_compat.zig"

abis=("$@")
if [[ ${#abis[@]} -eq 0 ]]; then
    abis=(arm64-v8a armeabi-v7a x86_64 x86)
fi
for abi in "${abis[@]}"; do
    case "$abi" in
        arm64-v8a) target=aarch64-linux-android.21 ;;
        armeabi-v7a) target=arm-linux-androideabi.21 ;;
        x86_64) target=x86_64-linux-android.21 ;;
        x86) target=x86-linux-android.21 ;;
        host) target=native ;;
        *) printf 'Unknown native target: %s\n' "$abi" >&2; exit 1 ;;
    esac
    ndk=none
    if [[ "$abi" != host ]]; then
        sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
        ndk="${MUXY_NDK:-${sdk:+$sdk/ndk/$ndk_version}}"
        if [[ ! -f "$ndk/source.properties" ]] || [[ "$(awk -F' = ' '$1 == "Pkg.Revision" {print $2}' "$ndk/source.properties")" != "$ndk_version" ]]; then
            printf 'Android NDK %s is required\n' "$ndk_version" >&2
            exit 1
        fi
    fi
    stamp="$(
        {
            printf '%s\n' "$pin" "$target" "$zig_version" "$ndk_version" "$ndk" ReleaseFast
            sha256sum "$here/build.sh" "$here/muxy_compat.zig"
            git -C "$source_dir" diff --binary HEAD
            if [[ -d "$here/patches" ]]; then
                find "$here/patches" -type f -name '*.patch' -print0 | sort -z | xargs -0 -r sha256sum
            fi
        } | sha256sum | awk '{print $1}'
    )"
    output="$build/$abi"
    if [[ -f "$output/lib/libghostty-vt.a" && -f "$output/stamp" ]] && [[ "$(<"$output/stamp")" == "$stamp" ]]; then
        printf 'Using cached Ghostty archive: %s\n' "$abi"
        continue
    fi
    temporary="$(mktemp -d "$build/$abi.XXXXXX")"
    trap 'rm -rf "$temporary"' EXIT
    printf 'Building Ghostty archive: %s (API 21)\n' "$abi"
    if [[ "$abi" == host ]]; then
        (cd "$source_dir" && "$zig_bin" build --prefix "$temporary" -Demit-lib-vt=true -Doptimize=ReleaseFast)
    else
        (cd "$source_dir" && ANDROID_NDK_HOME="$ndk" "$zig_bin" build --prefix "$temporary" -Demit-lib-vt=true -Doptimize=ReleaseFast -Dtarget="$target")
    fi
    [[ -s "$temporary/lib/libghostty-vt.a" ]] || { printf '%s\n' 'Ghostty archive was not produced' >&2; exit 1; }
    printf '%s\n' "$stamp" > "$temporary/stamp"
    mkdir -p "$output/lib"
    mv "$temporary/lib/libghostty-vt.a" "$output/lib/libghostty-vt.a"
    mv "$temporary/stamp" "$output/stamp"
    if [[ "$abi" == host ]]; then
        for file in "$temporary"/lib/libghostty-vt.so*; do
            [[ -e "$file" || -L "$file" ]] && mv "$file" "$output/lib/"
        done
    fi
    rm -rf "$temporary"
    trap - EXIT
    printf 'Built Ghostty archive: %s\n' "$abi"
done
