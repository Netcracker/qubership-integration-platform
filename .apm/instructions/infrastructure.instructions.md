---
description: "Local Docker Compose and Helm stack: commands, ports, and layout."
applyTo: "infrastructure/**"
---

### Project Overview

Infrastructure and deployment assets for the QIP local stack — **no application code**. Holds the Docker Compose definitions for the local dev stack, Helm charts (`qip-dev/`) for Kubernetes, the Nginx reverse-proxy routing config, Consul/OpenSearch/PostgreSQL config, and the `init-db` SQL. Stack: Docker Compose, Helm (camel-k), Nginx, Consul, PostgreSQL, OpenSearch.

### Local Stack Commands

```bash
# Bring up the full local stack (builds Java service images from sibling module dirs)
docker compose -f infrastructure/docker-compose.yml up -d --build

# Add optional brokers/caches via overlay files (compose them together with the base)
docker compose -f infrastructure/docker-compose.yml -f infrastructure/docker-compose.kafka.yml up -d --build
docker compose -f infrastructure/docker-compose.yml -f infrastructure/docker-compose.rabbitmq.yml up -d --build
docker compose -f infrastructure/docker-compose.yml -f infrastructure/docker-compose.redis.yml up -d --build
docker compose -f infrastructure/docker-compose.yml -f infrastructure/docker-compose.pubsub.yml up -d --build

# Tear down
docker compose -f infrastructure/docker-compose.yml down
```

#### `test-service-type-roundtrip.sh`

The live check for the per-type service file names (#553), against a running stack. Creates one service of each of the
five kinds, exports and re-imports through the three v1 endpoint families, then repeats with
`QIP_EXPORT_LEGACY_FORMAT=true`. **The legacy hop restores exactly three** — nothing scans for
`context-service-<id>.yaml` or `mcp-service-<id>.yaml`, in this version or any older one, so those two are written and
discovered by nothing, and the import answers 204.

Two things in it are load bearing and were measured, not read: every import hop deletes first (an import over a live id
is an `UPDATE`, so "the IDs are there" cannot fail otherwise), and it imports the **original archive bytes** (a flat
archive imports as 204 with an empty body, because `ArchiveWriter` builds `services/<id>/` unconditionally).

The flag goes on through a throwaway compose override plus `up -d --no-deps --force-recreate`. `--no-deps` matters:
`qip-runtime-catalog` declares `depends_on: postgres`, and postgres mounts no named volume, so a recreate without it
wipes the database. Never set the flag in `qip-dev.env` — `qip-engine` and `qip-sessions-management` read that file too.

User-facing documentation lives in `README.md`, which is the tracked file; this note is the reasoning behind it.

The UI is **not** containerized — `ui-proxy` (Nginx, port 8080) proxies non-`/api` paths to `host.docker.internal:4200`, so run the UI dev server separately (`npm -w @netcracker/qip-ui run dev`).

#### Helm (Kubernetes) — `qip-dev/`

```bash
# Camel-K operator is a prerequisite (camelk subchart references it)
helm repo add camel-k https://apache.github.io/camel-k/charts/
helm install camel-k camel-k/camel-k -n camel-k --create-namespace --set 'operator.global="true"'

# Install the platform chart
helm install --create-namespace --namespace qip qip infrastructure/qip-dev

# Wipe a namespace's data
kubectl delete all,secrets,configmaps,pvc -n <NAMESPACE> --all
```

Helm UI is exposed via NodePort at `http://localhost:30080/` (port set in `qip-dev/values.yaml`); like Compose it only proxies to a locally-served UI. `values.yaml` toggles `qip.deploy.classic` (Spring engine) vs `qip.deploy.micro` (Quarkus micro-engine, image `ghcr.io/netcracker/qubership-integration-micro-engine:latest`).

### Structure

```text
docker-compose.yml          # base local stack: runtime-catalog, engine, sessions-management,
                            # testing-service, ui-proxy, postgres, opensearch, consul
docker-compose.kafka.yml    # optional: zookeeper + kafka (9092) + akhq UI (8099)
docker-compose.rabbitmq.yml # optional: rabbitmq (5672) + management UI (15672)
docker-compose.redis.yml    # optional: redis (6379)
docker-compose.pubsub.yml   # optional: GCP Pub/Sub emulator (8085)
qip-dev.env / engine-dev.env # env files mounted into the Spring Boot service containers
nginx/
  nginx.conf                # http block; includes routes.conf
  routes.conf               # reverse-proxy routing (see Platform Context)
consul/
  server.json               # single-node bootstrap config (UI enabled, gossip encrypt key)
  consul-acl.json
opensearch/opensearch.yml   # single-node OpenSearch config
init-db/init.sql            # creates engine_qrtz_db + engine_checkpoints_db
kafka-ssl/                  # client/server cert dirs (mounted into engine)
qip-dev/                    # Helm umbrella chart (version 0.0.1) + per-component subcharts
  charts/{camelk,consul,opensearch,postgres,ui,qip-engine,qip-runtime-catalog,qip-sessions-management}
docs/adr/                   # architecture decision records (0001 PostgreSQL types)
```

#### Compose ports (host → container)

| Service | Port(s) | Notes |
|---|---|---|
| ui-proxy (Nginx) | 8080 | single entrypoint; proxies UI to host:4200 |
| qip-runtime-catalog | 8091 → 8080, 5006 → 5005 | second port = JDWP debug |
| qip-engine | 8092 → 8080, 5007 → 5005 | mounts `kafka-ssl/client` |
| qip-sessions-management | 8093 → 8080, 5008 → 5005 | |
| qip-testing-service | 8095 → 8080 | Go, no debug port; `read_only`, `PRODUCTION_MODE=false` |
| postgres | 5432 | image `postgres`, user/pass/db `postgres` |
| opensearch | 9200, 9300 | `opensearchproject/opensearch:2.18.0`, single-node |
| consul | 8500, 8600 (tcp/udp) | `hashicorp/consul:1.15.4`, `-bootstrap-expect=1` |

The three Java services run Spring profile `development`, build from their module dirs, and have actuator healthchecks at `/actuator/health`. `qip-testing-service` is the Go one: it builds from `../testing-service`, runs `read_only`, and answers its healthcheck at `/health`.

### Platform Context

This module orchestrates the local/dev deployment of the whole platform; it contains no service logic of its own. See `README.md` for the repository layout.

The base `docker-compose.yml` builds and runs four services from **in-repo module dirs** via relative build
contexts — `../runtime-catalog`, `../engine`, `../sessions-management` and `../testing-service` (one level up
from `infrastructure/`, i.e. the monorepo root). After monorepo consolidation these are sibling directories
inside this repo, not separate clones.

It also stands up the shared infra they depend on: PostgreSQL (catalog + `engine_qrtz_db` /
`engine_checkpoints_db` created by `init-db/init.sql`), OpenSearch (engine session recording +
sessions-management storage), and Consul (config + deployment publishing). Optional brokers and caches (Kafka,
RabbitMQ, Redis, Pub/Sub) are opt-in via overlay compose files for chains that use them.

`nginx/routes.conf` is the single entrypoint (port 8080) and encodes the platform's API routing: `^/api/{ver}/.*/catalog/...` and `variables-management` / `systems-catalog` → `runtime-catalog:8080`; `.../sessions-management` → `sessions-management:8080`; `.../engine` → `engine:8080`; everything else (the UI and `/doc/` help assets) proxies to `host.docker.internal:4200` (the locally-served UI/Vite dev server).
