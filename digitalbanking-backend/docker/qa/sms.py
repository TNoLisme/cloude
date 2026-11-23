"""In-memory SMS test double for disposable local QA."""
import collections
import hmac
import http.server
import json
import os
import threading
import urllib.parse

TOKEN = os.environ["QA_SMS_TOKEN"]
if len(TOKEN) < 16:
    raise RuntimeError("QA_SMS_TOKEN must have at least 16 characters")

MESSAGES = collections.deque(maxlen=100)
LOCK = threading.Lock()
PHONES = {"0900000011", "0900000012", "0900000021", "0900000022", "0900000023", "0900000024"}


class Mailbox(http.server.BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_POST(self):
        if self.path != "/messages":
            self.send_error(404)
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 8192:
                raise ValueError
            message = json.loads(self.rfile.read(size))
            if not isinstance(message, dict) or message.get("destination") not in PHONES:
                raise ValueError
        except (ValueError, TypeError):
            self.send_error(400)
            return
        with LOCK:
            key = self.headers.get("Idempotency-Key")
            if not any(previous == key for previous, _ in MESSAGES):
                MESSAGES.append((key, message))
        self.send_response(204)
        self.end_headers()

    def do_GET(self):
        if not hmac.compare_digest(self.headers.get("X-QA-SMS-Token", ""), TOKEN):
            self.send_error(403)
            return
        parsed = urllib.parse.urlparse(self.path)
        phone = urllib.parse.parse_qs(parsed.query).get("destination", [None])[0]
        if parsed.path != "/messages" or phone not in PHONES:
            self.send_error(404)
            return
        with LOCK:
            result = [message for _, message in reversed(MESSAGES) if message["destination"] == phone]
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(json.dumps(result).encode())


if __name__ == "__main__":
    http.server.ThreadingHTTPServer(("0.0.0.0", 8080), Mailbox).serve_forever()
