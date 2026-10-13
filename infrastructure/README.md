# Qubership Integration Platform

**Qubership Integration Platform** (QIP) is an integration and orchestration layer, which allows creating business valuable integration flows, providing reach scope of helpful instruments, such as transformation of incoming / outgoing data, process orchestration and mapping between different system formats.

It also provides next capabilities and features:

- Administrating and monitoring, including Logging and Tracing.
- Domain-Driven-Design deployment.
- Ability to work with Service discovery of APIs inside K8s.
- Large scope of automated functions and operations.
- Flexible orchestration of Inbound and Outbound transactions: loop, split, iterations, parallel execution, etc.

This directory contains Docker compose files designed to run Qubership Integration Platform locally in development mode.

## Kafka listeners

`docker-compose.kafka.yml` runs one KRaft node with a listener per security protocol. Clients inside the Compose network
connect to `kafka`; a client on the host connects to `localhost:9092`.

| Listener | Port | Protocol | Clients |
| --- | --- | --- | --- |
| `INTERNAL` | 29092 | `PLAINTEXT` | chains, inter-broker traffic, AKHQ |
| `CONTROLLER` | 29093 | `PLAINTEXT` | the KRaft controller |
| `EXTERNAL` | 9092 | `PLAINTEXT` | clients on the host, at `localhost:9092` |
| `AUTH` | 29094 | `SASL_PLAINTEXT` | user `e2e`, password `e2e-secret` |
| `TLS` | 29095 | `SSL` | clients that trust `kafka-ssl/client/ca.crt` |
| `AUTHTLS` | 29096 | `SASL_SSL` | both of the above |

`AUTH` and `AUTHTLS` accept the `PLAIN`, `SCRAM-SHA-256`, and `SCRAM-SHA-512` mechanisms. The SCRAM credentials are
written when the container formats its storage, so they survive a restart and disappear with the container.

The server certificate covers the names `kafka` and `localhost`. `kafka-ssl/generate.sh` writes it, its key, and the CA
certificate; the files are test material and are committed. The engine mounts `kafka-ssl/client` as its certificate
directory, so it trusts the CA without further setup.

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
`qip-engine` and `qip-sessions-management` read too. See [e2e/README.md](../e2e/README.md) for how to read a failure.

## Contribution

For the details on contribution, see [Contribution Guide](../CONTRIBUTING.md). For details on reporting of security issues
see [Security Reporting Process](../SECURITY.md).

Commits and pool requests should follow [Conventional Commits](https://www.conventionalcommits.org/en/v1.0.0/) strategy.

## Licensing

This software is licensed under Apache License Version 2.0. License text is located in [LICENSE](../LICENSE) file.
