#!/usr/bin/env python3
"""Produce a deterministic flat source-built companion ZIP; never uploads."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import zipfile

import recipe

PLATFORMS = {"linux-x86_64": 62, "android-arm64": 183}
REPORT_PREFIX = "META-INF/muonium/ffmpeg/platforms/"


def source_provenance(value):
    # Official JNI reuse is permitted; stock FFmpeg shared libraries are not.
    return value.startswith("source-") and not any(
        marker in value.replace("stock-jni", "") for marker in ("mixed", "stock", "upstream-component"))


def package(candidate, platform, destination):
    candidate, destination = Path(candidate), Path(destination)
    if platform not in PLATFORMS:
        raise ValueError("Unsupported companion ABI: " + platform)
    majors = recipe.CAP["libraries"]
    required_paths = recipe.expected_native_entries(platform)
    required = {PurePosixPath(name).name for name in required_paths}
    entries = {}
    with zipfile.ZipFile(candidate) as jar:
        names = jar.namelist()
        if len(names) != len(set(names)):
            raise ValueError("Duplicate ZIP member names")
        report_path = REPORT_PREFIX + platform + ".json"
        if report_path not in names:
            raise ValueError("Missing source-build platform report")
        report_data = jar.read(report_path)
        source_report = json.loads(report_data)
        provenance = source_report.get("provenance", "")
        if (source_report.get("platform") != platform or
                not source_report.get("nativeCompilation", "").startswith("completed") or
                not source_provenance(provenance)):
            raise ValueError("Only source-built FFmpeg libraries may enter a companion; stock JNI is separate")
        if (source_report.get("ffmpegVersion") != recipe.CAP["ffmpegVersion"] or
                source_report.get("javaCppVersion") != recipe.CAP["javaCppVersion"]):
            raise ValueError("Wrong FFmpeg/JavaCPP version in source report")
        recipe.audit_components(source_report.get("configuredComponents", []))
        native_paths = {name for name in names if name.startswith("org/bytedeco/") and
                        re.search(r"\.(?:so(?:\.[0-9]+)*|dll|dylib)$", name)}
        if native_paths != required_paths:
            raise ValueError("Missing/unexpected native libraries or foreign ABI: " +
                             str(sorted(native_paths ^ required_paths)))
        records = source_report.get("files", {})
        if set(records) != required_paths:
            raise ValueError("Source-report native inventory mismatch")
        for name in sorted(required_paths):
            data = jar.read(name)
            if (records[name].get("sha256") != hashlib.sha256(data).hexdigest() or
                    records[name].get("bytes") != len(data)):
                raise ValueError("Source-report hash/size mismatch: " + name)
            short = PurePosixPath(name).name
            if short in entries:
                raise ValueError("Duplicate native basename: " + short)
            # Both targets are little-endian ELF64 shared objects; ABI comes from the requested target.
            if (len(data) < 64 or data[:6] != b"\x7fELF\x02\x01" or
                    int.from_bytes(data[16:18], "little") != 3 or
                    int.from_bytes(data[18:20], "little") != PLATFORMS[platform]):
                raise ValueError("Wrong ELF ABI/shared-object type: " + short)
            entries[short] = data
    for module in majors:
        module_report = source_report.get("libraries", {}).get(module, {})
        if module_report and (module_report.get("major") != majors[module] or
                              not source_provenance(module_report.get("provenance", ""))):
            raise ValueError("Mixed/stock FFmpeg library provenance: " + module)
    root = Path(__file__).resolve().parent
    for name, data in recipe.license_entries():
        entries["licenses/" + name.removeprefix(recipe.LOCK["licenseInventory"]["destination"])] = data
    native_hashes = {name: hashlib.sha256(entries[name]).hexdigest() for name in sorted(required)}
    official_jni = ("stock-jni" in provenance or "official-jni" in provenance
                    or "official" in str(source_report.get("jniProvenance", "")).lower())
    libraries = {}
    for module, major in majors.items():
        native = "lib" + module + ".so" + ("" if platform.startswith("android-") else "." + str(major))
        jni = "libjni" + module + ".so"
        libraries[module] = {
            "major": major, "file": native, "sha256": native_hashes[native],
            "provenance": provenance, "nativeCompilation": source_report["nativeCompilation"],
            "jni": {"file": jni, "sha256": native_hashes[jni],
                    "version": recipe.CAP["ffmpegMavenVersion"],
                    "provenance": "matching-official-jni" if official_jni else "source-built-against-locked-java"}}
    manifest = {"schemaVersion": 1, "platform": platform,
                "ffmpegVersion": recipe.CAP["ffmpegVersion"], "javaCppVersion": recipe.CAP["javaCppVersion"],
                "ffmpegMavenVersion": recipe.CAP["ffmpegMavenVersion"],
                "provenance": provenance, "targetRuntimeTested": False, "releaseReady": False,
                "candidateSha256": recipe.sha(candidate), "nativeSha256": native_hashes,
                "nativeCount": len(required), "libraries": libraries, "dependencies": [],
                "sourceReportSha256": hashlib.sha256(report_data).hexdigest(),
                "sourceCapabilitiesSha256": source_report.get("capabilitiesSha256"),
                "sourceLockSha256": source_report.get("sourcesLockSha256"),
                "configuredComponents": source_report["configuredComponents"],
                "tlsHardening": source_report.get("tlsHardening", "deferred"),
                "tlsSecurityProductionVerified": False}
    entries["build-manifest.json"] = (json.dumps(manifest, sort_keys=True, indent=2) + "\n").encode()
    entries["source/platform-report.json"] = report_data
    # Preserve locked patch bytes, including the mandatory disabled-DTLS UDP helper guard.
    for entry in recipe.selected_patches("tls-hardening" if manifest["tlsHardening"].startswith("optional-enabled") else None):
        patch = root / entry["file"]
        if recipe.sha(patch) != entry["sha256"]:
            raise ValueError("Source patch hash mismatch: " + entry["file"])
        entries["source/" + PurePosixPath(entry["file"]).name] = patch.read_bytes()
    destination.mkdir(parents=True, exist_ok=True)
    draft = destination / (platform + ".candidate.zip")
    recipe.write_jar(draft, entries)
    digest = recipe.sha(draft)
    final = destination / f"muonium-ffmpeg-{platform}-{recipe.CAP['ffmpegMavenVersion']}-{digest[:16]}.zip"
    if final.exists():
        if recipe.sha(final) != digest:
            raise ValueError("Refusing to replace an existing different archive")
        draft.unlink()
    else:
        draft.rename(final)
    metadata = {**manifest, "fileName": final.name, "bytes": final.stat().st_size,
                "sha256": digest, "path": str(final.resolve())}
    (destination / (platform + ".json")).write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({key: metadata[key] for key in ("platform", "fileName", "bytes", "sha256", "dependencies")}, indent=2))
    return metadata


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--platform", required=True, choices=PLATFORMS)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    package(args.candidate, args.platform, args.output)
