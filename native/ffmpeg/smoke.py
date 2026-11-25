#!/usr/bin/env python3
"""Native candidate smoke; fixture creation uses an external test-only ffmpeg executable."""
from __future__ import annotations
import argparse
from functools import partial
import http.server
import json
import math
import os
from pathlib import Path
import shutil
import ssl
import struct
import subprocess
import sys
import threading
import wave
import zipfile
from recipe import CAP, ROOT, dump, fail, fetch, write_jar


def command(args, **kwargs):
    print("Running:", " ".join(map(str, args)), flush=True)
    return subprocess.run(list(map(str, args)), check=True, timeout=120, **kwargs)


def fixtures(dest):
    dest.mkdir(parents=True, exist_ok=True)
    source = dest / "source.wav"
    with wave.open(str(source), "wb") as wav:
        wav.setparams((2, 2, 48000, 0, "NONE", "not compressed"))
        wav.writeframes(b"".join(struct.pack("<hh", int(12000 * math.sin(i * 2 * math.pi * 997 / 48000)),
                                             int(8000 * math.sin(i * 2 * math.pi * 773 / 48000)))
                                 for i in range(48000 * 4)))
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        fail("Fixture generation requires test-only ffmpeg on PATH")
    command([ffmpeg, "-version"])
    for name, codec in {"lossless.flac": "flac", "alac.m4a": "alac", "aac.m4a": "aac",
                        "adts.aac": "aac", "radio.mp3": "libmp3lame", "vorbis.ogg": "libvorbis",
                        "opus.ogg": "libopus", "pcm24.wav": "pcm_s24le", "float.wav": "pcm_f32le"}.items():
        command([ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", source, "-c:a", codec, dest / name])
    for mode in ("ts", "fmp4", "aes128"):
        directory = dest / mode
        directory.mkdir()
        args = [ffmpeg, "-hide_banner", "-loglevel", "error", "-y", "-i", source, "-c:a", "aac",
                "-f", "hls", "-hls_time", "1", "-hls_list_size", "0"]
        if mode == "fmp4":
            args += ["-hls_segment_type", "fmp4", "-hls_segment_filename", directory / "part%02d.m4s"]
        else:
            args += ["-hls_segment_type", "mpegts", "-hls_segment_filename", directory / "part%02d.ts"]
        if mode == "aes128":
            (directory / "key.bin").write_bytes(bytes.fromhex("0123456789abcdef0123456789abcdef"))
            (directory / "key.info").write_text("key.bin\n" + str((directory / "key.bin").resolve()) + "\n")
            args += ["-hls_key_info_file", directory / "key.info"]
        command(args + [directory / "stream.m3u8"])
    (dest / "lossless-with-tail-tag.flac").write_bytes((dest / "lossless.flac").read_bytes() + b"TAG" + bytes(125))
    # Local CA + localhost-only leaf make untrusted and wrong-host checks meaningful.
    cert = dest / "certs"
    cert.mkdir()
    command(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3",
             "-keyout", cert / "ca.key", "-out", cert / "ca.pem", "-subj", "/CN=Muonium recipe QA CA"])
    command(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "3",
             "-keyout", cert / "unrelated.key", "-out", cert / "unrelated-ca.pem", "-subj", "/CN=Unrelated QA CA"])
    command(["openssl", "req", "-newkey", "rsa:2048", "-nodes", "-keyout", cert / "server.key",
             "-out", cert / "server.csr", "-subj", "/CN=localhost"])
    (cert / "ext.cnf").write_text("subjectAltName=DNS:localhost\nbasicConstraints=CA:FALSE\nkeyUsage=digitalSignature,keyEncipherment\nextendedKeyUsage=serverAuth\n")
    command(["openssl", "x509", "-req", "-in", cert / "server.csr", "-CA", cert / "ca.pem", "-CAkey", cert / "ca.key",
             "-CAcreateserial", "-days", "3", "-out", cert / "server.pem", "-extfile", cert / "ext.cnf"])
    print("Created local test media/certificates. They are CI artifacts, never runtime dependencies.")


class FixtureHandler(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        if self.path == "/redirect-ip-mismatch.mp3":
            self.send_response(302)
            self.send_header("Location", f"https://127.0.0.1:{self.server.server_port}/radio.mp3")
            self.send_header("Content-Length", "0")
            self.end_headers()
            return
        super().do_GET()

    def end_headers(self):
        if self.path.startswith("/radio.mp3"):
            self.send_header("icy-name", "Muonium native recipe fixture")
        super().end_headers()

    def log_message(self, *_):
        pass


def smoke(args):
    candidate = args.candidate.resolve()
    media = args.fixtures.resolve()
    work = args.work.resolve()
    work.mkdir(parents=True, exist_ok=True)
    cache = args.cache.resolve()
    cpp = fetch("javacpp", cache)
    ff = fetch("ffmpegJava", cache)
    with zipfile.ZipFile(candidate) as jar:
        report_name = next(name for name in jar.namelist() if name.startswith("META-INF/muonium/ffmpeg/platforms/"))
        report = json.loads(jar.read(report_name))
        if report["platform"].startswith("android"):
            fail("Android JNI must be tested in the actual launcher/device; a desktop JVM is not evidence")
    classes = work / "classes"
    classes.mkdir(exist_ok=True)
    cp = os.pathsep.join(map(str, (cpp, ff, candidate, classes)))
    command(["javac", "--release", "17", "-cp", cp, "-d", classes, ROOT / "RuntimeProbe.java"])
    base = ["java", "-cp", cp, "RuntimeProbe"]
    results = []

    def probe(name, source=None, ca=None, expected=True):
        argv = base + ([str(source)] if source is not None else []) + ([str(ca)] if ca is not None else [])
        result = subprocess.run(argv, capture_output=True, text=True, timeout=35)
        (work / (name + ".stdout.txt")).write_text(result.stdout, encoding="utf-8")
        (work / (name + ".stderr.txt")).write_text(result.stderr, encoding="utf-8")
        if (result.returncode == 0) != expected:
            print(result.stdout)
            print(result.stderr, file=sys.stderr)
            fail("Native smoke failed: " + name)
        if expected and source is not None and "decoded-samples\t" not in result.stdout:
            fail("No decoded frames in smoke: " + name)
        results.append({"test": name, "result": "passed", "expectedSuccess": expected})
        print("Passed:", name, flush=True)
        return result.stdout

    inventory = probe("inventory")
    groups = {}
    for line in inventory.splitlines():
        kind, _, value = line.partition("\t")
        groups.setdefault(kind, set()).add(value)
    if not any(value.startswith(CAP["ffmpegVersion"]) for value in groups.get("version", set())):
        fail("Runtime FFmpeg version mismatch")
    if not set(CAP["components"]["protocols"]) <= groups.get("protocol", set()):
        fail("Runtime input protocols differ from requested recipe")
    if not set(CAP["components"]["decoders"]) <= groups.get("decoder", set()):
        fail("Runtime audio decoder inventory differs from recipe")
    # AVInputFormat.name contains aliases, e.g. mov,mp4,m4a,3gp,3g2,mj2.
    names = {alias for value in groups.get("demuxer", set()) for alias in value.split(",")}
    # Raw WavPack is registered as wv in configure, as wv in AVInputFormat too.
    if not set(CAP["components"]["demuxers"]) <= names:
        fail("Runtime demuxer inventory differs from recipe: " + str(set(CAP["components"]["demuxers"]) - names))
    for filename in ("source.wav", "pcm24.wav", "float.wav", "lossless.flac", "alac.m4a", "aac.m4a",
                     "adts.aac", "radio.mp3", "vorbis.ogg", "opus.ogg"):
        probe("local-" + filename, media / filename)
    bounded = media / "lossless.flac"
    probe("local-subfile-flac", "subfile,,start,0,end," + str(bounded.stat().st_size) + ",,:file:" + str(media / "lossless-with-tail-tag.flac"))
    handler = partial(FixtureHandler, directory=str(media))
    plain = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    secure = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    context.load_cert_chain(media / "certs/server.pem", media / "certs/server.key")
    secure.socket = context.wrap_socket(secure.socket, server_side=True)
    workers = [threading.Thread(target=server.serve_forever, daemon=True) for server in (plain, secure)]
    for thread in workers:
        thread.start()
    try:
        http = f"http://127.0.0.1:{plain.server_port}"
        https = f"https://localhost:{secure.server_port}"
        ca = media / "certs/ca.pem"
        probe("http-radio-mp3", http + "/radio.mp3")
        probe("https-radio-mp3", https + "/radio.mp3", ca)
        for kind in ("ts", "fmp4", "aes128"):
            probe("http-hls-" + kind, http + "/" + kind + "/stream.m3u8")
            probe("https-hls-" + kind, https + "/" + kind + "/stream.m3u8", ca)
        if args.tls_identity_tests:
            # Initial manifest is trusted; only the segment/key hostname is wrong.
            # This catches loss of TLS policy between HLS nested requests, not only opening the root URL.
            bad_host = f"https://127.0.0.1:{secure.server_port}"
            original = (media / "ts/stream.m3u8").read_text()
            (media / "ts/bad-host.m3u8").write_text("\n".join(
                bad_host + "/ts/" + line if line and not line.startswith("#") else line
                for line in original.splitlines()) + "\n")
            probe("tls-hls-segment-wrong-host", https + "/ts/bad-host.m3u8", ca, expected=False)
            original = (media / "aes128/stream.m3u8").read_text()
            (media / "aes128/bad-key-host.m3u8").write_text(
                original.replace('URI="key.bin"', 'URI="' + bad_host + '/aes128/key.bin"'))
            probe("tls-hls-key-wrong-host", https + "/aes128/bad-key-host.m3u8", ca, expected=False)
            # Use an explicit unrelated CA so a host trust store cannot change the negative test.
            probe("tls-untrusted", https + "/radio.mp3", media / "certs/unrelated-ca.pem", expected=False)
            probe("tls-wrong-host", f"https://127.0.0.1:{secure.server_port}/radio.mp3", ca, expected=False)
            probe("tls-redirect-ip-mismatch", https + "/redirect-ip-mismatch.mp3", ca, expected=False)
    finally:
        for server in (plain, secure):
            server.shutdown()
            server.server_close()
        for thread in workers:
            thread.join(timeout=2)
    report["runtimeSmoke"] = "passed-inventory-local-audio-http-https-hls-aes128"
    report["runtimeTests"] = results
    report["tlsIdentityTests"] = "passed-limited-fixtures" if args.tls_identity_tests else "deferred"
    report["tlsSecurityProductionVerified"] = False
    # Still a candidate: no Android-device, game, bit-accuracy, stress, or real-radio proof here.
    dump(work / "runtime-report.json", report)
    with zipfile.ZipFile(candidate) as jar:
        entries = {name: jar.read(name) for name in jar.namelist()}
    entries[report_name] = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode()
    updated = candidate.with_suffix(".verified.jar")
    write_jar(updated, entries)
    updated.replace(candidate)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    subs = parser.add_subparsers(dest="command", required=True)
    gen = subs.add_parser("fixtures")
    gen.add_argument("destination", type=Path)
    test = subs.add_parser("test")
    test.add_argument("--candidate", type=Path, required=True)
    test.add_argument("--fixtures", type=Path, required=True)
    test.add_argument("--work", type=Path, required=True)
    test.add_argument("--tls-identity-tests", action="store_true", help="optional deferred identity/negative TLS tests; not run by core CI")
    test.add_argument("--cache", type=Path, default=Path(".scratch/ffmpeg-source-cache"))
    args = parser.parse_args()
    fixtures(args.destination) if args.command == "fixtures" else smoke(args)


if __name__ == "__main__":
    main()
