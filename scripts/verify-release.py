#!/usr/bin/env python3
import hashlib
import io
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

BOOTSTRAPS = {
    "apt-android-7": {
        "arm64-v8a": "ea2aeba8819e517db711f8c32369e89e7c52cee73e07930ff91185e1ab93f4f3",
        "armeabi-v7a": "a38f4d3b2f735f83be2bf54eff463e86dc32a3e2f9f861c1557c4378d249c018",
        "x86": "f5bc0b025b9f3b420b5fcaeefc064f888f5f22a0d6fd7090f4aac0c33eb3555b",
        "x86_64": "b7fd0f2e3a4de534be3144f9f91acc768630fc463eaf134ab2e64c545e834f7a",
    },
    "apt-android-5": {
        "arm64-v8a": "913609d439415c828c5640be1b0561467e539cb1c7080662decaaca2fb4820e7",
        "armeabi-v7a": "26bfb45304c946170db69108e5eb6e3641aad751406ce106c80df80cad2eccf8",
        "x86": "46dcfeb5eef67ba765498db9fe4c50dc4690805139aa0dd141a9d8ee0693cd27",
        "x86_64": "615b590679ee6cd885b7fd2ff9473c845e920f9b422f790bb158c63fe42b8481",
    },
}
TRIPLES = {
    "arm64-v8a": "aarch64-linux-android",
    "armeabi-v7a": "arm-linux-androideabi",
    "x86": "i686-linux-android",
    "x86_64": "x86_64-linux-android",
}


def command(*args):
    return subprocess.check_output(args, text=True, timeout=120)


def symbols(readelf, library):
    result = []
    for line in command(readelf, "--dyn-syms", "--wide", library).splitlines():
        fields = line.split()
        if len(fields) >= 8 and re.fullmatch(r"[0-9]+:", fields[0]):
            result.append((fields[4], fields[6], fields[7].split("@")[0]))
    return result


def bootstrap_hash(library):
    # The ZIP has an ELF prefix and trailing section tables.
    with zipfile.ZipFile(io.BytesIO(library)) as archive:
        start = min(info.header_offset for info in archive.infolist())
        end = library.rfind(b"PK\x05\x06") + 22 + len(archive.comment)
    if not 0 <= start < end <= len(library):
        raise ValueError("Invalid embedded bootstrap bounds")
    return hashlib.sha256(library[start:end]).hexdigest()


def verify(root, version, certificate):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", version):
        raise ValueError("Invalid release version")
    if not re.fullmatch(r"[a-f0-9]{64}", certificate):
        raise ValueError("Invalid certificate fingerprint")
    expected = {
        f"muxy-{version}-{variant}-{abi}.apk": (variant, abi)
        for variant in BOOTSTRAPS
        for abi in (*TRIPLES, "universal")
    }
    seen = set()
    for line in (root / "SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([a-f0-9]{64})  ([A-Za-z0-9._-]+\.apk)", line)
        if not match or match[2] not in expected or match[2] in seen:
            raise ValueError("Invalid release checksum entry")
        seen.add(match[2])
        with (root / match[2]).open("rb") as artifact:
            digest = hashlib.file_digest(artifact, "sha256").hexdigest()
        if digest != match[1]:
            raise ValueError("Release checksum mismatch: " + match[2])
    if seen != set(expected) or {path.name for path in root.glob("*.apk")} != seen:
        raise ValueError("Incomplete or unexpected release APK matrix")

    sdk = Path(os.environ["ANDROID_HOME"])
    tools = sdk / "build-tools/36.0.0"
    properties = Path(__file__).resolve().parents[1] / "gradle.properties"
    ndk_version = re.search(r"^ndkVersion=([0-9.]+)$", properties.read_text(), re.MULTILINE)
    if ndk_version is None:
        raise ValueError("Missing declared NDK version")
    toolchain = sdk / "ndk" / ndk_version[1] / "toolchains/llvm/prebuilt/linux-x86_64"
    readelf = toolchain / "bin/llvm-readelf"
    exports = {}
    for abi, triple in TRIPLES.items():
        libraries = toolchain / "sysroot/usr/lib" / triple / "21"
        exports[abi] = {
            name for dep in ("libc.so", "libdl.so", "libm.so", "liblog.so")
            for bind, section, name in symbols(readelf, libraries / dep)
            if section != "UND"
        }

    with tempfile.TemporaryDirectory(prefix="muxy-release-check-") as temporary:
        library_path = Path(temporary) / "library.so"
        for filename, (variant, abi) in sorted(expected.items()):
            apk = root / filename
            for maximum in ("21", "36"):
                verified = command(tools / "apksigner", "verify", "--min-sdk-version", "21",
                                   "--max-sdk-version", maximum, "--print-certs", apk)
                digests = {line.rsplit(": ", 1)[-1] for line in verified.splitlines()
                           if "certificate SHA-256 digest: " in line}
                if digests != {certificate}:
                    raise ValueError("Release signer mismatch: " + filename)
            badging = command(tools / "aapt", "dump", "badging", apk)
            if (f"versionName='{version}'" not in badging or "package: name='com.termux'" not in badging
                    or "sdkVersion:'21'" not in badging or "application-debuggable" in badging):
                raise ValueError("Invalid release manifest: " + filename)
            abis = set(TRIPLES) if abi == "universal" else {abi}
            with zipfile.ZipFile(apk) as archive:
                packaged = {name.split("/")[1] for name in archive.namelist() if name.startswith("lib/")}
                if packaged != abis:
                    raise ValueError("Invalid APK ABI set: " + filename)
                for packaged_abi in abis:
                    for module in ("libtermux-bootstrap.so", "libtermux.so", "libmuxy_terminal.so", "liblocal-socket.so"):
                        library = archive.read(f"lib/{packaged_abi}/{module}")
                        if module == "libtermux-bootstrap.so" and bootstrap_hash(library) != BOOTSTRAPS[variant][packaged_abi]:
                            raise ValueError("Wrong embedded bootstrap: " + filename)
                        library_path.write_bytes(library)
                        loads = [line.split() for line in command(readelf, "--program-headers", "--wide", library_path).splitlines()
                                 if line.strip().startswith("LOAD ")]
                        if not loads or any(int(fields[-1], 16) < 16384 for fields in loads):
                            raise ValueError(f"Native ELF alignment below 16 KiB: {filename} ({module})")
                        missing = {name for bind, section, name in symbols(readelf, library_path)
                                   if section == "UND" and bind != "WEAK" and name not in exports[packaged_abi]}
                        if missing:
                            raise ValueError("Unresolved API 21 imports: " + repr(sorted(missing)))
            print(filename + ": signatures, bootstrap, API 21 imports, and 16 KiB alignment passed")


if __name__ == "__main__":
    if len(sys.argv) != 4:
        raise SystemExit("Usage: verify-release.py RELEASE_DIR VERSION CERTIFICATE_SHA256")
    verify(Path(sys.argv[1]), sys.argv[2], sys.argv[3])
