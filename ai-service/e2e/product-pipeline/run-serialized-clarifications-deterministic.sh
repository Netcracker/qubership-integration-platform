#!/usr/bin/env bash
# Run the scripted clarification flow, then assert the raw SSE and run evidence.
set -euo pipefail

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${DIR}/../../.." && pwd)"

cd "${REPO_ROOT}"

rm -f ai-service/target/serialized-clarifications/evidence.json

mvn -f ai-service/pom.xml -Dtest=SerializedClarificationFlowTest test

python3 -m unittest discover -s ai-service/e2e/product-pipeline -p 'test_serialized_clarifications.py'
