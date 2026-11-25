#!/usr/bin/env python3
"""Locked audio/video-cover native build, evidence recording, and candidate assembly (Python >= 3.10)."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
CAP = json.loads((ROOT / "capabilities.json").read_text(encoding="utf-8"))
LOCK = json.loads((ROOT / "sources.lock.json").read_text(encoding="utf-8"))
PROVENANCE = "source-built-audio-candidate"
MODULES = tuple(CAP["libraries"])


def fail(message):
    raise RuntimeError(message)


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def dump(path, obj):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(obj, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def fetch(key, cache):
    entry = LOCK["sources"][key]
    path = cache / entry["file"]
    cache.mkdir(parents=True, exist_ok=True)
    if not path.exists():
        temp = path.with_suffix(path.suffix + ".part")
        print("Downloading locked source:", entry["url"], flush=True)
        with urllib.request.urlopen(entry["url"], timeout=120) as source, temp.open("wb") as out:
            shutil.copyfileobj(source, out)
        if sha(temp) != entry["sha256"]:
            fail("SHA256 mismatch: " + key)
        temp.replace(path)
    if sha(path) != entry["sha256"]:
        fail("Cached file has wrong SHA256: " + str(path))
    return path


def extract(key, archive, source_root):
    dest = source_root / LOCK["sources"][key]["root"]
    if dest.exists():
        fail("Build source already exists; use a fresh --work directory: " + str(dest))
    with tarfile.open(archive) as tar:
        # Source archives are locked; still prohibit escaping paths/link destinations.
        for member in tar.getmembers():
            target = (source_root / member.name).resolve()
            if not target.is_relative_to(source_root.resolve()):
                fail("Unsafe archive member: " + member.name)
            if member.ischr() or member.isblk() or member.isfifo():
                fail("Special file in source archive: " + member.name)
            if member.issym() or member.islnk():
                base = (source_root / member.name).parent if member.issym() else source_root
                if not (base / member.linkname).resolve().is_relative_to(source_root.resolve()):
                    fail("Unsafe source archive link: " + member.name)
        if sys.version_info >= (3, 12):
            tar.extractall(source_root, filter="data")
        else:
            tar.extractall(source_root)
    return dest


def options():
    flags = list(CAP["configureBase"])
    for group, values in CAP["components"].items():
        singular = group[:-1]  # decoders -> decoder, protocols -> protocol
        flags.append("--enable-" + singular + "=" + ",".join(values))
    return flags


def validate_source(source):
    # These registries are from the exact release, not a website's current list.
    registries = {"decoders": ("libavcodec/allcodecs.c", "FFCodec", "decoder"),
                  "demuxers": ("libavformat/allformats.c", "FFInputFormat", "demuxer"),
                  "parsers": ("libavcodec/parsers.c", "FFCodecParser", "parser"),
                  "protocols": ("libavformat/protocols.c", "URLProtocol", "protocol")}
    for group, (file, kind, suffix) in registries.items():
        text = (source / file).read_text(encoding="utf-8")
        found = set(re.findall(r"extern const " + kind + r"\s+ff_(\w+)_" + suffix + r";", text))
        missing = set(CAP["components"][group]) - found
        if missing:
            fail(f"Unknown FFmpeg {CAP['ffmpegVersion']} {group}: {sorted(missing)}")
    for module, expected in CAP["libraries"].items():
        header = source / ("lib" + module) / ("version.h" if module == "avutil" else "version_major.h")
        declaration = re.search(r"^#define LIB" + module.upper() + r"_VERSION_MAJOR\s+(\d+)\b",
                                header.read_text(encoding="utf-8"), re.M)
        if declaration is None or int(declaration.group(1)) != expected:
            fail("Library ABI major differs from capabilities: " + module)
    configure = (source / "configure").read_text(encoding="utf-8")
    for flag in CAP["configureBase"]:
        key = flag.split("=", 1)[0][2:]
        if key not in configure:
            # Component libraries are generated via LIBRARY_LIST; all supported by configure.
            word = key.removeprefix("enable-").removeprefix("disable-").replace("-", "_")
            if not re.search(r"\b" + re.escape(word) + r"\b", configure):
                fail("Configure option not found in release source: " + flag)
    if (source / "RELEASE").read_text().strip() != CAP["ffmpegVersion"]:
        fail("Wrong FFmpeg release source")
    print("Exact-release registry and configure-name validation passed; no native compilation implied.")


def selected_patches(profile=None):
    return [entry for entry in LOCK.get("patches", [])
            if entry.get("profile") == profile or entry.get("enabledByDefault", False)]


def apply_patches(source, profile=None):
    # Lock order is significant: optional identity changes precede the mandatory DTLS build guard.
    # Minimal deterministic unified-diff application. No fuzzy patching and no host patch executable.
    for entry in selected_patches(profile):
        hashes = entry.get("profileHashes", {}).get(profile, entry)
        patch = ROOT / entry["file"]
        target = source / entry["target"]
        if sha(patch) != entry["sha256"] or sha(target) != hashes["upstreamSha256"]:
            fail("Patch/source hash mismatch: " + entry["file"])
        original = target.read_text(encoding="utf-8").splitlines(keepends=True)
        output = []
        cursor = 0
        active = False
        for line in patch.read_text(encoding="utf-8").splitlines(keepends=True):
            if line.startswith("@@ "):
                match = re.match(r"@@ -(\d+)(?:,\d+)? \+\d+(?:,\d+)? @@", line)
                if not match:
                    fail("Invalid patch hunk")
                start = int(match.group(1)) - 1
                if start < cursor:
                    fail("Overlapping patch hunk")
                output.extend(original[cursor:start])
                cursor = start
                active = True
            elif active and line[0] in " +-":
                if line[0] in " -":
                    if cursor >= len(original) or original[cursor] != line[1:]:
                        fail("Patch context mismatch")
                    cursor += 1
                if line[0] in " +":
                    output.append(line[1:])
        output.extend(original[cursor:])
        data = "".join(output).encode()
        if hashlib.sha256(data).hexdigest() != hashes["patchedSha256"]:
            fail("Patched source hash mismatch")
        target.write_bytes(data)
        print("Applied locked source patch:", entry["file"], flush=True)


def audit_config(build):
    text = (build / "ffbuild/config.mak").read_text()
    return audit_components(set(re.findall(r"^CONFIG_([A-Z0-9_]+)=yes$", text, re.M)))


def audit_components(enabled):
    enabled = set(enabled)
    for group, names in CAP["components"].items():
        singular = group[:-1].upper()
        missing = {name for name in names if name.upper() + "_" + singular not in enabled}
        if missing:
            fail("Requested capabilities silently omitted: " + str(sorted(missing)))
    forbidden = {"AVDEVICE", "AVFILTER", "GPL", "NONFREE", "FFMPEG", "FFPROBE", "FFPLAY"}
    bad = enabled & forbidden
    bad |= {name for name in enabled if name.endswith(("_ENCODER", "_MUXER", "_INDEV", "_OUTDEV", "_FILTER", "_HWACCEL"))}
    if bad:
        fail("Unexpected enabled output/device components: " + str(sorted(bad)))
    extra_decoders = {name for name in enabled if name.endswith("_DECODER")} - {name.upper() + "_DECODER" for name in CAP["components"]["decoders"]}
    if extra_decoders:
        fail("Unexpected decoders outside the audio/video-cover whitelist: " + str(sorted(extra_decoders)))
    for name in [*(module.upper() for module in MODULES), "NETWORK", "OPENSSL", "ZLIB", "VERSION3"]:
        if name not in enabled:
            fail("Required library/dependency missing: " + name)
    return sorted(enabled)


def java_entries(jar):
    with zipfile.ZipFile(jar) as z:
        for name in sorted(z.namelist()):
            if name.endswith("/") or name == "META-INF/MANIFEST.MF":
                continue
            if re.search(r"META-INF/.*\.(SF|RSA|DSA)$", name) or name.endswith("module-info.class"):
                continue
            if name.startswith("META-INF/versions/"):
                continue
            if name.startswith("META-INF/native-image/") and any("/jni" + mod + "/" in name for mod in ("avdevice", "avfilter")):
                continue
            if any(name.startswith("org/bytedeco/ffmpeg/" + part + "/") for part in ("avdevice", "avfilter")):
                continue
            if any(name == f"org/bytedeco/ffmpeg/{part}/{mod}.class"
                   for part in ("global", "presets") for mod in ("avdevice", "avfilter")):
                continue
            yield name, z.read(name)


def license_entries():
    # Stage tracked, exact upstream license bytes. No sibling checkout or source tree is needed.
    entry = LOCK["licenseInventory"]
    manifest_path = ROOT / entry["file"]
    if sha(manifest_path) != entry["sha256"]:
        fail("License inventory hash mismatch")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    directory = manifest_path.parent
    prefix = entry["destination"]
    seen = set()
    for record in manifest["files"]:
        relative = Path(record["file"])
        path = (directory / relative).resolve()
        if relative.is_absolute() or not path.is_relative_to(directory.resolve()) or record["file"] in seen:
            fail("Invalid/duplicate license inventory path")
        seen.add(record["file"])
        data = path.read_bytes()
        if len(data) != record["bytes"] or hashlib.sha256(data).hexdigest() != record["sha256"]:
            fail("License text hash/size mismatch: " + record["file"])
        yield prefix + record["file"], data
    yield prefix + "licenses.lock.json", manifest_path.read_bytes()
    yield prefix + "README.md", (directory / "README.md").read_bytes()


def write_jar(path, entries):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        for name, data in sorted(entries.items()):
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            jar.writestr(info, data)


def expected_native_entries(platform):
    prefix = "" if platform.startswith("windows") else "lib"
    ext = ".dll" if platform.startswith("windows") else ".dylib" if platform.startswith("macosx") else ".so"
    ffmpeg = "org/bytedeco/ffmpeg/" + platform + "/"
    entries = {ffmpeg + prefix + "jni" + module + ext for module in MODULES}
    for module, major in CAP["libraries"].items():
        if platform.startswith("windows"):
            filename = f"{module}-{major}.dll"
        elif platform.startswith("macosx"):
            filename = f"lib{module}.{major}.dylib"
        else:
            filename = f"lib{module}.so" + ("" if platform.startswith("android") else f".{major}")
        entries.add(ffmpeg + filename)
    if platform.startswith("windows"):
        entries.add(ffmpeg + "libwinpthread-1.dll")
    entries.add("org/bytedeco/javacpp/" + platform + "/" + prefix + "jnijavacpp" + ext)
    return entries


def package_platform(work, platform, tls_hardening=False):
    stage = work / "stage"
    prefix = work / "install"
    native = stage / "org/bytedeco/ffmpeg" / platform
    runtime = stage / "org/bytedeco/javacpp" / platform
    native.mkdir(parents=True, exist_ok=True)
    runtime.mkdir(parents=True, exist_ok=True)
    if platform.startswith("windows"):
        names = {f"{mod}-{version}.dll" for mod, version in CAP["libraries"].items()}
        names |= {"libwinpthread-1.dll"}  # actual preset preload; MinGW runtime, copied from toolchain
        ext = ".dll"
    elif platform.startswith("macosx"):
        names = {f"lib{mod}.{version}.dylib" for mod, version in CAP["libraries"].items()}
        ext = ".dylib"
    else:
        names = {f"lib{mod}.so" + ("" if platform.startswith("android") else f".{version}")
                 for mod, version in CAP["libraries"].items()}
        ext = ".so"
    for name in sorted(names):
        candidates = [prefix / "lib" / name, prefix / "bin" / name, work / "jni" / name]
        source = next((path for path in candidates if path.is_file()), None)
        if source is None:
            fail("Native library is missing: " + name)
        shutil.copyfile(source, native / name)  # dereference SONAME aliases, keep loader-visible basename
    jni_prefix = "" if platform.startswith("windows") else "lib"
    for mod in MODULES:
        name = jni_prefix + "jni" + mod + ext
        shutil.copyfile(work / "jni" / name, native / name)
    name = jni_prefix + "jnijavacpp" + ext
    shutil.copyfile(work / "jni-runtime" / name, runtime / name)
    report_dir = stage / "META-INF/muonium/ffmpeg/platforms"
    enabled = audit_config(work / "ffmpeg-build")
    files = {str(path.relative_to(stage)).replace(os.sep, "/"): {"sha256": sha(path), "bytes": path.stat().st_size}
             for path in sorted(stage.rglob("*")) if path.is_file()}
    if set(files) != expected_native_entries(platform):
        fail("Native resource layout is incomplete or contains unexpected components")
    report = {"schemaVersion": 1, "platform": platform, "provenance": PROVENANCE,
              "ffmpegVersion": CAP["ffmpegVersion"], "javaCppVersion": CAP["javaCppVersion"],
              "nativeCompilation": "completed", "runtimeSmoke": "not-run", "releaseReady": False,
              "tlsHardening": "optional-enabled-unverified" if tls_hardening else "deferred",
              "tlsIdentityTests": "not-run", "tlsSecurityProductionVerified": False,
              "patchesApplied": [p["file"] for p in selected_patches("tls-hardening" if tls_hardening else None)],
              "capabilitiesSha256": sha(ROOT / "capabilities.json"), "sourcesLockSha256": sha(ROOT / "sources.lock.json"),
              "files": files, "configuredComponents": enabled,
              "libraries": {module: {"major": major, "provenance": PROVENANCE,
                                     "nativeCompilation": "completed",
                                     "jniProvenance": "source-built-against-locked-java"}
                            for module, major in CAP["libraries"].items()},
              "videoCoverRuntimeSmoke": "not-run",
              "androidDeviceVerification": "not-run" if platform.startswith("android") else "not-applicable",
              "requiredBeforeRelease": ["per-platform JNI/media runtime smoke", "TLS/stream failure and cancellation checks",
                                        "Android launcher/device loading and 16 KiB page verification",
                                        "single final mod JAR dependency closure / size / license-source audit"]}
    dump(report_dir / (platform + ".json"), report)
    entries = {str(path.relative_to(stage)).replace(os.sep, "/"): path.read_bytes()
               for path in stage.rglob("*") if path.is_file()}
    jar = work / ("ffmpeg-audio-candidate-" + platform + ".jar")
    write_jar(jar, entries)
    print(f"Native candidate: {jar} ({jar.stat().st_size} bytes); runtime verification pending")
    return jar


def build(args):
    # All three source targets use a Linux host, including Windows/Android cross builds.
    # Do not pass Windows drive paths to bash or guess a WSL/MSYS path translation.
    if not sys.platform.startswith("linux"):
        fail("Source builds require a Linux host with native Linux --work/--cache/JDK/NDK paths")
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    for name in ("install", "ffmpeg-build", "jni", "stage"):
        if (work / name).exists():
            fail("Build directory is not fresh: " + str(work / name))
    cache = args.cache.resolve()
    downloaded = {key: fetch(key, cache) for key in LOCK["sources"]}
    sources = work / "sources"
    sources.mkdir(exist_ok=True)
    for key in ("ffmpeg", "openssl", "zlib"):
        extract(key, downloaded[key], sources)
    validate_source(sources / LOCK["sources"]["ffmpeg"]["root"])
    apply_patches(sources / LOCK["sources"]["ffmpeg"]["root"], "tls-hardening" if args.tls_hardening else None)
    (work / "configure-flags.txt").write_text("\n".join(options()) + "\n")
    dump(work / "sources.lock.json", LOCK)
    dump(work / "capabilities.json", CAP)
    env = os.environ.copy()
    env.update({"NATIVE_WORK": str(work), "NATIVE_PLATFORM": args.platform,
                "NATIVE_ROOT": str(ROOT), "JAVACPP_JAR": str(downloaded["javacpp"]),
                "FFMPEG_JAVA_JAR": str(downloaded["ffmpegJava"]),
                "ANDROID_API": str(LOCK["android"]["api"]),
                "ANDROID_NDK_REVISION": LOCK["android"]["ndkRevision"]})
    # Toolchain sources/versions are kept in build.log/config logs by the CI artifact step.
    subprocess.run(["bash", str(ROOT / "build-native.sh")], env=env, check=True)
    package_platform(work, args.platform, args.tls_hardening)


def aggregate(args):
    paths = sorted(args.inputs.rglob("ffmpeg-audio-candidate-*.jar"))
    entries = {}
    platforms = set()
    profiles = set()
    platform_report = []
    for path in paths:
        with zipfile.ZipFile(path) as jar:
            reports = [name for name in jar.namelist() if name.startswith("META-INF/muonium/ffmpeg/platforms/")]
            if len(reports) != 1:
                fail("Expected one platform report: " + str(path))
            report = json.loads(jar.read(reports[0]))
            platform = report["platform"]
            if platform in platforms or platform not in CAP["platforms"]:
                fail("Duplicate/unknown platform: " + platform)
            if report["provenance"] != PROVENANCE or report["nativeCompilation"] != "completed":
                fail("Only source-built candidates may enter this bundle; stock packages are not slim builds")
            if report["capabilitiesSha256"] != sha(ROOT / "capabilities.json") or report["sourcesLockSha256"] != sha(ROOT / "sources.lock.json"):
                fail("Mixed native recipes/locks")
            if set(report["files"]) != expected_native_entries(platform):
                fail("Native library/JNI layout mismatch: " + platform)
            if not platform.startswith("android") and report["runtimeSmoke"] != "passed-inventory-local-audio-http-https-hls-aes128":
                fail("Desktop JNI/media/network smoke not completed: " + platform)
            declared = set(report["files"]) | set(reports)
            if len(jar.namelist()) != len(set(jar.namelist())):
                fail("Duplicate ZIP member names in native artifact")
            if set(jar.namelist()) != declared:
                fail("Undeclared or missing files in native artifact")
            for name, evidence in report["files"].items():
                data = jar.read(name)
                if hashlib.sha256(data).hexdigest() != evidence["sha256"] or len(data) != evidence["bytes"]:
                    fail("Native artifact hash/size mismatch: " + name)
                if name in entries:
                    fail("Duplicate platform resource: " + name)
                entries[name] = data
            entries[reports[0]] = jar.read(reports[0])
            profiles.add(report.get("tlsHardening", "deferred"))
            platforms.add(platform)
            platform_report.append({"platform": platform, "jarBytes": path.stat().st_size, "sha256": sha(path),
                                    "runtimeSmoke": report["runtimeSmoke"]})
    if platforms != set(CAP["platforms"]):
        fail("Source candidate bundle is incomplete: " + str(sorted(set(CAP["platforms"]) - platforms)))
    if len(profiles) != 1:
        fail("Mixed native optional patch profiles")
    cache = args.cache.resolve()
    for key in ("javacpp", "ffmpegJava"):
        for name, data in java_entries(fetch(key, cache)):
            if name in entries and entries[name] != data:
                if name.startswith("META-INF/"):
                    name = "META-INF/muonium/ffmpeg/upstream/" + key + "/" + name[9:]
                else:
                    fail("Nonidentical duplicate Java resource: " + name)
            entries[name] = data
    with zipfile.ZipFile(fetch("certifi", cache)) as z:
        entries["META-INF/muonium/ffmpeg/cacert.pem"] = z.read("certifi/cacert.pem")
    # License texts are staged from this checkout, with locked upstream source/file hashes.
    # Aggregate needs only the declared source candidates + pinned Java/CA artifacts.
    for name, data in license_entries():
        entries[name] = data
    entries["META-INF/MANIFEST.MF"] = ("Manifest-Version: 1.0\r\n"
        "Automatic-Module-Name: muonium.ffmpeg.audio\r\n"
        "Muonium-Native-Provenance: " + PROVENANCE + "\r\n\r\n").encode()
    for entry in LOCK.get("patches", []):
        entries["META-INF/muonium/ffmpeg/" + entry["file"]] = (ROOT / entry["file"]).read_bytes()
    for name in ("capabilities.json", "sources.lock.json"):
        entries["META-INF/muonium/ffmpeg/" + name] = (ROOT / name).read_bytes()
    report = {"schemaVersion": 1, "provenance": PROVENANCE, "platforms": platform_report,
              "releaseReady": False, "androidRuntime": "not-verified-by-host-CI",
              "tlsHardening": next(iter(profiles)), "tlsSecurityProductionVerified": False,
              "distribution": "internal source-candidate aggregate; Windows embedded, other ABIs on demand"}
    entries["META-INF/muonium/ffmpeg/bundle-report.json"] = (json.dumps(report, indent=2) + "\n").encode()
    write_jar(args.output, entries)
    dump(args.output.with_suffix(".report.json"), {**report, "bundleBytes": args.output.stat().st_size, "sha256": sha(args.output)})
    print(f"Internal source-candidate aggregate: {args.output} ({args.output.stat().st_size} bytes). Not a release verification.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    plan = subs.add_parser("plan", help="print exact configure flags without building")
    plan.add_argument("--platform", choices=CAP["platforms"])
    validate = subs.add_parser("validate-source", help="check exact FFmpeg source registries/options")
    validate.add_argument("source", type=Path)
    compile_cmd = subs.add_parser("build", help="build one internal platform candidate; not a user platform distribution")
    compile_cmd.add_argument("--platform", choices=CAP["platforms"], required=True)
    compile_cmd.add_argument("--work", type=Path, required=True)
    compile_cmd.add_argument("--tls-hardening", action="store_true", help="optional deferred TLS patch profile; disabled in core CI")
    compile_cmd.add_argument("--cache", type=Path, default=Path(".scratch/ffmpeg-source-cache"))
    bundle = subs.add_parser("bundle", help="assemble declared source candidates + Java classes for internal validation; not the mod distribution")
    bundle.add_argument("--inputs", type=Path, required=True)
    bundle.add_argument("--output", type=Path, required=True)
    bundle.add_argument("--cache", type=Path, default=Path(".scratch/ffmpeg-source-cache"))
    args = parser.parse_args()
    if args.command == "plan":
        print(json.dumps({"platform": args.platform, "configure": options(), "sources": LOCK,
                          "provenanceIfBuilt": PROVENANCE, "nativeCompilation": "not-run"}, indent=2))
    elif args.command == "validate-source":
        validate_source(args.source.resolve())
    elif args.command == "build":
        build(args)
    elif args.command == "bundle":
        aggregate(args)


if __name__ == "__main__":
    main()
