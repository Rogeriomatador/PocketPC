#!/usr/bin/env python3
from http.server import BaseHTTPRequestHandler, HTTPServer
import json, os

class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path not in ("/", "/health"):
            self.send_response(404); self.end_headers(); return
        body=json.dumps({
            "service":"PocketPC Render Builder",
            "status":"READY",
            "sourceRevision":os.environ.get("RENDER_GIT_COMMIT","UNKNOWN"),
            "artifactsExposed":False,
        }).encode()
        self.send_response(200)
        self.send_header("Content-Type","application/json")
        self.send_header("Content-Length",str(len(body)))
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, fmt, *args):
        pass

port=int(os.environ.get("PORT","10000"))
HTTPServer(("0.0.0.0",port),Handler).serve_forever()
