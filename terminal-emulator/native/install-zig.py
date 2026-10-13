#!/usr/bin/env python3

import hashlib
import json
import os
from pathlib import Path
import platform
import re
import subprocess
import sys
import tarfile
import tempfile
import urllib.parse
import urllib.request


def install(destination):
    pin = Path(__file__).with_name("PIN.txt").read_text(encoding="utf-8")
    matches = re.findall(r"^zig: ([0-9]+\.[0-9]+\.[0-9]+)$", pin, re.MULTILINE)
    if len(matches) != 1:
        raise RuntimeError("Invalid Zig version pin")
    version = matches[0]
    if destination.exists():
        existing = subprocess.check_output([str(destination / "zig"), "version"], text=True).strip()
        if existing != version:
            raise RuntimeError("Existing Zig installation does not match the pinned version")
        return
    if not hasattr(tarfile, "data_filter"):
        raise RuntimeError("Python with tarfile.data_filter is required")
    if platform.system() != "Linux" or platform.machine() not in ("x86_64", "aarch64"):
        raise RuntimeError("Native build setup requires x86_64 or aarch64 Linux")
    with urllib.request.urlopen("https://ziglang.org/download/index.json", timeout=60) as response:
        data = response.read(8 * 1024 * 1024 + 1)
    if len(data) > 8 * 1024 * 1024:
        raise RuntimeError("Zig release metadata is too large")
    release = json.loads(data)[version][platform.machine() + "-linux"]
    url = release["tarball"]
    checksum = release["shasum"]
    parsed = urllib.parse.urlparse(url)
    if parsed.scheme != "https" or parsed.netloc != "ziglang.org" or parsed.query or parsed.fragment:
        raise RuntimeError("Unexpected Zig archive URL")
    if not re.fullmatch(r"[a-f0-9]{64}", checksum):
        raise RuntimeError("Invalid Zig archive checksum")
    expected_size = int(release["size"])
    if not 0 < expected_size <= 1024 * 1024 * 1024:
        raise RuntimeError("Invalid Zig archive size")
    destination.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="zig-download.", dir=destination.parent) as temporary:
        root = Path(temporary)
        archive = root / "archive.tar.xz"
        digest = hashlib.sha256()
        size = 0
        with urllib.request.urlopen(url, timeout=60) as response, archive.open("xb") as output:
            while chunk := response.read(1024 * 1024):
                size += len(chunk)
                if size > expected_size:
                    raise RuntimeError("Zig archive exceeds its published size")
                digest.update(chunk)
                output.write(chunk)
        if size != expected_size or digest.hexdigest() != checksum:
            raise RuntimeError("Zig archive checksum or size mismatch")
        unpacked = root / "unpacked"
        unpacked.mkdir()
        with tarfile.open(archive, "r:xz") as source:
            source.extractall(unpacked, filter="data")
        entries = list(unpacked.iterdir())
        if len(entries) != 1 or not entries[0].is_dir() or not (entries[0] / "zig").is_file():
            raise RuntimeError("Unexpected Zig archive layout")
        actual = subprocess.check_output([str(entries[0] / "zig"), "version"], text=True).strip()
        if actual != version:
            raise RuntimeError("Downloaded Zig version does not match the pin")
        if destination.exists():
            raise RuntimeError("Zig destination appeared during installation")
        os.rename(entries[0], destination)


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: install-zig.py DESTINATION")
    install(Path(sys.argv[1]).absolute())
