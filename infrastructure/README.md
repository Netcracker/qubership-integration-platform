# Qubership Integration Platform

**Qubership Integration Platform** (QIP) is an integration and orchestration layer, which allows creating business valuable integration flows, providing reach scope of helpful instruments, such as transformation of incoming / outgoing data, process orchestration and mapping between different system formats.

It also provides next capabilities and features:

- Administrating and monitoring, including Logging and Tracing.
- Domain-Driven-Design deployment.
- Ability to work with Service discovery of APIs inside K8s.
- Large scope of automated functions and operations.
- Flexible orchestration of Inbound and Outbound transactions: loop, split, iterations, parallel execution, etc.

This repository contains Docker compose files designed to run Qubership Integration Platform locally in development mode.

## Service file format round trip

`test-service-type-roundtrip.sh` exercises the service file format against the running stack. It creates one service of
each of the five kinds, exports and re-imports them in the current format — checking both halves of the format, the
file name and the `$schema` that states the type — then repeats the export with `QIP_EXPORT_LEGACY_FORMAT=true` and
checks what survives the downgrade: plain services only.

```bash
docker compose -f infrastructure/docker-compose.yml up -d      # the script refuses to start without it
infrastructure/test-service-type-roundtrip.sh
```

Needs `curl`, `jq`, `unzip` and `docker`. It sets the legacy flag through a throwaway compose override and restores the
container on every exit path, so it never edits `qip-dev.env` — which `qip-engine` and `qip-sessions-management` read
too. The script header explains how to read a failure.

## Contribution

For the details on contribution, see [Contribution Guide](../CONTRIBUTING.md). For details on reporting of security issues
see [Security Reporting Process](../SECURITY.md).

Commits and pool requests should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) strategy.

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in [LICENSE](../LICENSE) file.
