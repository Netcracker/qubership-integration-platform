# Vendored CIP runtime SDK

This directory contains a read-only copy of the upstream Knowledge SDK. Do not edit it in
`ai-service`; replace it from the upstream source when you need a newer SDK.

## Knowledge SDK

| Field | Value |
|-------|-------|
| Source path (in-repo) | `.dev/materials/experimental-migration/cip_compiler_v2/runtime-knowledge-sdk/core/knowledge_sdk/` |
| Vendored path | `vendor/cip-runtime/knowledge_sdk/` |
| Copy date (UTC) | 2026-07-28 |

Copied with `rsync -a` and verified byte-identical to the source tree (`diff -qr` empty).

## Knowledge Package

The Knowledge Package is private and is not vendored into `ai-service`. Mount an externally
compiled, certified export at `/knowledge` and set `QIP_KNOWLEDGE_PATH=/knowledge`.

| Field | Value |
|-------|-------|
| Local source | `integration-platform-skills/.apm/skills/cip-runtime-context-loader/assets/knowledge-export/` |
| Container path | `/knowledge` |
| Host-path variable | `QIP_KNOWLEDGE_HOST_PATH` |

Do not record local absolute paths that identify a confidential build environment.

Semantic coverage required for sidecar tests includes `CIP:GEN-000005` and `CIP:GEN-000049`.

## Out of scope

Do not vendor compiler modules, skills, addons, release ZIPs, Markdown knowledge sources, or
compiled Knowledge Packages here. The sidecar consumes the SDK from the image and the package
from the read-only mount.
