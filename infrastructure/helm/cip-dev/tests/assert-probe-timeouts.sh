#!/usr/bin/env bash
# Asserts that every probe waits at least 5 seconds for an answer and that a liveness probe restarts its
# container only after at least a minute of failures.
set -euo pipefail

CHART="$(cd "$(dirname "$0")/.." && pwd)"
E2E_VALUES="$CHART/../../../e2e/k8s/values.e2e.yaml"
RENDERED="$(mktemp -d)"
trap 'rm -rf "$RENDERED"' EXIT

helm template cip "$CHART" > "$RENDERED/default.yaml"
helm template cip "$CHART" -f "$E2E_VALUES" > "$RENDERED/e2e.yaml"
# The brokers lane turns the four broker subcharts on, as the provisioner does on its command line.
helm template cip "$CHART" -f "$E2E_VALUES" \
    --set kafka.enabled=true --set rabbitmq.enabled=true --set pubsub.enabled=true --set sftp.enabled=true \
    > "$RENDERED/e2e-brokers.yaml"

python3 - "$RENDERED/default.yaml" "$RENDERED/e2e.yaml" "$RENDERED/e2e-brokers.yaml" << 'PY'
import sys

import yaml

MIN_TIMEOUT = 5
MIN_LIVENESS_WINDOW = 60
# Kubernetes defaults for a field the probe leaves out.
DEFAULT_PERIOD, DEFAULT_FAILURE_THRESHOLD = 10, 3

failures = []
for path in sys.argv[1:]:
    render = path.rsplit("/", 1)[-1]
    checked = 0
    for document in yaml.safe_load_all(open(path)):
        pod = document and document.get("spec", {}).get("template", {}).get("spec")
        if not pod:
            continue
        for container in pod.get("initContainers", []) + pod.get("containers", []):
            for kind in ("startupProbe", "livenessProbe", "readinessProbe"):
                probe = container.get(kind)
                if probe is None:
                    continue
                checked += 1
                where = f"{render}: {document['kind']} {document['metadata']['name']}, {container['name']} {kind}"
                timeout = probe.get("timeoutSeconds")
                if timeout is None or timeout < MIN_TIMEOUT:
                    failures.append(f"{where} has timeoutSeconds {timeout}, expected at least {MIN_TIMEOUT}")
                if kind == "livenessProbe":
                    period = probe.get("periodSeconds", DEFAULT_PERIOD)
                    threshold = probe.get("failureThreshold", DEFAULT_FAILURE_THRESHOLD)
                    if period * threshold < MIN_LIVENESS_WINDOW:
                        failures.append(f"{where} restarts after {period} x {threshold} = {period * threshold} s, "
                                        f"expected at least {MIN_LIVENESS_WINDOW} s")
    print(f"checked {checked} probes in {render}")

if failures:
    raise SystemExit("FAIL:\n" + "\n".join(failures))
PY

echo "All probe timeout assertions passed."
