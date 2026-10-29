---
description: "Local Docker Compose and Helm stack: commands, ports, and layout."
applyTo: "infrastructure/**"
---

### Project Overview

Infrastructure and deployment assets for the QIP local stack — **no application code**. Holds the Docker Compose definitions for the local dev stack under `docker/`, the Helm chart `cip-k8s-dev` (`helm/cip-dev/`) for Kubernetes, the Nginx reverse-proxy routing config, Consul/OpenSearch/PostgreSQL config, and the `init-db` SQL. Stack: Docker Compose, Helm (camel-k), Nginx, Consul, PostgreSQL, OpenSearch.

### Local Stack Commands

```bash
# Bring up the base local stack (builds the service images from the module dirs)
docker compose -f infrastructure/docker/docker-compose.yml up -d --build

# Add optional brokers/caches via overlay files (compose them together with the base)
docker compose -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.kafka.yml up -d --build
docker compose -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.rabbitmq.yml up -d --build
docker compose -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.redis.yml up -d --build
docker compose -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.pubsub.yml up -d --build
docker compose -f infrastructure/docker/docker-compose.yml -f infrastructure/docker/docker-compose.sftp.yml up -d --build

# Add the AI services (qip-ai-service, qip-knowledge-sidecar, minio); --profile ai-e2e adds qip-e2e-evaluator
docker compose -f infrastructure/docker/docker-compose.yml --profile ai up -d --build

# Tear down
docker compose -f infrastructure/docker/docker-compose.yml down
```

#### The service file format round trip

`e2e/specs/env/service-type-roundtrip.spec.ts` checks the per-type service file names (#553) against a running stack.
Its header comment gives the rules it measured, and `docker/README.md` says how to run it. To recreate one service
with a throwaway override, pass `--no-deps`: postgres mounts no named volume, so recreating it with
`qip-runtime-catalog` wipes the database.

The UI is **not** containerized — `ui-proxy` (Nginx, port 8080) proxies non-`/api` paths to `host.docker.internal:4200`, so run the UI dev server separately (`npm -w @netcracker/qip-ui run dev`).

#### Helm (Kubernetes) — `helm/cip-dev/`

```bash
# Camel-K operator is a prerequisite (camelk subchart references it)
helm repo add camel-k https://apache.github.io/camel-k/charts/
helm install camel-k camel-k/camel-k -n camel-k --create-namespace --set-string operator.global=true

# Install the platform chart
helm install --create-namespace --namespace cip cip infrastructure/helm/cip-dev

# Wipe a namespace's data
kubectl delete all,secrets,configmaps,pvc -n <NAMESPACE> --all
```

Pass `operator.global` with `--set-string`: the camel-k chart renders its RBAC only when the value is
the string `true`, and `--set 'operator.global="true"'` stores the quotes too, so the operator gets
no RBAC and an Integration never starts a pod.

The end-to-end suite installs this chart into namespace `cip-e2e` with `e2e/k8s/values.e2e.yaml` and the
values it sets on the command line, so a change under `helm/cip-dev/` also changes what the Kubernetes
target runs. A new value's default must render exactly what the chart rendered before the value
existed; `helm/cip-dev/README.md` lists the values the suite uses under "Values for the platform
services" and "Brokers".

Helm UI is exposed via NodePort at `http://localhost:30080/` (port set in `helm/cip-dev/values.yaml`); like Compose it only proxies to a locally-served UI. `values.yaml` enables both engines by default: `global.qip.deploy.classic.enabled` (Spring engine) and `global.qip.deploy.micro.enabled` (Quarkus micro-engine, image `ghcr.io/netcracker/qubership-integration-micro-engine:latest`).

### Structure

```text
docker/                       # Docker Compose local stack
  README.md                   # user-facing guide to the Compose stack
  docker-compose.yml          # base local stack: runtime-catalog, engine, sessions-management,
                              # testing-service, ui-proxy, postgres, opensearch, consul; profile `ai`
                              # adds ai-service, knowledge-sidecar, minio; `ai-e2e` adds e2e-evaluator
  docker-compose.kafka.yml    # optional: kafka in KRaft mode (9092) + akhq UI (8099); SASL, TLS,
                              # SASL-over-TLS, and TLSv1.3-only listeners, see README.md
                              # "Kafka listeners"
  docker-compose.rabbitmq.yml # optional: rabbitmq (5672) + management UI (15672)
  docker-compose.redis.yml    # optional: redis (6379)
  docker-compose.pubsub.yml   # optional: GCP Pub/Sub emulator (8085)
  docker-compose.sftp.yml     # optional: SFTP server (2222), the e2e file transports' endpoint
  docker-compose.local-env.yml # optional: layers ../.env.local (real LLM credentials) over ai-service-dev.env
  docker-compose.a2a-lab.yml  # optional: turns on the ai-service A2A endpoints (scripts/a2a-lab.sh)
  qip-dev.env / engine-dev.env # env files mounted into the Spring Boot service containers
  ai-service-dev.env          # env file of qip-ai-service and qip-e2e-evaluator
  nginx/
    nginx.conf                # http block; includes routes.conf
    routes.conf               # reverse-proxy routing (see Platform Context)
  consul/
    server.json               # single-node bootstrap config (UI enabled, gossip encrypt key)
    consul-acl.json
  opensearch/opensearch.yml   # single-node OpenSearch config
  init-db/init.sql            # creates engine_qrtz_db, engine_checkpoints_db, and ai_a2a
  kafka-ssl/                  # committed test TLS material that generate.sh writes: client/ca.crt
                              # (mounted into engine), server/kafka.{crt,key} (mounted into kafka);
                              # gitleaks allowlists server/ by path
helm/
  cip-dev/                    # Helm umbrella chart + per-component subcharts
    istio.yaml                # IstioOperator for istioctl install: minimal profile, small requests
    charts/{camelk,consul,gateways,general,opensearch,postgres,ui,cip-engine,cip-runtime-catalog,
            cip-sessions-management,cip-testing-service,cip-ai-service}
    charts/{kafka,rabbitmq,pubsub,sftp}  # brokers, off by default (helm/cip-dev/README.md "Brokers")
    templates/checks.yaml     # fails the render when micro domains mount the engine-certs Secret
                              # while cip-engine.trustedCerts is empty
docs/adr/                     # architecture decision records (0001 PostgreSQL types)
```

#### Compose ports (host → container)

From `docker/docker-compose.yml`:

| Service | Port(s) | Notes |
|---|---|---|
| ui-proxy (Nginx) | 8080 | single entrypoint; proxies UI to host:4200 |
| qip-runtime-catalog | 8091 → 8080, 5006 → 5005 | second port = JDWP debug |
| qip-engine | 8092 → 8080, 5007 → 5005 | mounts `kafka-ssl/client` |
| qip-sessions-management | 8093 → 8080, 5008 → 5005 | |
| qip-testing-service | 8095 → 8080 | Go, no debug port; `read_only`, `PRODUCTION_MODE=false` |
| qip-ai-service | 8094 → 8080 | Quarkus; profile `ai` |
| qip-e2e-evaluator | 8100 → 8099 | profile `ai-e2e` |
| minio | 9000, 9001 | profile `ai`; the only named volume, `minio-data` |
| postgres | 5432 | image `postgres`, user/pass/db `postgres` |
| opensearch | 9200, 9300 | `opensearchproject/opensearch`, single-node |
| consul | 8500, 8600 (tcp/udp) | `hashicorp/consul`, `-bootstrap-expect=1` |

The three Java services run Spring profile `development`, build from their module dirs, and have actuator healthchecks at `/actuator/health`. `qip-testing-service` is the Go one: it builds from `../../testing-service`, runs `read_only`, and answers its healthcheck at `/health`.

### Platform Context

This module orchestrates the local/dev deployment of the whole platform; it contains no service logic of its own. See `README.md` for the repository layout.

The base `docker/docker-compose.yml` builds and runs four services from **in-repo module dirs** via relative build
contexts — `../../runtime-catalog`, `../../engine`, `../../sessions-management` and `../../testing-service` (two levels up
from `infrastructure/docker/`, i.e. the monorepo root). The `ai` and `ai-e2e` profiles build theirs from
`../../ai-service`.

It also stands up the shared infra they depend on: PostgreSQL (catalog + `engine_qrtz_db` /
`engine_checkpoints_db` created by `docker/init-db/init.sql`), OpenSearch (engine session recording +
sessions-management storage), and Consul (config + deployment publishing). Optional brokers and caches (Kafka,
RabbitMQ, Redis, Pub/Sub, SFTP) are opt-in via overlay compose files for chains that use them; the chart
offers Kafka, RabbitMQ, Pub/Sub and SFTP as subcharts, off by default.

`docker/nginx/routes.conf` is the single entrypoint (port 8080) and holds the API routing; `e2e/env/api-routes.ts`
tabulates it by prefix. The `/api/` path shape differs between resources, so look a path up there instead of building
it by hand. Chain invocation (`/routes/`, `/cip-routes/`) matches no `location` and gets the UI's `index.html` with
200, so call a chain on the engine port, not through 8080.
