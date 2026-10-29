from __future__ import annotations

import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

from .catalog import Catalog, inside
from .runs import RunStore, create_run, markdown_report, start_worker


def make_server(catalog: Catalog, store: RunStore, entry: Path, port: int):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args):
            pass

        def send(
            self, body, status=200, content_type="application/json", attachment=False
        ):
            if content_type == "application/json":
                body = json.dumps(body, ensure_ascii=False).encode()
            elif isinstance(body, str):
                body = body.encode()
            self.send_response(status)
            self.send_header(
                "Content-Type",
                content_type
                + ("; charset=utf-8" if content_type.startswith("text/") else ""),
            )
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header(
                "Content-Security-Policy",
                "default-src 'self'; object-src 'none'; frame-ancestors 'none'",
            )
            if attachment:
                self.send_header("Content-Disposition", "attachment")
            self.end_headers()
            self.wfile.write(body)

        def host_valid(self):
            return self.headers.get("Host") in {
                f"127.0.0.1:{self.server.server_port}",
                f"localhost:{self.server.server_port}",
            }

        # Method names follow BaseHTTPRequestHandler dispatch.
        # pylint: disable-next=invalid-name,too-many-return-statements
        def do_GET(self):
            if not self.host_valid():
                return self.send(
                    {"error": "Use the loopback URL printed by the server."}, 403
                )
            path = unquote(urlsplit(self.path).path)
            try:
                if path == "/api/catalog":
                    return self.send(Catalog(catalog.root, catalog.package).describe())
                if path == "/api/runs":
                    return self.send(store.list())
                parts = path.strip("/").split("/")
                if parts[:2] == ["api", "runs"] and len(parts) >= 3:
                    run = store.read(parts[2])
                    if len(parts) == 3:
                        return self.send(run)
                    if parts[3:] == ["report.md"]:
                        return self.send(
                            markdown_report(run),
                            content_type="text/markdown",
                            attachment=True,
                        )
                    if parts[3] == "artifacts" and len(parts) > 4:
                        artifact = inside(
                            store.directory(run["id"]), "/".join(parts[4:])
                        )
                        if not artifact.is_file() or artifact.name.endswith(".lock"):
                            raise FileNotFoundError("Unknown artifact")
                        return self.send(
                            artifact.read_bytes(),
                            content_type="application/octet-stream",
                            attachment=True,
                        )
                names = {
                    "/": "index.html",
                    "/app.js": "app.js",
                    "/style.css": "style.css",
                }
                if path in names:
                    types = {
                        "/": "text/html",
                        "/app.js": "text/javascript",
                        "/style.css": "text/css",
                    }
                    return self.send(
                        (Path(__file__).parent / "web" / names[path]).read_bytes(),
                        content_type=types[path],
                    )
                raise FileNotFoundError("Unknown resource")
            except (ValueError, OSError, KeyError) as exc:
                return self.send({"error": str(exc)}, 404)

        # pylint: disable-next=invalid-name
        def do_POST(self):
            origin = self.headers.get("Origin")
            expected = "http://" + self.headers.get("Host", "")
            if not self.host_valid() or (origin is not None and origin != expected):
                return self.send({"error": "Cross-origin actions are disabled."}, 403)
            try:
                size = int(self.headers.get("Content-Length", "0"))
                if (
                    size < 2
                    or size > 65536
                    or self.headers.get("Content-Type") != "application/json"
                ):
                    raise ValueError("Send a JSON object of at most 64 KiB")
                body = json.loads(self.rfile.read(size))
                if not isinstance(body, dict):
                    raise ValueError("Expected a JSON object")
                path = urlsplit(self.path).path
                if path == "/api/runs":
                    if set(body) - {
                        "profile",
                        "suites",
                        "settings",
                        "allowLlm",
                        "parentRunId",
                    }:
                        raise ValueError("Unknown run option")
                    run = create_run(
                        Catalog(catalog.root, catalog.package),
                        store,
                        body["profile"],
                        body.get("suites"),
                        body.get("settings"),
                        body.get("allowLlm") is True,
                        body.get("parentRunId"),
                    )
                    return self.send(start_worker(store, run["id"], entry), 201)
                parts = path.strip("/").split("/")
                if len(parts) == 4 and parts[:2] == ["api", "runs"]:
                    if parts[3] == "cancel":
                        return self.send(store.cancel(parts[2]))
                    if parts[3] == "diagnose":
                        return self.send(
                            store.diagnose(
                                parts[2],
                                body["failureId"],
                                body["target"],
                                body["diagnosis"],
                            )
                        )
                raise ValueError("Unknown action")
            except (ValueError, OSError, KeyError, TypeError) as exc:
                return self.send({"error": str(exc)}, 400)

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


def serve(catalog: Catalog, store: RunStore, entry: Path, port: int):
    with make_server(catalog, store, entry, port) as server:
        print(f"Test results: http://127.0.0.1:{server.server_port}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
