#!/usr/bin/env bash
# Asserts that the engine pod starts only after the release's OpenSearch Service answers over HTTP.
set -euo pipefail

CHART="$(cd "$(dirname "$0")/.." && pwd)"
RENDERED="$(mktemp -d)"
trap 'rm -rf "$RENDERED"' EXIT

helm template cip "$CHART" > "$RENDERED/default.yaml"
helm template cip "$CHART" -f "$CHART/../../../e2e/k8s/values.e2e.yaml" > "$RENDERED/e2e.yaml"

python3 - "$RENDERED/default.yaml" "$RENDERED/e2e.yaml" << 'PY'
import sys

import yaml


def fail(render, message):
    raise SystemExit(f"FAIL ({render}): {message}")


def config_map_ref(env, name):
    entry = next((item for item in env if item["name"] == name), None)
    ref = entry and entry.get("valueFrom", {}).get("configMapKeyRef")
    return ref and (ref["name"], ref["key"])


for path in sys.argv[1:]:
    render = path.rsplit("/", 1)[-1]
    documents = [document for document in yaml.safe_load_all(open(path)) if document]
    named = {(document["kind"], document["metadata"]["name"]): document for document in documents}
    deployment = named.get(("Deployment", "cip-engine-v1")) or fail(render, "no engine Deployment")
    pod = deployment["spec"]["template"]["spec"]
    engine = next(container for container in pod["containers"] if container["name"] == "cip-engine")
    wait = next((container for container in pod.get("initContainers", [])
                 if container["name"] == "wait-for-opensearch"), None)
    if wait is None:
        fail(render, "the engine Deployment has no wait-for-opensearch initContainer")

    for variable in ("OPENSEARCH_HOST", "OPENSEARCH_PORT"):
        ref = config_map_ref(wait.get("env", []), variable)
        if ref != config_map_ref(engine["env"], variable):
            fail(render, f"the initContainer reads {variable} from {ref}, not from the engine's source")
    config = named[("ConfigMap", "cip-env")]["data"]
    host, port = config["OPENSEARCH_HOST"], int(config["OPENSEARCH_PORT"])
    service = named.get(("Service", host)) or fail(render, f"OPENSEARCH_HOST {host} names no Service")
    if port not in [entry["port"] for entry in service["spec"]["ports"]]:
        fail(render, f"Service {host} has no port {port}")
    if service["spec"]["selector"] != named[("Deployment", "cip-opensearch")]["spec"]["selector"]["matchLabels"]:
        fail(render, f"Service {host} does not select the OpenSearch pod")

    script = " ".join(wait["command"])
    for needle in ("until curl", "--fail", "http://$OPENSEARCH_HOST:$OPENSEARCH_PORT/"):
        if needle not in script:
            fail(render, f"the wait script lacks {needle!r}: {script}")
    print(f"OK ({render}): the engine waits for http://{host}:{port}/ in {wait['image']}")
PY

echo "All engine OpenSearch wait assertions passed."
