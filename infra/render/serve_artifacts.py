from __future__ import annotations
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import json
import os

ROOT = Path("/artifacts").resolve()
PORT = int(os.environ.get("PORT", "10000"))

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path in ("/", "/health"):
            payload = json.dumps({
                "service": "pocketpc-home-test-builder",
                "status": "ready",
                "artifacts": sorted(p.name for p in ROOT.iterdir() if p.is_file()),
            }).encode()
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        name = self.path.removeprefix("/")
        target = (ROOT / name).resolve()
        if target.parent != ROOT or not target.is_file():
            self.send_error(404)
            return
        data = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Content-Disposition", f'attachment; filename="{target.name}"')
        self.end_headers()
        self.wfile.write(data)

    def log_message(self, format, *args):
        return

ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
