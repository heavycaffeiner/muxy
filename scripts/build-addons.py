#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tarfile


CORE = "4f19a15cdbdce7334a7f2429bf45aa086b698896"
ADDONS = {
    "termux-api": ("0.53.0+muxy.5", "7ae693f540a69e52a93125a1cfad82aacc3ef632", "com.termux.api", 24),
    "termux-styling": ("0.32.1+muxy.5", "92c327f764a30c38d2394278ba942f160bb3affb", "com.termux.styling", 21),
    "termux-boot": ("0.8.1+muxy.5", "89db1e99f986a6f49aa197842413c30c5b19aa00", "com.termux.boot", 21),
    "termux-float": ("0.17.0+muxy.5", "621edec04349deb656e3ec0bafba3dc26fbb2213", "com.termux.window", 24),
    "termux-tasker": ("0.9.0+muxy.5", "ba3f840c40381ac55710466a6f89e4c67704c9a5", "com.termux.tasker", 21),
    "termux-widget": ("0.15.0+muxy.5", "6bb0360ac6ec97d11a75602720b8bf0176723e56", "com.termux.widget", 24),
}
CERTIFICATE = "756768af514d3fa0d1c214d54dabd7b0cd088a1723a22f520b90e8e8545262ce"
ROOT = Path(__file__).resolve().parents[1]


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True, timeout=900)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def apk_name(name, version):
    return f"muxy-addon-{name}-{version.replace('+', '-')}.apk"


def prepare(sources, name, commit):
    target = sources / name
    run("git", "clone", "--no-checkout", "--filter=blob:none",
        f"https://github.com/muxy-terminal/{name}.git", str(target))
    run("git", "checkout", "--detach", commit, cwd=target)
    if run("git", "rev-parse", "HEAD", cwd=target).strip() != commit:
        raise ValueError("Source revision mismatch")
    return target


def archive_sources(destination, sources, label):
    with tarfile.open(destination, "x:gz") as output:
        for repo in ("muxy", *ADDONS):
            source = sources / repo
            process = subprocess.Popen(["git", "archive", "HEAD"], cwd=source, stdout=subprocess.PIPE)
            try:
                with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
                    for member in archive:
                        if member.isfile() or member.issym():
                            path = source / member.name
                            name = f"{label}/sources/{repo}/{member.name}"
                            metadata = output.gettarinfo(str(path), arcname=name)
                            if member.isfile():
                                metadata.mode = member.mode
                                with path.open("rb") as stream:
                                    output.addfile(metadata, stream)
                            else:
                                output.addfile(metadata)
                if process.wait() != 0:
                    raise RuntimeError("Cannot archive addon source")
            finally:
                if process.poll() is None:
                    process.kill()
                    process.wait()
        for relative in ("scripts/build-addons.py", "scripts/verify-addons.py",
                         "docs/en/addons.md", "docs/en/addons-GPL-3.0.txt"):
            output.add(ROOT / relative, arcname=f"{label}/{relative}")
        instructions = sources / "BUILD.txt"
        instructions.write_text(
            "The sources/ directory contains the exact organization source used for these APKs.\n"
            "Keep muxy and the six addons as sibling directories. Build each addon with:\n"
            "./gradlew :app:assembleRelease\n"
            "Use Java 17 or 21, Android SDK 34, 35 and 36, build tools 36.0.0, NDK 29.0.14206865,\n"
            "and Zig 0.16.0. Ghostty source and patches are pinned in muxy/terminal-emulator/native.\n"
            "The terminal native build fetches pinned source and dependencies during the build.\n"
            "Each addon retains its package name, SDK requirements, and upstream licenses.\n"
            "Release signing requires your own keystore. Private keys are not included.\n",
            encoding="utf-8")
        output.add(instructions, arcname=f"{label}/BUILD.txt")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", type=Path, help="New build directory")
    parser.add_argument("--label", required=True, help="Addon distribution label, such as addons-0.119.8")
    parser.add_argument("--keystore", required=True, type=Path)
    parser.add_argument("--password-file", required=True, type=Path)
    parser.add_argument("--alias", default="muxy")
    args = parser.parse_args()
    if not re.fullmatch(r"addons-[0-9]+(?:\.[0-9]+){2}", args.label):
        parser.error("Invalid addon distribution label")
    if not args.keystore.is_file() or not args.password_file.is_file():
        parser.error("Signing files are missing")
    destination = args.destination.resolve()
    destination.mkdir(parents=True, exist_ok=False)
    sources = destination / "sources"
    sources.mkdir()
    artifacts = destination / "artifacts"
    artifacts.mkdir()
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/36.0.0"
    prepare(sources, "muxy", CORE)
    records = []
    for name, (version, commit, package, minimum) in ADDONS.items():
        source = prepare(sources, name, commit)
        pin = source / "MUXY_CORE.txt"
        if pin.exists() and pin.read_text().strip() != CORE:
            raise ValueError("Addon muxy revision mismatch")
        log = destination / (name + "-build.log")
        with log.open("x") as output:
            subprocess.run(["./gradlew", ":app:assembleRelease"], cwd=source, stdout=output,
                           stderr=subprocess.STDOUT, check=True, timeout=1800)
        candidates = list((source / "app/build/outputs/apk/release").glob("*.apk"))
        if len(candidates) != 1:
            raise ValueError("Unexpected APK outputs: " + name)
        target = artifacts / apk_name(name, version)
        run(str(tools / "apksigner"), "sign", "--ks", str(args.keystore.resolve()),
            "--ks-key-alias", args.alias, "--ks-pass", "file:" + str(args.password_file.resolve()),
            "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
            "--v4-signing-enabled", "false", "--out", str(target), str(candidates[0]))
        records.append({"file": target.name, "version": version, "package": package, "min_sdk": minimum,
                        "repository": f"https://github.com/muxy-terminal/{name}", "commit": commit,
                        "sha256": digest(target), "bytes": target.stat().st_size})
        print(target.name, flush=True)
    archive = artifacts / ("muxy-" + args.label + "-sources.tar.gz")
    archive_sources(archive, sources, "muxy-" + args.label)
    (artifacts / "ADDONS.json").write_text(json.dumps({"label": args.label, "certificate_sha256": CERTIFICATE,
        "core_commit": CORE, "core_repository": "https://github.com/muxy-terminal/muxy", "addons": records,
        "sources": {"file": archive.name, "sha256": digest(archive)}}, indent=2) + "\n", encoding="utf-8")
    shutil.copyfile(ROOT / "docs/en/addons-GPL-3.0.txt", artifacts / "GPL-3.0.txt")
    with (artifacts / "ADDON-SHA256SUMS").open("x") as output:
        for path in sorted(artifacts.iterdir()):
            if path.name != "ADDON-SHA256SUMS":
                output.write(digest(path) + "  " + path.name + "\n")
    subprocess.run(["python3", str(ROOT / "scripts/verify-addons.py"), str(artifacts)], check=True)


if __name__ == "__main__":
    main()
