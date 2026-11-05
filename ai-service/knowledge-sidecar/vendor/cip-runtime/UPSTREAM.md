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

Semantic coverage required for sidecar tests includes `CIP:GEN-000005` and `CIP:GEN-000049`.

Limit vendored content to the upstream SDK.
