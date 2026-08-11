# ai-service E2E

CREATE acceptance tests live under `product-pipeline/`.

## Offline

```bash
ai-service/e2e/test-offline.sh
```

This command runs the live-runner contract tests and the stubbed quality gate without Docker,
network access, or a private knowledge package.

## Live

```bash
ai-service/e2e/product-pipeline/run-quality-gate.sh \
  --runs 1 \
  --knowledge-package integration-platform-skills/.apm/skills/cip-runtime-context-loader/assets/knowledge-export \
  --report-dir /tmp/ai-service-create-gate \
  --base-url http://localhost:8094
```

The live gate requires the private `integration-platform-skills` directory and local LLM
credentials. See `product-pipeline/README.md` for the evidence and catalog-retention contracts.
