from __future__ import annotations

import hashlib
import json
import re
import sys
from pathlib import Path
from urllib.parse import urlsplit

CONTRACT = "qip-ai-tests/v1"
ID = re.compile(r"[a-z0-9][a-z0-9._-]{0,119}\Z")


def read_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def tree_digest(root: Path) -> str | None:
    if not root.is_dir():
        return None
    tree = hashlib.sha256()
    for path in sorted(root.rglob("*")):
        if path.is_file() and "__pycache__" not in path.parts and path.suffix != ".pyc":
            tree.update(str(path.relative_to(root)).encode())
            tree.update(bytes.fromhex(digest(path)))
    return tree.hexdigest()


def inside(root: Path, relative: str) -> Path:
    path = (root / relative).resolve()
    if not path.is_relative_to(root.resolve()):
        raise ValueError("Path leaves its declared root")
    return path


class Catalog:
    def __init__(self, service_root: Path, package: Path | None = None):
        self.root = service_root.resolve()
        self.package = (package or self.root / ".private/rocky-skills").resolve()
        self.profiles: dict[str, list[str]] = {}
        self.suites: dict[str, dict] = {}
        self.sources: list[dict] = []
        self._load(self.root / "ai-service/e2e/suites.json", "service")
        self.package_error = "Install the skills testing bundle to run this profile."
        manifest = self.package / "testing/suites.json"
        if manifest.is_file():
            try:
                metadata = read_json(manifest)
                expected_version = (
                    metadata.get("skillsPackageVersion")
                    if isinstance(metadata, dict)
                    else None
                )
                if expected_version and expected_version != read_json(
                    self.package / "manifest.json"
                ).get("version"):
                    raise ValueError(
                        "The testing bundle targets a different skills package version"
                    )
                self._load(manifest, "skills")
                self.package_error = ""
            except (ValueError, OSError, KeyError, TypeError) as exc:
                self.package_error = f"Cannot load the skills testing bundle: {exc}"

    def _load(self, path: Path, owner: str):
        document = read_json(path)
        if not isinstance(document, dict):
            raise ValueError("A test manifest must be an object")
        if document.get("contract") != CONTRACT:
            raise ValueError(f"Unsupported test manifest contract in {path}")
        suites = {}
        if not isinstance(document.get("suites"), list) or not isinstance(
            document.get("profiles"), dict
        ):
            raise ValueError("A test manifest needs suites and profiles")
        for row in document["suites"]:
            if not isinstance(row, dict):
                raise ValueError("A suite must be an object")
            name = row["id"]
            if (
                not isinstance(name, str)
                or not ID.fullmatch(name)
                or name in self.suites
                or name in suites
            ):
                raise ValueError(f"Invalid or duplicate suite ID: {name}")
            if not isinstance(row.get("usesLlm"), bool):
                raise ValueError(f"usesLlm is required for {name}")
            argv = row["command"]
            if (
                not isinstance(argv, list)
                or not argv
                or any(not isinstance(x, str) for x in argv)
            ):
                raise ValueError(f"command must be an argument array for {name}")
            if row.get("format", "command") not in {
                "command",
                "junit",
                "jest",
                "result",
            }:
                raise ValueError(f"Unsupported result format for {name}")
            if (
                not isinstance(row.get("timeoutSeconds"), int)
                or row["timeoutSeconds"] < 1
            ):
                raise ValueError(f"timeoutSeconds must be positive for {name}")
            suites[name] = {**row, "owner": owner}
        profiles = document["profiles"]
        for name, selection in profiles.items():
            if not ID.fullmatch(name) or name in self.profiles:
                raise ValueError(f"Invalid or duplicate profile: {name}")
            if (
                not isinstance(selection, list)
                or not selection
                or any(x not in suites and x not in self.suites for x in selection)
            ):
                raise ValueError(f"Unknown or empty suite selection for {name}")
        self.suites.update(suites)
        self.profiles.update(profiles)
        self.sources.append(
            {
                "owner": owner,
                "path": str(path),
                "sha256": digest(path),
                "version": document.get("version"),
            }
        )

    def describe(self) -> dict:
        profiles = [
            {"id": name, "suites": ids, "available": True}
            for name, ids in self.profiles.items()
        ]
        for name in (
            "chain-acceptance",
            "recovery",
            "recovery-acceptance",
            "recovery-acceptance-check",
            "chain-offline",
            "planner",
            "executor",
            "generators",
        ):
            if name not in self.profiles:
                profiles.append(
                    {
                        "id": name,
                        "suites": [],
                        "available": False,
                        "reason": self.package_error,
                    }
                )
        return {
            "contract": CONTRACT,
            "profiles": profiles,
            "suites": list(self.suites.values()),
            "sources": self.sources,
        }

    def plan(
        self,
        profile: str,
        selection: list[str] | None = None,
        settings: dict | None = None,
    ) -> dict:
        if settings is not None and (
            not isinstance(settings, dict)
            or any(not isinstance(v, str) for v in settings.values())
        ):
            raise ValueError("Settings must be a string map")
        if set(settings or {}) - {
            "base_url",
            "evaluator_url",
            "knowledge_package",
            "model",
            "image",
        }:
            raise ValueError("Unknown execution setting")
        if selection is not None and (
            not isinstance(selection, list)
            or any(not isinstance(x, str) for x in selection)
        ):
            raise ValueError("Suite selection must be an array of IDs")
        options = dict(settings or {})
        for key, fallback in (
            ("base_url", "http://localhost:8094"),
            ("evaluator_url", "http://localhost:8100"),
        ):
            value = options.setdefault(key, fallback)
            parsed = urlsplit(value)
            if (
                parsed.scheme not in {"http", "https"}
                or not parsed.hostname
                or parsed.username
                or parsed.password
            ):
                raise ValueError(f"{key} must be an HTTP URL without credentials")
        options.setdefault(
            "knowledge_package", str(self.root / ".private/rocky-knowledge")
        )
        options.setdefault("model", "unspecified")
        options.setdefault("image", "unspecified")
        if profile == "recovery-acceptance":
            if options["model"] == "unspecified":
                options["model"] = "kimi"
            if options["model"] not in {"ordinary", "kimi", "both"}:
                raise ValueError(
                    "Recovery acceptance needs model ordinary, kimi, or both."
                )
        if profile not in self.profiles:
            if profile in {
                "chain-acceptance",
                "recovery",
                "recovery-acceptance",
                "recovery-acceptance-check",
                "chain-offline",
                "planner",
                "executor",
                "generators",
            }:
                return {
                    "profile": profile,
                    "suites": [],
                    "blocked": self.package_error,
                    "settings": options,
                }
            raise ValueError(f"Unknown profile: {profile}")
        selected = list(dict.fromkeys(selection or self.profiles[profile]))
        if any(name not in self.profiles[profile] for name in selected):
            raise ValueError("Selected suites must belong to the profile")
        if any(self.suites[name].get("requiresCore") for name in selected):
            selected = list(
                dict.fromkeys(
                    [
                        name
                        for name in self.profiles[profile]
                        if self.suites[name].get("gate")
                    ]
                    + selected
                )
            )
        return {
            "profile": profile,
            "suites": [self.suites[name] for name in selected],
            "blocked": None,
            "settings": options,
        }

    def variables(self, task_dir: Path, settings: dict) -> dict[str, str]:
        return {
            "service_root": str(self.root),
            "package_root": str(self.package),
            "bundle_root": str(self.package / "testing"),
            "task_dir": str(task_dir),
            "python": sys.executable,
            **settings,
        }
