import json
import tempfile
import threading
import unittest
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import Request, urlopen
from unittest.mock import patch

from test_system.catalog import CONTRACT, Catalog
from test_system.runs import RunStore
from test_system.server import make_server


class ServerTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        root = Path(self.tmp.name)
        self.root = root
        mock = patch(
            "test_system.runs.git_identity",
            return_value={"commit": "candidate", "dirty": False},
        )
        mock.start()
        self.addCleanup(mock.stop)
        (root / "ai-service/e2e").mkdir(parents=True)
        (root / "ai-service/e2e/suites.json").write_text(
            json.dumps(
                {
                    "contract": CONTRACT,
                    "profiles": {"core-regression": ["one"]},
                    "suites": [
                        {
                            "id": "one",
                            "usesLlm": False,
                            "timeoutSeconds": 30,
                            "command": ["not-executed"],
                        }
                    ],
                }
            )
        )
        self.server = make_server(
            Catalog(root), RunStore(root / "results"), root / "run.py", 0
        )
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.addCleanup(self.close)
        self.url = f"http://127.0.0.1:{self.server.server_port}"

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()

    def test_catalog_and_real_ui_are_served(self):
        with urlopen(self.url + "/api/catalog") as response:
            self.assertEqual(
                json.load(response)["profiles"][0]["id"], "core-regression"
            )
        with urlopen(self.url) as response:
            self.assertIn("AI service tests", response.read().decode())
            self.assertIn(
                "frame-ancestors 'none'", response.headers["Content-Security-Policy"]
            )

    def test_catalog_reload_picks_up_a_changed_manifest(self):
        path = self.root / "ai-service/e2e/suites.json"
        document = json.loads(path.read_text())
        document["profiles"]["new-profile"] = ["one"]
        path.write_text(json.dumps(document))
        with urlopen(self.url + "/api/catalog") as response:
            self.assertIn(
                "new-profile",
                [profile["id"] for profile in json.load(response)["profiles"]],
            )

    def test_uninstalled_bundle_creates_a_blocked_run_visible_in_history(self):
        request = Request(
            self.url + "/api/runs",
            data=b'{"profile":"chain-acceptance"}',
            headers={"Content-Type": "application/json"},
        )
        with urlopen(request) as response:
            run = json.load(response)
        self.assertEqual(run["status"], "BLOCKED")
        with urlopen(self.url + "/api/runs") as response:
            self.assertEqual(json.load(response)[0]["id"], run["id"])

    def test_external_origin_and_artifact_traversal_are_rejected(self):
        request = Request(
            self.url + "/api/runs",
            data=b"{}",
            headers={
                "Content-Type": "application/json",
                "Origin": "https://external.invalid",
            },
        )
        with self.assertRaises(HTTPError) as error:
            urlopen(request)
        self.assertEqual(error.exception.code, 403)
        with self.assertRaises(HTTPError) as error:
            urlopen(self.url + "/api/runs/bad/artifacts/../../../../etc/passwd")
        self.assertEqual(error.exception.code, 404)


if __name__ == "__main__":
    unittest.main()
