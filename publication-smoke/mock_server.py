"""Local Zealot response fixture for the API 21 publication smoke app."""

from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.parse import parse_qs, urlsplit


class Handler(BaseHTTPRequestHandler):
    def do_GET(self):
        url = urlsplit(self.path)
        query = parse_qs(url.query, keep_blank_values=True)
        if not url.path.endswith("/api/apps/latest") or not all(
            name in query for name in ("channel_key", "bundle_id", "release_version", "build_version")
        ):
            status, body = 400, b'{}'
        elif url.path.startswith("/error/"):
            status, body = 503, b'{}'
        elif url.path.startswith("/current/"):
            status, body = 200, b'{"releases":[]}'
        else:
            status, body = 200, (
                b'{"releases":[{"release_version":"2.0","build_version":"12",'
                b'"install_url":"https://zealot.example.com/install"}]}'
            )
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, *_args):
        # Request URLs contain channel keys; keep them out of terminal logs.
        pass


if __name__ == "__main__":
    server = HTTPServer(("127.0.0.1", 18766), Handler)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
