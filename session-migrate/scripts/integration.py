#!/usr/bin/env python3
"""Real kmet PTY/session/provider tests. No external model, credentials or graphics claims."""
import argparse
import hashlib
import http.server
import json
import os
from pathlib import Path
import pty
import select
import shutil
import struct
import subprocess
import tempfile
import threading
import time
import fcntl
import termios
import tty

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


class Provider(http.server.BaseHTTPRequestHandler):
    requests = []
    lock = threading.Lock()

    def log_message(self, *_):
        pass

    def do_POST(self):
        assert self.path == "/v1/chat/completions", self.path
        assert self.headers.get("Authorization") == "Bearer dummy-not-a-credential"
        request = json.loads(self.rfile.read(int(self.headers["Content-Length"])))
        with self.lock:
            self.requests.append(request)
            number = len(self.requests)
        marker = f"MIGRATION_CONTINUATION_{number}"
        chunks = [
            {"id": f"local-{number}", "choices": [{"index": 0, "delta": {"role": "assistant", "content": marker}, "finish_reason": None}]},
            {"id": f"local-{number}", "choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]},
            {"id": f"local-{number}", "choices": [], "usage": {"prompt_tokens": 30, "completion_tokens": 5, "total_tokens": 35}},
        ]
        body = "".join("data: " + json.dumps(chunk) + "\n\n" for chunk in chunks) + "data: [DONE]\n\n"
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Content-Length", str(len(body.encode())))
        self.end_headers()
        self.wfile.write(body.encode())


class Kmet:
    def __init__(self, command, cwd, env, output, probes):
        self.master, slave = pty.openpty()
        fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack("HHHH", 50, 180, 0, 0))
        tty.setraw(slave)  # Preserve CR as Enter instead of Linux ICRNL → Shift+Enter.
        def own_terminal():
            os.setsid()
            fcntl.ioctl(slave, termios.TIOCSCTTY, 0)

        self.process = subprocess.Popen(command, cwd=cwd, env=env, stdin=slave, stdout=slave, stderr=slave, preexec_fn=own_terminal)
        os.close(slave)
        self.output = output
        self.probes = probes
        self.log = bytearray()
        self.sequence = 0
        self.closed = False
        try:
            self.wait(lambda: b"migration-test" in self.log, "interactive startup", timeout=90)
        except BaseException:
            self.close()
            raise

    def drain(self):
        if select.select([self.master], [], [], 0.03)[0]:
            try:
                data = os.read(self.master, 131072)
            except OSError:
                data = b""
            self.log.extend(data)
            if b"\x1b[6n" in data:
                os.write(self.master, b"\x1b[1;1R")
            if b"\x1b[c" in data:
                os.write(self.master, b"\x1b[?62c")
            if b"\x1b[?u" in data:
                os.write(self.master, b"\x1b[?0u")

    def wait(self, predicate, label, timeout=40):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            self.drain()
            if predicate():
                return
            if self.process.poll() is not None:
                raise AssertionError(f"kmet exited during {label}: {self.log[-6000:].decode(errors='replace')}")
        raise AssertionError(f"timeout: {label}: {self.log[-6000:].decode(errors='replace')}")

    def send(self, line):
        # The editor treats a multi-byte chunk containing Enter as pasted text.
        # Deliver text and the actual keypress as distinct terminal reads.
        os.write(self.master, line.encode())
        time.sleep(0.1)
        self.drain()
        os.write(self.master, b"\x1b[13u")  # Unambiguous Kitty Enter (not LF/Shift+Enter).
        time.sleep(0.1)
        self.drain()

    def checkpoint(self):
        self.sequence += 1
        tag = str(self.sequence)
        found = []
        self.send("/migration-probe " + tag)

        def read_probe():
            if self.probes.exists():
                for line in self.probes.read_text().splitlines():
                    try:
                        row = json.loads(line)
                    except json.JSONDecodeError:
                        continue
                    if row.get("tag") == tag:
                        found.append(row)
                        return True
            return False

        self.wait(read_probe, f"probe {tag}")
        return found[-1]

    def command(self, line):
        self.send(line)
        return self.checkpoint()

    def continue_local(self, number):
        self.send("Continue using only the local dummy provider.")
        self.wait(lambda: len(Provider.requests) >= number, "local provider request")
        for _ in range(40):
            time.sleep(0.1)
            row = self.checkpoint()
            if row["idle"] and f"MIGRATION_CONTINUATION_{number}" in json.dumps(row["branch"]):
                return row
        raise AssertionError("assistant continuation did not reach the persisted idle session")

    def close(self):
        if self.closed:
            return
        self.closed = True
        if self.process.poll() is None:
            self.send("/quit")
            deadline = time.monotonic() + 8
            while self.process.poll() is None and time.monotonic() < deadline:
                self.drain()
            if self.process.poll() is None:
                self.process.terminate()
                self.process.wait(timeout=8)
        self.drain()
        self.output.write_bytes(self.log)
        os.close(self.master)


def compact_source(path):
    def message(id_, parent, role, text):
        return {"type": role, "uuid": id_, "parentUuid": parent, "sessionId": "compacted-source", "timestamp": "2026-01-01T00:00:00Z", "message": {"role": role, "content": text}}
    rows = [message("old", None, "user", "DO_NOT_SEND_PRECOMPACTION_HISTORY"),
            {"type": "system", "subtype": "compact_boundary", "uuid": "boundary", "logicalParentUuid": "old"},
            dict(message("summary", "boundary", "user", "NATIVE_COMPACTION_SUMMARY"), isCompactSummary=True),
            message("new", "summary", "user", "POST_COMPACTION_CONTEXT")]
    path.write_text("".join(json.dumps(row) + "\n" for row in rows))


def run(args, output):
    output.mkdir(parents=True, exist_ok=True)
    home = output / "home"
    home.mkdir()
    agent = home / "agent"
    agent.mkdir()
    source = home / "source with spaces.jsonl"
    shutil.copyfile(ROOT / "testdata/claude-native.jsonl", source)
    source_uuid = "73fea258-9467-4a17-877b-ef6bcd0898b7"
    uuid_source = home / "claude" / "projects" / "fixture" / (source_uuid + ".jsonl")
    uuid_source.parent.mkdir(parents=True)
    shutil.copyfile(source, uuid_source)
    compact = home / "compact.jsonl"
    compact_source(compact)
    bad = home / "malformed.jsonl"
    bad.write_text('{"PRIVATE_SENTINEL": invalid}\n')
    original_hashes = {p: digest(p) for p in [source, uuid_source, compact, bad]}
    probes = output / "probes.jsonl"
    helper = home / "migration_probe.clj"
    shutil.copyfile(ROOT / "testdata/migration_probe.clj", helper)
    # EDN accepts JSON quoted strings, so no shell or reader interpolation.
    (agent / "settings.edn").write_text(
        '{:packages [' + json.dumps(str(ROOT)) + ' ' + json.dumps(str(helper)) + '] :provider :migration-local :model "migration-test" :thinking :off :auto-compact false :retry-enabled false}\n')
    server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Provider)
    threading.Thread(target=server.serve_forever, daemon=True).start()
    env = {"PATH": os.environ["PATH"], "HOME": str(home), "TERM": "xterm-256color", "LANG": "C.UTF-8",
           "XDG_CONFIG_HOME": str(home / "config"), "XDG_CACHE_HOME": str(home / "cache"),
           "KMET_CODING_AGENT_DIR": str(agent), "MIGRATION_TEST_URL": f"http://127.0.0.1:{server.server_port}/v1",
           "MIGRATION_TEST_PROBES": str(probes), "CLAUDE_CONFIG_DIR": str(home / "claude")}
    # Trust-store paths are not credentials; Nix-provided git/curl need them.
    env.update({key: os.environ[key] for key in ["NIX_SSL_CERT_FILE", "SSL_CERT_FILE", "SSL_CERT_DIR", "GIT_SSL_CAINFO"] if key in os.environ})
    if args.artifact:
        base = [args.executable, str(args.artifact.resolve())] if args.host == "bb" else [str(args.artifact.resolve())]
    elif args.host == "bb":
        base = [args.executable, "--config", str(args.kmet_source / "bb.edn"), "start"]
    else:
        base = [args.executable, "start"]
    base += ["--provider", "migration-local", "--model", "migration-test", "--thinking", "off"]
    sessions = lambda: set(agent.glob("sessions/**/*.ednl"))
    process = None
    try:
        process = Kmet(base, args.kmet_source, env, output / "first.pty.log", probes)
        initial = process.checkpoint()
        assert "session-migrate" in initial["commands"], "The importer did not load"
        before = sessions()
        inspected = process.command(f'/session-migrate inspect claude "{source}"')
        assert inspected["leaf"] == initial["leaf"] and sessions() == before
        assert not Provider.requests
        uuid_inspected = process.command(f'/session-migrate inspect claude {source_uuid}')
        assert uuid_inspected["leaf"] == initial["leaf"] and sessions() == before
        duplicate = home / "claude" / "projects" / "ambiguous" / (source_uuid + ".jsonl")
        duplicate.parent.mkdir()
        shutil.copyfile(source, duplicate)
        ambiguous = process.command(f'/session-migrate inspect claude {source_uuid}')
        assert ambiguous["leaf"] == initial["leaf"] and sessions() == before and not Provider.requests
        assert b"resolved to 2" in process.log
        saved = process.command(f'/session-migrate save claude "{source}"')
        assert saved["leaf"] == initial["leaf"]
        assert len(sessions() - before) == 1
        before_import = sessions()
        imported = process.command(f'/session-migrate import claude "{source}"')
        assert imported["name"] == "repair-event-window-boundary", imported
        imported_path, = sessions() - before_import
        manifest = json.loads(Path(str(imported_path) + ".migration.json").read_text())
        assert manifest["source"]["source-sha256"] == original_hashes[source]
        assert manifest["initial-target-sha256"] == digest(imported_path)
        assert all(p.stat().st_mode & 0o777 == 0o600 for p in [imported_path, Path(str(imported_path) + ".migration.json")])
        assert len(imported["branch"]) == 12 and len(Provider.requests) == 0
        leaf = imported["leaf"]
        before_bad = sessions()
        refused = process.command(f'/session-migrate import claude "{bad}"')
        assert refused["leaf"] == leaf and sessions() == before_bad and not Provider.requests
        assert "PRIVATE_SENTINEL" not in process.log.decode(errors="replace")
        continued = process.continue_local(1)
        assert continued["name"] == imported["name"]
        wire = json.dumps(Provider.requests[0])
        assert "tool_calls" in wire and "tool_call_id" in wire and "image_url" in wire
        assert "thinking" not in wire and "PRIVATE_SENTINEL" not in wire
        assert not any("MIGRATION_CONTINUATION" in json.dumps(request) for request in Provider.requests)
        process.close()
        process = Kmet(base + ["--session", str(imported_path)], args.kmet_source, env, output / "reopen.pty.log", probes)
        # Tags need to stay globally distinct across the same probe log.
        process.sequence = 100
        reopened = process.checkpoint()
        assert reopened["name"] == imported["name"]
        assert "MIGRATION_CONTINUATION_1" in json.dumps(reopened["branch"])
        assert len(Provider.requests) == 1
        process.continue_local(2)
        assert "MIGRATION_CONTINUATION_1" in json.dumps(Provider.requests[1])
        before_compact = sessions()
        compacted = process.command(f'/session-migrate import claude "{compact}"')
        assert any(row["role"] == "compaction" for row in compacted["branch"])
        compact_path, = sessions() - before_compact
        compact_manifest = json.loads(Path(str(compact_path) + ".migration.json").read_text())
        assert compact_manifest["source"]["preserved"]["compactions"] == 1
        process.continue_local(3)
        compact_wire = json.dumps(Provider.requests[2])
        assert "NATIVE_COMPACTION_SUMMARY" in compact_wire and "POST_COMPACTION_CONTEXT" in compact_wire
        assert "DO_NOT_SEND_PRECOMPACTION_HISTORY" not in compact_wire
        assert all(digest(path) == sha for path, sha in original_hashes.items())
        result = {"host": args.host, "execution": str(args.artifact.resolve()) if args.artifact else "source",
                  "upstream": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.kmet_source, text=True).strip(),
                  "checks": ["inspect-no-mutation", "uuid-lookup-and-ambiguity", "save-no-switch", "native-import-switch", "malformed-refusal", "private-manifest",
                             "historical-tools-and-image-wire", "local-continuation", "reopen-and-continue", "native-compaction-context", "source-immutability"],
                  "provider-requests": len(Provider.requests), "native-fixture-sha256": original_hashes[source]}
        (output / "requests.json").write_text(json.dumps(Provider.requests, indent=2))
        (output / "result.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result))
    finally:
        if process:
            process.close()
        server.shutdown()
        server.server_close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kmet-source", type=Path, required=True)
    parser.add_argument("--host", choices=["bb", "jolt"], default="bb")
    parser.add_argument("--executable", default="bb")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--artifact", type=Path, help="Built kmet.jar (bb) or native executable (jolt)")
    arguments = parser.parse_args()
    arguments.kmet_source = arguments.kmet_source.resolve()
    if arguments.output:
        run(arguments, arguments.output.resolve())
    else:
        with tempfile.TemporaryDirectory(prefix="kmet-session-migrate-integration-") as directory:
            run(arguments, Path(directory))
