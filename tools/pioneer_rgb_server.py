#!/usr/bin/env python3
"""
Local web server for pioneer_rgb_viewer.html.

Queries a Pioneer AVR over telnet (?RGBxx, ?RGC, ?F) and returns JSON for the page.
Browsers cannot open raw telnet sockets, so this script acts as a small local proxy.

Usage:
    cd tools
    python pioneer_rgb_server.py

Then open: http://localhost:8765/
"""

from __future__ import annotations

import json
import socket
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

DEFAULT_IP = "192.168.86.74"
DEFAULT_PORT = 23
WEB_HOST = "127.0.0.1"
WEB_PORT = 8765
HTML_FILE = Path(__file__).with_name("pioneer_rgb_viewer.html")


def telnet_query(ip: str, port: int, cmd: str, wait: float = 0.35) -> str:
    with socket.create_connection((ip, port), timeout=5) as sock:
        sock.sendall((cmd + "\r").encode("ascii"))
        time.sleep(wait)
        sock.settimeout(1.5)
        chunks: list[bytes] = []
        try:
            while True:
                chunk = sock.recv(4096)
                if not chunk:
                    break
                chunks.append(chunk)
        except socket.timeout:
            pass
    return b"".join(chunks).decode("ascii", errors="replace")


def split_lines(raw: str) -> list[str]:
    lines: list[str] = []
    for part in raw.replace("\r", "\n").split("\n"):
        part = part.strip()
        if part and part != "R" and not part.startswith("E0"):
            lines.append(part)
    return lines


def parse_rgb_line(line: str, expected_index: int | None = None) -> dict[str, Any]:
    entry: dict[str, Any] = {
        "line": line,
        "parsed_code": None,
        "parsed_name": None,
        "matches_query": False,
    }
    if not line.startswith("RGB") or len(line) <= 6:
        return entry
    entry["parsed_code"] = line[3:5]
    entry["parsed_name"] = line[6:].strip()
    if expected_index is not None:
        expected = f"{expected_index:02d}"
        entry["matches_query"] = entry["parsed_code"] == expected
    return entry


def query_rgb(ip: str, port: int, index: int) -> dict[str, Any]:
    query = f"?RGB{index:02d}"
    raw = telnet_query(ip, port, query)
    lines = split_lines(raw)
    rgb_lines = [parse_rgb_line(line, index) for line in lines if line.startswith("RGB")]
    matched = next((r for r in rgb_lines if r["matches_query"]), None)
    return {
        "query": query,
        "index": index,
        "raw": raw.strip(),
        "lines": lines,
        "rgb_lines": rgb_lines,
        "matched_name": matched["parsed_name"] if matched else None,
        "matched_code": matched["parsed_code"] if matched else None,
    }


def query_cmd(ip: str, port: int, cmd: str) -> dict[str, Any]:
    raw = telnet_query(ip, port, cmd)
    return {
        "cmd": cmd,
        "raw": raw.strip(),
        "lines": split_lines(raw),
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "PioneerRGBViewer/1.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        print(f"{self.address_string()} - {fmt % args}")

    def _send_json(self, status: int, payload: dict[str, Any]) -> None:
        body = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_json(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            return {}
        data = self.rfile.read(length)
        return json.loads(data.decode("utf-8"))

    def do_GET(self) -> None:
        path = urlparse(self.path).path
        if path in ("/", "/index.html"):
            if not HTML_FILE.exists():
                self._send_json(404, {"error": f"Missing {HTML_FILE.name}"})
                return
            body = HTML_FILE.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.end_headers()
            self.wfile.write(body)
            return
        if path == "/api/config":
            self._send_json(200, {
                "default_ip": DEFAULT_IP,
                "default_port": DEFAULT_PORT,
            })
            return
        self._send_json(404, {"error": "Not found"})

    def do_POST(self) -> None:
        path = urlparse(self.path).path
        try:
            body = self._read_json()
            ip = str(body.get("ip") or DEFAULT_IP).strip()
            port = int(body.get("port") or DEFAULT_PORT)

            if path == "/api/rgb":
                index = int(body.get("index", 0))
                result = query_rgb(ip, port, index)
                self._send_json(200, result)
                return

            if path == "/api/cmd":
                cmd = str(body.get("cmd") or "").strip()
                if not cmd:
                    self._send_json(400, {"error": "cmd required"})
                    return
                result = query_cmd(ip, port, cmd)
                self._send_json(200, result)
                return

            self._send_json(404, {"error": "Not found"})
        except socket.timeout:
            self._send_json(504, {"error": "Receiver connection timed out"})
        except ConnectionRefusedError:
            self._send_json(503, {"error": f"Connection refused at {ip}:{port}"})
        except OSError as exc:
            self._send_json(503, {"error": str(exc)})
        except Exception as exc:  # noqa: BLE001 - sample tool
            self._send_json(500, {"error": str(exc)})


def main() -> None:
    try:
        httpd = ThreadingHTTPServer((WEB_HOST, WEB_PORT), Handler)
    except OSError as exc:
        print(f"Could not start web server on http://{WEB_HOST}:{WEB_PORT}/")
        print(f"Error: {exc}")
        if getattr(exc, "winerror", None) == 10048 or exc.errno in (48, 98):
            print("Port 8765 is already in use. Stop the other server or change WEB_PORT in this script.")
        raise SystemExit(1) from exc

    print("")
    print("Pioneer RGB viewer")
    print(f"  Open in browser: http://127.0.0.1:{WEB_PORT}/")
    print(f"  Default receiver: {DEFAULT_IP}:{DEFAULT_PORT}")
    print("  Do NOT open pioneer_rgb_viewer.html as a file — use the URL above.")
    print("  Press Ctrl+C to stop.")
    print("")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
