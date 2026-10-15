#!/usr/bin/env python3
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tarfile
import tempfile
import zipfile

from importlib.util import module_from_spec, spec_from_file_location

spec = spec_from_file_location("addon_build", Path(__file__).with_name("build-addons.py"))
build = module_from_spec(spec)
spec.loader.exec_module(build)


def command(*args):
    return subprocess.check_output(args, text=True, timeout=120)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def verify(root):
    sdk = Path(os.environ["ANDROID_HOME"])
    tools = sdk / "build-tools/36.0.0"
    readelf = sdk / "ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin/llvm-readelf"
    seen = set()
    for line in (root / "ADDON-SHA256SUMS").read_text().splitlines():
        match = re.fullmatch(r"([a-f0-9]{64})  ([A-Za-z0-9._-]+)", line)
        if not match or match[2] in seen or match[2] in (".", ".."):
            raise ValueError("Invalid addon checksum entry")
        path = root / match[2]
        if not path.is_file() or path.is_symlink() or digest(path) != match[1]:
            raise ValueError("Addon checksum mismatch: " + match[2])
        seen.add(match[2])
    actual = {path.name for path in root.iterdir() if path.is_file()}
    if actual != seen | {"ADDON-SHA256SUMS"}:
        raise ValueError("Unexpected or missing addon artifacts")
    manifest = json.loads((root / "ADDONS.json").read_text())
    if (manifest["certificate_sha256"] != build.CERTIFICATE or manifest["core_commit"] != build.CORE
            or manifest["core_repository"] != "https://github.com/muxy-terminal/muxy"):
        raise ValueError("Unexpected addon distribution provenance")
    expected = {build.apk_name(name, values[0]) for name, values in build.ADDONS.items()}
    if {path.name for path in root.glob("*.apk")} != expected:
        raise ValueError("Incomplete addon matrix")
    records = {record["file"]: record for record in manifest["addons"]}
    if len(records) != len(expected) or set(records) != expected:
        raise ValueError("Invalid addon manifest")
    for name, (version, commit, package, minimum) in build.ADDONS.items():
        filename = build.apk_name(name, version)
        apk = root / filename
        record = records[filename]
        if (record["sha256"] != digest(apk) or record["commit"] != commit
                or record["repository"] != f"https://github.com/muxy-terminal/{name}"
                or record["version"] != version or record["package"] != package
                or record["min_sdk"] != minimum or record["bytes"] != apk.stat().st_size):
            raise ValueError("Addon manifest mismatch")
        verified = command(tools / "apksigner", "verify", "--min-sdk-version", str(minimum),
                           "--print-certs", apk)
        certificates = {line.rsplit(": ", 1)[-1] for line in verified.splitlines()
                        if "certificate SHA-256 digest: " in line}
        if certificates != {build.CERTIFICATE}:
            raise ValueError("Addon signer mismatch")
        badging = command(tools / "aapt", "dump", "badging", apk)
        if (f"package: name='{package}'" not in badging or f"versionName='{version}'" not in badging
                or f"sdkVersion:'{minimum}'" not in badging or "application-debuggable" in badging):
            raise ValueError("Invalid addon manifest: " + filename)
        xml = command(tools / "aapt", "dump", "xmltree", apk, "AndroidManifest.xml")
        if not re.search(r'android:sharedUserId[^\n]*="com\.termux"', xml):
            raise ValueError("Missing Termux shared UID: " + filename)
        with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temporary:
            native_core = {member.split('/')[1] for member in archive.namelist()
                           if member.startswith('lib/') and member.endswith('/libmuxy_terminal.so')}
            if name == "termux-float" and native_core != {"arm64-v8a", "armeabi-v7a", "x86", "x86_64"}:
                raise ValueError("Float does not package the four-ABI muxy native core")
            if name != "termux-float" and native_core:
                raise ValueError("Unexpected terminal core in a non-terminal addon")
            for member in archive.namelist():
                if not member.startswith("lib/") or not member.endswith(".so"):
                    continue
                library = Path(temporary) / "library.so"
                library.write_bytes(archive.read(member))
                loads = [line.split() for line in command(readelf, "--program-headers", "--wide", library).splitlines()
                         if line.strip().startswith("LOAD ")]
                if not loads or any(int(fields[-1], 16) < 16384 for fields in loads):
                    raise ValueError("Addon native alignment below 16 KiB: " + member)
        print(filename + ": checksum, signer, package, SDK, shared UID, and native alignment passed")
    sources = manifest["sources"]
    if (not re.fullmatch(r"muxy-addons-[0-9]+(?:\.[0-9]+){2}-sources\.tar\.gz", sources["file"])
            or sources["file"] not in seen or digest(root / sources["file"]) != sources["sha256"]):
        raise ValueError("Missing or invalid corresponding source archive")
    if "GPL-3.0.txt" not in seen:
        raise ValueError("Missing addon license")
    prefix = "muxy-" + manifest["label"]
    if sources["file"] != prefix + "-sources.tar.gz":
        raise ValueError("Source archive label mismatch")
    with tarfile.open(root / sources["file"]) as archive:
        members = archive.getmembers()
        for member in members:
            path = Path(member.name)
            if (path.is_absolute() or ".." in path.parts or not path.parts or path.parts[0] != prefix
                    or ".git" in path.parts or "build" in path.parts
                    or path.suffix in (".p12", ".pass")
                    or path.suffix == ".jks" and path.name != "testkey_untrusted.jks"):
                raise ValueError("Unexpected corresponding-source archive entry")
        required = [prefix + "/BUILD.txt", prefix + "/docs/en/addons-GPL-3.0.txt"]
        required.extend(prefix + "/sources/" + repo + "/gradlew" for repo in ("muxy", *build.ADDONS))
        names = {member.name for member in members}
        if not set(required) <= names:
            raise ValueError("Incomplete corresponding-source archive")
        for repo in ("termux-api", "termux-float", "termux-tasker", "termux-widget"):
            member = archive.extractfile(prefix + "/sources/" + repo + "/MUXY_CORE.txt")
            if member is None or member.read(128).decode("ascii").strip() != build.CORE:
                raise ValueError("Addon source does not pin the recorded muxy core")
        if prefix + "/sources/muxy/terminal-emulator/native/PIN.txt" not in names:
            raise ValueError("Missing native terminal source pin")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: verify-addons.py ARTIFACT_DIR")
    verify(Path(sys.argv[1]))
