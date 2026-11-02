from http.server import SimpleHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import os
from urllib.parse import urlsplit

PORT = 8768
NONCE = "dGVzdC1ub25jZQ=="
POLICY_PATH = "/policy-probe.html"
POLICY = (
    f"script-src 'nonce-{NONCE}' 'wasm-unsafe-eval'; "
    f"style-src 'nonce-{NONCE}'; object-src 'none'"
)


class Handler(SimpleHTTPRequestHandler):
    def end_headers(self):
        if urlsplit(self.path).path == POLICY_PATH:
            self.send_header("Content-Security-Policy", POLICY)
        super().end_headers()


if __name__ == "__main__":
    os.chdir(Path(__file__).parent)
    ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
