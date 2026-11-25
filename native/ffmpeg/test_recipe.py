#!/usr/bin/env python3
"""Build-manifest rejection tests. Synthetic bytes are not native compilation evidence."""
import argparse
import hashlib
import io
import importlib.util
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile
import recipe

spec = importlib.util.spec_from_file_location("package_companion", recipe.ROOT / "package-companion.py")
companion = importlib.util.module_from_spec(spec)
spec.loader.exec_module(companion)


class NativeBundleGuards(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.inputs = self.root / "inputs"
        self.inputs.mkdir()
        self.args = argparse.Namespace(inputs=self.inputs, output=self.root / "out.jar", cache=self.root / "cache")

    def tearDown(self):
        self.temp.cleanup()

    def candidate(self, platform, *, mutate=None, damaged=None):
        files = {name: ("synthetic-not-native:" + name).encode() for name in recipe.expected_native_entries(platform)}
        report = {"platform": platform, "provenance": recipe.PROVENANCE, "nativeCompilation": "completed",
                  "runtimeSmoke": "not-run" if platform.startswith("android") else "passed-inventory-local-audio-http-https-hls-aes128",
                  "capabilitiesSha256": recipe.sha(recipe.ROOT / "capabilities.json"),
                  "sourcesLockSha256": recipe.sha(recipe.ROOT / "sources.lock.json"),
                  "files": {name: {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)} for name, data in files.items()}}
        if mutate:
            mutate(report)
        if damaged:
            damaged(files)
        files[f"META-INF/muonium/ffmpeg/platforms/{platform}.json"] = json.dumps(report).encode()
        path = self.inputs / ("ffmpeg-audio-candidate-" + platform + ".jar")
        recipe.write_jar(path, files)
        return path

    def all_candidates(self):
        for platform in recipe.CAP["platforms"]:
            self.candidate(platform)

    def test_partial_platform_set_cannot_form_universal_bundle(self):
        self.candidate("android-arm64")
        with self.assertRaisesRegex(RuntimeError, "incomplete"):
            recipe.aggregate(self.args)

    def test_stock_binary_cannot_be_relabelled_by_aggregate(self):
        self.candidate("android-arm64", mutate=lambda r: r.update(provenance="stock-component-trimmed"))
        with self.assertRaisesRegex(RuntimeError, "source-built"):
            recipe.aggregate(self.args)

    def test_mixed_recipe_versions_are_rejected(self):
        self.candidate("android-arm64", mutate=lambda r: r.update(sourcesLockSha256="0" * 64))
        with self.assertRaisesRegex(RuntimeError, "Mixed native recipes"):
            recipe.aggregate(self.args)

    def test_byte_tampering_is_rejected_before_assembly(self):
        self.candidate("android-arm64", damaged=lambda f: f.update({next(iter(f)): b"changed"}))
        with self.assertRaisesRegex(RuntimeError, "hash/size mismatch"):
            recipe.aggregate(self.args)

    def test_missing_runtime_jni_is_rejected(self):
        def missing(report):
            key = next(k for k in report["files"] if "jnijavacpp" in k)
            del report["files"][key]
        self.candidate("android-arm64", mutate=missing)
        with self.assertRaisesRegex(RuntimeError, "layout mismatch"):
            recipe.aggregate(self.args)

    def test_unverified_desktop_is_rejected(self):
        self.candidate("linux-x86_64", mutate=lambda r: r.update(runtimeSmoke="not-run"))
        with self.assertRaisesRegex(RuntimeError, "smoke not completed"):
            recipe.aggregate(self.args)

    def test_duplicate_platform_is_rejected(self):
        first = self.candidate("android-arm64")
        (self.inputs / "ffmpeg-audio-candidate-duplicate.jar").write_bytes(first.read_bytes())
        with self.assertRaisesRegex(RuntimeError, "Duplicate/unknown platform"):
            recipe.aggregate(self.args)

    def test_unreported_files_are_rejected(self):
        self.candidate("android-arm64", damaged=lambda files: files.update({"ffmpeg.exe": b"unwanted"}))
        with self.assertRaisesRegex(RuntimeError, "Undeclared"):
            recipe.aggregate(self.args)

    def test_duplicate_zip_names_are_rejected(self):
        path = self.candidate("android-arm64")
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(path, "a") as jar:
                jar.writestr("duplicate", b"a")
                jar.writestr("duplicate", b"b")
        with self.assertRaisesRegex(RuntimeError, "Duplicate ZIP"):
            recipe.aggregate(self.args)

    def patch_fixture(self):
        checkout = self.root / "patch-checkout"
        source = self.root / "patch-source"
        (checkout / "patches").mkdir(parents=True, exist_ok=True)
        (source / "libavformat").mkdir(parents=True, exist_ok=True)
        original = b"tls_enabled\nudp_helpers\nidentity_deferred\n"
        hardened = b"tls_enabled\nudp_helpers\nidentity_enabled\n"
        guarded = b"tls_enabled\n#if CONFIG_DTLS_PROTOCOL\nudp_helpers\n#endif\nidentity_deferred\n"
        combined = b"tls_enabled\n#if CONFIG_DTLS_PROTOCOL\nudp_helpers\n#endif\nidentity_enabled\n"
        target = source / "libavformat/tls_openssl.c"
        target.write_bytes(original)
        optional = {"file": "patches/optional-identity.patch", "target": "libavformat/tls_openssl.c",
                    "profile": "tls-hardening", "enabledByDefault": False,
                    "upstreamSha256": hashlib.sha256(original).hexdigest(),
                    "patchedSha256": hashlib.sha256(hardened).hexdigest()}
        guard = {"file": "patches/disabled-dtls-udp-helper-guard.patch", "target": optional["target"],
                 "enabledByDefault": True, "upstreamSha256": hashlib.sha256(original).hexdigest(),
                 "patchedSha256": hashlib.sha256(guarded).hexdigest(),
                 "profileHashes": {"tls-hardening": {"upstreamSha256": hashlib.sha256(hardened).hexdigest(),
                                                    "patchedSha256": hashlib.sha256(combined).hexdigest()}}}
        for entry, diff in [(optional, "@@ -3 +3 @@\n-identity_deferred\n+identity_enabled\n"),
                            (guard, "@@ -2 +2,3 @@\n+#if CONFIG_DTLS_PROTOCOL\n udp_helpers\n+#endif\n")]:
            path = checkout / entry["file"]
            path.write_bytes(diff.encode())
            entry["sha256"] = recipe.sha(path)
        return source, checkout, {"patches": [optional, guard]}, guarded, combined

    def test_default_build_profile_applies_guard_without_deferred_tls_patches(self):
        self.assertEqual([p["file"] for p in recipe.selected_patches()],
                         ["patches/disabled-dtls-udp-helper-guard.patch"])
        source, checkout, lock, guarded, _ = self.patch_fixture()
        with patch.object(recipe, "ROOT", checkout), patch.object(recipe, "LOCK", lock):
            recipe.apply_patches(source)
        self.assertEqual((source / "libavformat/tls_openssl.c").read_bytes(), guarded)

    def test_required_guard_hashes_match_verified_source_build(self):
        guard = next(p for p in recipe.LOCK["patches"] if p.get("enabledByDefault"))
        self.assertEqual(recipe.sha(recipe.ROOT / guard["file"]),
                         "9ad7ef945d88c9e86c8abde23003dc19f33bf1b226fa8ab356ce1171226a4a0a")
        self.assertEqual(guard["upstreamSha256"],
                         "3b59111c4bbdef0ed9f0773e7e0d7a63e55f8497c307d248ac742bfe14e6c3ab")
        self.assertEqual(guard["patchedSha256"],
                         "77ba219979a3dd00cdaaa2059960ca6f34cb2771f9cea4e21b79308de542542c")

    def test_tls_hardening_also_applies_required_guard_in_lock_order(self):
        self.assertEqual(len(recipe.selected_patches("tls-hardening")), 3)
        source, checkout, lock, _, combined = self.patch_fixture()
        with patch.object(recipe, "ROOT", checkout), patch.object(recipe, "LOCK", lock):
            recipe.apply_patches(source, "tls-hardening")
        self.assertEqual((source / "libavformat/tls_openssl.c").read_bytes(), combined)

    def test_required_guard_rejects_patch_source_and_result_hash_tampering(self):
        for changed in ("patch", "source", "result"):
            with self.subTest(changed=changed):
                source, checkout, lock, _, _ = self.patch_fixture()
                guard = lock["patches"][-1]
                if changed == "patch":
                    (checkout / guard["file"]).write_bytes(b"changed patch")
                elif changed == "source":
                    (source / guard["target"]).write_bytes(b"changed source")
                else:
                    guard["patchedSha256"] = "0" * 64
                with patch.object(recipe, "ROOT", checkout), patch.object(recipe, "LOCK", lock):
                    with self.assertRaisesRegex(RuntimeError, "[Hh]ash mismatch"):
                        recipe.apply_patches(source)

    def test_locked_upstream_licenses_are_staged_byte_for_byte(self):
        entries = dict(recipe.license_entries())
        prefix = "META-INF/muonium/ffmpeg/licenses/"
        manifest = json.loads(entries[prefix + "licenses.lock.json"])
        self.assertEqual(len(manifest["files"]), 9)
        self.assertIn(prefix + "javacpp-LICENSE.txt", entries)
        self.assertIn(prefix + "javacpp-presets-LICENSE.txt", entries)
        self.assertIn(prefix + "ffmpeg-COPYING.GPLv3.txt", entries)
        self.assertNotIn(prefix + ".gitattributes", entries)
        for record in manifest["files"]:
            self.assertEqual(hashlib.sha256(entries[prefix + record["file"]]).hexdigest(), record["sha256"])

    def test_changed_upstream_license_is_rejected(self):
        isolated = self.root / "isolated-checkout"
        source = recipe.ROOT / "licenses"
        target = isolated / "licenses"
        target.mkdir(parents=True)
        for path in source.iterdir():
            if path.is_file():
                (target / path.name).write_bytes(path.read_bytes())
        (target / "javacpp-LICENSE.txt").write_bytes(b"changed license")
        with patch.object(recipe, "ROOT", isolated):
            with self.assertRaisesRegex(RuntimeError, "License text hash/size mismatch"):
                dict(recipe.license_entries())

    def test_universal_jar_can_stage_from_candidates_and_recipe_without_parent_checkout(self):
        self.all_candidates()
        fake = self.root / "official-artifact-inputs"
        fake.mkdir()
        archives = {}
        for key in ("javacpp", "ffmpegJava", "certifi"):
            path = fake / (key + ".jar")
            archives[key] = path
            if key == "certifi":
                content = {"certifi/cacert.pem": b"synthetic CA bytes, not a trust test"}
            else:
                content = {"org/bytedeco/" + key + "/Synthetic.class": b"synthetic class, not a JNI test"}
            recipe.write_jar(path, content)
        # A mocked artifact fetch prohibits network or sibling-source requests in this assembly test.
        with patch.object(recipe, "fetch", side_effect=lambda key, _: archives[key]):
            recipe.aggregate(self.args)
        with zipfile.ZipFile(self.args.output) as jar:
            names = jar.namelist()
            self.assertEqual(len(names), len(set(names)))
            for platform in recipe.CAP["platforms"]:
                self.assertTrue(recipe.expected_native_entries(platform) <= set(names))
            for name, data in recipe.license_entries():
                self.assertEqual(jar.read(name), data)
            report = json.loads(jar.read("META-INF/muonium/ffmpeg/bundle-report.json"))
            self.assertFalse(report["releaseReady"])
            self.assertEqual(len(report["platforms"]), 3)

    def test_bad_cached_source_is_rejected_without_network(self):
        self.args.cache.mkdir()
        name = recipe.LOCK["sources"]["ffmpeg"]["file"]
        (self.args.cache / name).write_bytes(b"corrupted")
        with self.assertRaisesRegex(RuntimeError, "wrong SHA256"):
            recipe.fetch("ffmpeg", self.args.cache)

    def test_source_archive_cannot_escape_work_directory(self):
        archive = self.root / "source.tar"
        with tarfile.open(archive, "w") as tar:
            member = tarfile.TarInfo("../escaped")
            member.size = 1
            tar.addfile(member, io.BytesIO(b"x"))
        with self.assertRaisesRegex(RuntimeError, "Unsafe archive member"):
            recipe.extract("ffmpeg", archive, self.root / "sources")
        self.assertFalse((self.root / "escaped").exists())

    def config(self, extra=(), missing=()):
        names = {name.upper() + "_" + group[:-1].upper()
                 for group, values in recipe.CAP["components"].items() for name in values}
        names.update({"AVUTIL", "AVCODEC", "AVFORMAT", "SWRESAMPLE", "SWSCALE", "NETWORK", "OPENSSL", "ZLIB", "VERSION3"})
        names.update(extra)
        names.difference_update(missing)
        build = self.root / "build"
        (build / "ffbuild").mkdir(parents=True, exist_ok=True)
        (build / "ffbuild/config.mak").write_text("\n".join("CONFIG_" + name + "=yes" for name in sorted(names)))
        return build

    def test_expected_configuration_passes_inventory_check_only(self):
        self.assertIn("HLS_DEMUXER", recipe.audit_config(self.config()))

    def test_video_decoder_accidentally_enabled_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Unexpected decoders"):
            recipe.audit_config(self.config(extra=["AV1_DECODER"]))

    def test_unexpected_encoder_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "Unexpected enabled"):
            recipe.audit_config(self.config(extra=["PCM_S16LE_ENCODER"]))

    def test_silently_disabled_subfile_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "silently omitted"):
            recipe.audit_config(self.config(missing=["SUBFILE_PROTOCOL"]))

    def test_silently_disabled_https_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError, "silently omitted"):
            recipe.audit_config(self.config(missing=["HTTPS_PROTOCOL"]))


    def test_source_platforms_and_distribution_policy(self):
        self.assertEqual(recipe.CAP["platforms"], ["windows-x86_64", "linux-x86_64", "android-arm64"])
        self.assertEqual(recipe.CAP["bundleClassifierPolicy"], "windows-embedded-other-on-demand")
        self.assertEqual(set(recipe.CAP["platforms"]) & set(recipe.CAP["onDemandStockPlatforms"]), set())

    def test_audio_inventory_retained_with_only_two_video_decoders(self):
        components = recipe.CAP["components"]
        self.assertEqual(len(set(components["decoders"]) - {"h264", "hevc"}), 63)
        self.assertEqual(len(components["decoders"]), 65)
        self.assertEqual(len(components["demuxers"]), 27)
        self.assertEqual(len(components["parsers"]), 12)
        self.assertEqual(len(components["protocols"]), 9)
        self.assertTrue({"h264", "hevc"} <= set(components["parsers"]))
        self.assertIn("--enable-swscale", recipe.options())
        self.assertNotIn("--disable-swscale", recipe.options())

    def test_h264_hevc_decoder_parser_and_swscale_cannot_be_omitted(self):
        for component in ("H264_DECODER", "HEVC_DECODER", "H264_PARSER", "HEVC_PARSER", "SWSCALE"):
            with self.subTest(component=component), self.assertRaisesRegex(RuntimeError, "omitted|missing"):
                recipe.audit_config(self.config(missing=[component]))

    def test_other_video_and_subtitle_decoders_remain_forbidden(self):
        for decoder in ("VP9", "MPEG2VIDEO", "PNG", "MJPEG", "H264_CUVID", "HEVC_QSV", "ASS"):
            with self.subTest(decoder=decoder), self.assertRaisesRegex(RuntimeError, "Unexpected decoders"):
                recipe.audit_config(self.config(extra=[decoder + "_DECODER"]))

    def test_video_filters_tools_devices_and_hwaccels_remain_forbidden(self):
        for component in ("PNG_ENCODER", "SCALE_FILTER", "AVFILTER", "AVDEVICE", "FFMPEG", "FFPROBE",
                          "FFPLAY", "MP4_MUXER", "ALSA_INDEV", "ALSA_OUTDEV", "H264_VAAPI_HWACCEL"):
            with self.subTest(component=component), self.assertRaisesRegex(RuntimeError, "Unexpected enabled"):
                recipe.audit_config(self.config(extra=[component]))

    def test_all_source_targets_require_swscale_library_and_jni(self):
        self.assertEqual(recipe.MODULES, ("avutil", "swresample", "avcodec", "avformat", "swscale"))
        for platform, native, jni in (("windows-x86_64", "swscale-9.dll", "jniswscale.dll"),
                                      ("linux-x86_64", "libswscale.so.9", "libjniswscale.so"),
                                      ("android-arm64", "libswscale.so", "libjniswscale.so")):
            with self.subTest(platform=platform):
                names = recipe.expected_native_entries(platform)
                prefix = "org/bytedeco/ffmpeg/" + platform + "/"
                self.assertIn(prefix + native, names)
                self.assertIn(prefix + jni, names)
                self.assertEqual(len(names), 12 if platform.startswith("windows") else 11)

    def test_missing_swscale_library_or_jni_is_rejected_by_assembly(self):
        for suffix in ("/libswscale.so", "/libjniswscale.so"):
            def omit(report):
                del report["files"][next(name for name in report["files"] if name.endswith(suffix))]
            with self.subTest(suffix=suffix):
                self.candidate("android-arm64", mutate=omit)
                with self.assertRaisesRegex(RuntimeError, "layout mismatch"):
                    recipe.aggregate(self.args)

    def test_java_bindings_retain_swscale_and_graal_but_remove_filter_device(self):
        entries = {}
        retained = []
        for module in ("swscale", "avfilter", "avdevice"):
            names = [f"org/bytedeco/ffmpeg/{module}/Context.class",
                     f"org/bytedeco/ffmpeg/global/{module}.class",
                     f"org/bytedeco/ffmpeg/presets/{module}.class",
                     f"META-INF/native-image/linux-x86_64/jni{module}/jni-config.json"]
            entries.update({name: b"synthetic class/config" for name in names})
            if module == "swscale":
                retained.extend(names)
        jar = self.root / "java.jar"
        recipe.write_jar(jar, entries)
        self.assertEqual(set(dict(recipe.java_entries(jar))), set(retained))

    def source_fixture(self):
        source = self.root / "ffmpeg-source"
        registries = {"decoders": ("libavcodec/allcodecs.c", "FFCodec", "decoder"),
                      "demuxers": ("libavformat/allformats.c", "FFInputFormat", "demuxer"),
                      "parsers": ("libavcodec/parsers.c", "FFCodecParser", "parser"),
                      "protocols": ("libavformat/protocols.c", "URLProtocol", "protocol")}
        for group, (name, kind, suffix) in registries.items():
            path = source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("\n".join(f"extern const {kind} ff_{item}_{suffix};" for item in recipe.CAP["components"][group]))
        for module, major in recipe.CAP["libraries"].items():
            directory = source / ("lib" + module)
            directory.mkdir(exist_ok=True)
            (directory / ("version.h" if module == "avutil" else "version_major.h")).write_text(f"#define LIB{module.upper()}_VERSION_MAJOR {major}\n")
        (source / "configure").write_text("\n".join(recipe.CAP["configureBase"]))
        (source / "RELEASE").write_text(recipe.CAP["ffmpegVersion"])
        return source

    def test_wrong_swscale_source_major_is_rejected(self):
        source = self.source_fixture()
        recipe.validate_source(source)
        (source / "libswscale/version_major.h").write_text("#define LIBSWSCALE_VERSION_MAJOR 8\n")
        with self.assertRaisesRegex(RuntimeError, "ABI major.*swscale"):
            recipe.validate_source(source)

    def test_missing_video_registry_declaration_is_rejected(self):
        for name, declaration in (("libavcodec/allcodecs.c", "extern const FFCodec ff_hevc_decoder;"),
                                  ("libavcodec/parsers.c", "extern const FFCodecParser ff_h264_parser;")):
            with self.subTest(name=name):
                source = self.source_fixture()
                path = source / name
                path.write_text(path.read_text().replace(declaration, ""))
                with self.assertRaisesRegex(RuntimeError, "Unknown FFmpeg"):
                    recipe.validate_source(source)

    def companion_candidate(self, platform="android-arm64", *, omit=(), extra=None, mutate=None, wrong_abi=None):
        # Header-shaped synthetic bytes only exercise packaging rejection; no native execution is implied.
        header = bytearray(64)
        header[:6] = b"\x7fELF\x02\x01"
        header[16:18] = (3).to_bytes(2, "little")
        header[18:20] = companion.PLATFORMS[platform].to_bytes(2, "little")
        files = {name: bytes(header) + name.encode() for name in recipe.expected_native_entries(platform)
                 if not any(name.endswith(suffix) for suffix in omit)}
        if extra:
            files.update(extra)
        if wrong_abi:
            name = next(name for name in files if name.endswith(wrong_abi))
            data = bytearray(files[name])
            data[18:20] = (62 if platform == "android-arm64" else 183).to_bytes(2, "little")
            files[name] = bytes(data)
        report = {"platform": platform, "provenance": "source-cross-built-cover-stock-jni",
                  "nativeCompilation": "completed-cross-compiled-on-Windows", "runtimeSmoke": "not-run",
                  "ffmpegVersion": recipe.CAP["ffmpegVersion"], "javaCppVersion": recipe.CAP["javaCppVersion"],
                  "configuredComponents": recipe.audit_config(self.config()),
                  "files": {name: {"sha256": hashlib.sha256(data).hexdigest(), "bytes": len(data)}
                            for name, data in files.items()}}
        if mutate:
            mutate(report)
        files[companion.REPORT_PREFIX + platform + ".json"] = json.dumps(report).encode()
        candidate = self.root / "companion-candidate.jar"
        recipe.write_jar(candidate, files)
        return candidate

    def test_companion_flat_manifest_records_all_five_libraries_and_eleven_natives(self):
        for platform in companion.PLATFORMS:
            with self.subTest(platform=platform):
                candidate = self.companion_candidate(platform)
                metadata = companion.package(candidate, platform, self.root / "output")
                with zipfile.ZipFile(metadata["path"]) as archive:
                    names = archive.namelist()
                    native = [name for name in names if ".so" in name]
                    self.assertEqual(len(native), 11)
                    self.assertTrue(all("/" not in name for name in native))
                    manifest = json.loads(archive.read("build-manifest.json"))
                    self.assertEqual(set(manifest["libraries"]), set(recipe.MODULES))
                    self.assertEqual(manifest["libraries"]["swscale"]["major"], 9)
                    self.assertEqual(manifest["nativeCount"], 11)
                    for library in manifest["libraries"].values():
                        self.assertTrue(library["provenance"].startswith("source-"))
                        self.assertEqual(library["jni"]["provenance"], "matching-official-jni")
                        self.assertEqual(library["jni"]["version"], "8.1.2-1.5.14")
                    self.assertFalse(manifest["targetRuntimeTested"])
                    self.assertIn("source/disabled-dtls-udp-helper-guard.patch", names)
                    with zipfile.ZipFile(candidate) as source:
                        self.assertEqual(archive.read("source/platform-report.json"),
                                         source.read(companion.REPORT_PREFIX + platform + ".json"))
                self.assertEqual(metadata["sha256"], companion.package(candidate, platform, self.root / "output")["sha256"])

    def test_companion_requires_fifth_library_and_jni(self):
        for suffix in ("/libswscale.so", "/libjniswscale.so"):
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, "Missing/unexpected native"):
                companion.package(self.companion_candidate(omit=[suffix]), "android-arm64", self.root / "output")

    def test_companion_rejects_wrong_elf_machine_including_swscale_jni(self):
        for suffix in ("/libswscale.so", "/libjniswscale.so", "/libjnijavacpp.so"):
            with self.subTest(suffix=suffix), self.assertRaisesRegex(ValueError, "Wrong ELF ABI"):
                companion.package(self.companion_candidate(wrong_abi=suffix), "android-arm64", self.root / "output")

    def test_companion_rejects_foreign_abi_and_unrequested_native_module(self):
        for name in ("org/bytedeco/ffmpeg/linux-x86_64/libswscale.so.9",
                     "org/bytedeco/ffmpeg/android-arm64/libavfilter.so"):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "foreign ABI"):
                companion.package(self.companion_candidate(extra={name: b"unexpected"}), "android-arm64", self.root / "output")

    def test_companion_rejects_stock_mixed_uncompiled_or_wrong_version_reports(self):
        for changed in ({"provenance": "stock-component-trimmed"}, {"provenance": "source-mixed-stock-component"},
                        {"nativeCompilation": "not-run"}, {"ffmpegVersion": "8.0"}, {"javaCppVersion": "1.5.13"},
                        {"platform": "linux-x86_64"}):
            with self.subTest(changed=changed), self.assertRaisesRegex(ValueError, "source-built|version"):
                candidate = self.companion_candidate(mutate=lambda report: report.update(changed))
                companion.package(candidate, "android-arm64", self.root / "output")

    def test_companion_rejects_explicit_stock_swscale_among_source_libraries(self):
        candidate = self.companion_candidate(mutate=lambda report: report.update(
            libraries={"swscale": {"major": 9, "provenance": "stock-component-trimmed"}}))
        with self.assertRaisesRegex(ValueError, "Mixed/stock"):
            companion.package(candidate, "android-arm64", self.root / "output")

    def test_companion_rejects_modified_native_hashes_and_config_leaks(self):
        for changed in ("hash", "config", "inventory"):
            def mutate(report):
                if changed == "hash":
                    report["files"][next(iter(report["files"]))]["sha256"] = "0" * 64
                elif changed == "config":
                    report["configuredComponents"].append("VP9_DECODER")
                else:
                    del report["files"][next(iter(report["files"]))]
            with self.subTest(changed=changed), self.assertRaisesRegex((ValueError, RuntimeError), "mismatch|Unexpected decoders"):
                companion.package(self.companion_candidate(mutate=mutate), "android-arm64", self.root / "output")


    def test_staged_source_candidate_contains_swscale_and_five_library_provenance(self):
        for platform in recipe.CAP["platforms"]:
            with self.subTest(platform=platform):
                work = self.root / ("stage-" + platform)
                for name in recipe.expected_native_entries(platform):
                    short = name.rsplit("/", 1)[-1]
                    if "jnijavacpp" in short:
                        path = work / "jni-runtime" / short
                    elif "jni" in short or short == "libwinpthread-1.dll":
                        path = work / "jni" / short
                    else:
                        path = work / "install/lib" / short
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(b"synthetic staging fixture: " + name.encode())
                config = work / "ffmpeg-build/ffbuild/config.mak"
                config.parent.mkdir(parents=True)
                config.write_bytes((self.config() / "ffbuild/config.mak").read_bytes())
                candidate = recipe.package_platform(work, platform)
                with zipfile.ZipFile(candidate) as archive:
                    self.assertTrue(recipe.expected_native_entries(platform) <= set(archive.namelist()))
                    report = json.loads(archive.read(companion.REPORT_PREFIX + platform + ".json"))
                    self.assertEqual(set(report["libraries"]), set(recipe.MODULES))
                    self.assertEqual(report["libraries"]["swscale"]["major"], 9)
                    self.assertEqual(report["libraries"]["swscale"]["jniProvenance"], "source-built-against-locked-java")
                    self.assertEqual(report["videoCoverRuntimeSmoke"], "not-run")

    def test_source_build_rejects_non_linux_host_before_fetching(self):
        args = argparse.Namespace(work=self.root / "build", cache=self.root / "cache")
        with patch.object(recipe.sys, "platform", "win32"), patch.object(recipe, "fetch") as fetch:
            with self.assertRaisesRegex(RuntimeError, "Linux host"):
                recipe.build(args)
            fetch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
