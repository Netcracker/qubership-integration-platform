# Qubership Integration Platform

**Qubership Integration Platform** (QIP) is an integration and orchestration layer, which allows creating business valuable integration flows, providing reach scope of helpful instruments, such as transformation of incoming / outgoing data, process orchestration and mapping between different system formats.

It also provides next capabilities and features:

- Administrating and monitoring, including Logging and Tracing.
- Domain-Driven-Design deployment.
- Ability to work with Service discovery of APIs inside K8s.
- Large scope of automated functions and operations.
- Flexible orchestration of Inbound and Outbound transactions: loop, split, iterations, parallel execution, etc.

This directory contains Docker compose files designed to run Qubership Integration Platform locally in development mode.

## Service file format round trip

`e2e/specs/env/service-type-roundtrip.spec.ts` exercises the service file format against the running stack. It creates
one service of each of the five kinds, exports and re-imports them in the current format — checking both halves of the
format, the file name and the `$schema` that states the type — then repeats the export with
`CIP_EXPORT_LEGACY_FORMAT=true` and checks what survives the downgrade: plain services only.

```bash
cd e2e && npm test -- --project=env --no-deps --grep "service type round trip"
```

The suite brings the Compose stack up itself, so nothing has to be started first. It sets the legacy flag through a
throwaway compose override and restores the container on every exit path, so it never edits `qip-dev.env` — which
`qip-engine` and `qip-sessions-management` read too. See [e2e/README.md](../../e2e/README.md) for how to read a failure.

## AI documentation sidecar

The `ai` Compose profile includes the AI service and its knowledge sidecar.
The sidecar image packages public documentation from `help/docs/` using the UI's existing Markdown generator.
Build it from the repository root with the sidecar Dockerfile; the Compose build uses the same root context.
The runtime image contains the prepared corpus and full-text index and runs without Node.js or write access.

Public documentation is available through `/v1/documentation/manifest`,
`/v1/documentation/search`, and `/v1/documentation/read` on the sidecar.
The manifest also serves as the Compose readiness probe for public documentation.
The AI service requires this corpus in both public and full modes.
A private knowledge package is additionally required when private Rocky skills are installed.

The public corpus is included in the image at `/documentation`; `QIP_DOCUMENTATION_PATH` selects its location.
The existing private knowledge mount and `QIP_KNOWLEDGE_HOST_PATH` remain separate.
A public deployment does not need a private knowledge package. It supports documentation consultations,
while AI creation and editing remain unavailable without private skills.
Rebuild the sidecar image after changing the manuals so that its source links match the UI documentation.

## Contribution

For the details on contribution, see [Contribution Guide](../../CONTRIBUTING.md). For details on reporting of security issues
see [Security Reporting Process](../../SECURITY.md).

Commits and pool requests should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) strategy.

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in [LICENSE](../../LICENSE) file.
