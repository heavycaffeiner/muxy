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


CORE = "7bceab88e2272f961d1b94ef736f1a9e20173247"
ADDONS = {
    "termux-api": ("0.53.0", "6c8eaa223bcc387e6b28146d340acb20712aa3e7", "com.termux.api", 24),
    "termux-styling": ("0.32.1", "82dcd3243349d1c5de9a9055b429331d1a01b388", "com.termux.styling", 21),
    "termux-boot": ("0.8.1", "613dc9893f672313e2c572c0089ece7475e04f5b", "com.termux.boot", 21),
    "termux-float": ("0.17.0", "b435f4b671fd1702817e889ef680850a9934a05f", "com.termux.window", 24),
    "termux-tasker": ("0.9.0", "f08f83b5830c1273916d96d1ebcc70e14e520f1b", "com.termux.tasker", 21),
    "termux-widget": ("0.15.0", "289c6296ed9013ada91742726da5b7230ee74f70", "com.termux.widget", 24),
}
CERTIFICATE = "756768af514d3fa0d1c214d54dabd7b0cd088a1723a22f520b90e8e8545262ce"
ROOT = Path(__file__).resolve().parents[1]


def run(*args, cwd=None):
    return subprocess.check_output(args, cwd=cwd, text=True, timeout=900)


def digest(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def prepare(sources, name, commit):
    target = sources / name
    run("git", "clone", "--no-checkout", "--filter=blob:none",
        f"https://github.com/termux/{name}.git", str(target))
    run("git", "checkout", "--detach", commit, cwd=target)
    if run("git", "rev-parse", "HEAD", cwd=target).strip() != commit:
        raise ValueError("Source revision mismatch")
    patch = ROOT / "scripts/addon-patches" / (name + ".patch")
    if patch.exists():
        run("git", "apply", "--check", str(patch), cwd=target)
        run("git", "apply", str(patch), cwd=target)
    return target


def archive_sources(destination, sources, label):
    with tarfile.open(destination, "x:gz") as output:
        for repo in ("termux-app", *ADDONS):
            source = sources / repo
            process = subprocess.Popen(["git", "archive", "HEAD"], cwd=source, stdout=subprocess.PIPE)
            try:
                with tarfile.open(fileobj=process.stdout, mode="r|") as archive:
                    for member in archive:
                        path = source / member.name
                        name = f"{label}/sources/{repo}/{member.name}"
                        if member.isfile() or member.issym():
                            # Include patched working files, not build output or signing material.
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
        for relative in ("scripts/build-addons.py", "scripts/verify-addons.py", "scripts/addon-patches",
                         "docs/en/addons.md", "docs/en/addons-GPL-3.0.txt"):
            output.add(ROOT / relative, arcname=f"{label}/{relative}")
        instructions = sources / "BUILD.txt"
        instructions.write_text(
            "The sources/ directory contains the exact patched source used for these APKs.\n"
            "Build each addon from sources/termux-NAME using its Gradle wrapper:\n"
            "./gradlew :app:assembleRelease -Pandroid.nonTransitiveRClass=false -Pandroid.nonFinalResIds=false\n"
            "Use Java 17 or 21, Android SDK 34 and 35, build tools 36.0.0, and NDK 29.0.14206865.\n"
            "Each addon retains its upstream package name, version, SDK requirements, and license.\n"
            "Release signing requires your own keystore. Private keys are not included.\n",
            encoding="utf-8")
        output.add(instructions, arcname=f"{label}/BUILD.txt")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("destination", type=Path, help="New build directory")
    parser.add_argument("--label", required=True, help="Addon distribution label, such as addons-2026.10.15")
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
    prepare(sources, "termux-app", CORE)
    records = []
    for name, (version, commit, package, minimum) in ADDONS.items():
        source = prepare(sources, name, commit)
        log = destination / (name + "-build.log")
        with log.open("x") as output:
            subprocess.run(["./gradlew", ":app:assembleRelease", "-Pandroid.nonTransitiveRClass=false",
                            "-Pandroid.nonFinalResIds=false"], cwd=source, stdout=output,
                           stderr=subprocess.STDOUT, check=True, timeout=900)
        candidates = list((source / "app/build/outputs/apk/release").glob("*.apk"))
        if len(candidates) != 1:
            raise ValueError("Unexpected APK outputs: " + name)
        target = artifacts / f"muxy-addon-{name}-{version}.apk"
        run(str(tools / "apksigner"), "sign", "--ks", str(args.keystore.resolve()),
            "--ks-key-alias", args.alias, "--ks-pass", "file:" + str(args.password_file.resolve()),
            "--v1-signing-enabled", "true", "--v2-signing-enabled", "true", "--v3-signing-enabled", "true",
            "--v4-signing-enabled", "false",
            "--out", str(target), str(candidates[0]))
        records.append({"file": target.name, "version": version, "package": package, "min_sdk": minimum,
                        "repository": f"https://github.com/termux/{name}", "commit": commit,
                        "sha256": digest(target), "bytes": target.stat().st_size})
        print(target.name, flush=True)
    archive = artifacts / ("muxy-" + args.label + "-sources.tar.gz")
    archive_sources(archive, sources, "muxy-" + args.label)
    (artifacts / "ADDONS.json").write_text(json.dumps({"label": args.label, "certificate_sha256": CERTIFICATE,
        "core_commit": CORE, "addons": records, "sources": {"file": archive.name, "sha256": digest(archive)}},
        indent=2) + "\n", encoding="utf-8")
    shutil.copyfile(ROOT / "docs/en/addons-GPL-3.0.txt", artifacts / "GPL-3.0.txt")
    with (artifacts / "ADDON-SHA256SUMS").open("x") as output:
        for path in sorted(artifacts.iterdir()):
            if path.name != "ADDON-SHA256SUMS":
                output.write(digest(path) + "  " + path.name + "\n")
    subprocess.run(["python3", str(ROOT / "scripts/verify-addons.py"), str(artifacts)], check=True)


if __name__ == "__main__":
    main()
