# Run Rocky locally

This runbook builds the AI service, Runtime Catalog, Engine, and Sessions Management from the repository, starts them
with Docker Compose, and runs the UI dev server against them. Run the commands from the repository root.

## 1. Check the prerequisites

- JDK 21 as `JAVA_HOME`. Newer JDKs can fail annotation processing in existing modules.

  ```bash
  export JAVA_HOME="$(/usr/libexec/java_home -v 21)"   # macOS
  ```

- Maven, Node.js 22.5 or later with npm, and Docker with Compose v2.

## 2. Configure the model

Create the untracked file `infrastructure/.env.local`. Compose reads it after `ai-service-dev.env`.

```dotenv
LLM_API_KEY=<api-key>
LLM_BASE_URL=<provider-base-url>
LLM_CHAT_MODEL=<model-id>
```

Set `LLM_CHAT_MODEL` to a model ID available to your provider account. Rocky selects the Anthropic client for
`api.anthropic.com` and the OpenAI client for other hosts by default. Set `LLM_PROVIDER=anthropic` for an Anthropic
endpoint on a custom host.

For scenarios that use APIHub, also set `APIHUB_MCP_URL` and `APIHUB_MCP_API_KEY`. Copy the local env file into
each worktree that runs the stack.

## 3. Build the services

Build the Catalog, Engine, and Sessions Management JARs for their Docker images.

```bash
mvn -B -pl runtime-catalog,engine,sessions-management -am package -Dgpg.skip=true -Dmaven.test.skip=true
```

Build Rocky.

```bash
mvn -B -f ai-service/pom.xml -Dgpg.skip=true -Dmaven.test.skip=true package
```

Tests are skipped here. Run them separately through the [shared test runner](../e2e/test-system.md).

## 4. Start the containers

```bash
docker compose -f infrastructure/docker/docker-compose.yml --profile ai up -d --build
```

Compose uses the `infrastructure` project and keeps its PostgreSQL volume when it recreates containers. The first
sidecar image build installs Python dependencies and can take several minutes.

Check the container states and the AI service readiness endpoint.

```bash
docker compose -f infrastructure/docker/docker-compose.yml --profile ai ps
curl -fsS http://localhost:8094/q/health/ready
```

| Service | Host URL | Health check |
| --- | --- | --- |
| UI proxy | `http://localhost:8080` | `/` |
| Runtime Catalog | `http://localhost:8091` | `/actuator/health` |
| Engine | `http://localhost:8092` | `/actuator/health` |
| Sessions Management | `http://localhost:8093` | `/actuator/health` |
| AI service | `http://localhost:8094` | `/q/health` |

Wait for the AI service to report `UP`, including its `knowledge-sidecar` check for the product documentation.
The sidecar's internal address is `http://knowledge-sidecar:8095`.

## 5. Try Rocky over HTTP

Send a consultation request to check that the configured model answers.

```bash
curl -fsSN http://localhost:8094/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"conversationId":"rocky-local-smoke","message":"What does an HTTP Trigger do in QIP?"}'
```

The response streams as server-sent events. Look for a text answer about the HTTP Trigger. Reuse the same
`conversationId` for follow-up turns and choose a fresh ID to start another conversation.

For an A2A client, set `QIP_AI_A2A_ENABLED=true` in `infrastructure/.env.local` and recreate the AI service.
Read the Agent Card at `http://localhost:8094/.well-known/agent-card.json` to discover the available interfaces
and capabilities.

## 6. Try Rocky in the UI

```bash
npm ci
npm -w @netcracker/qip-ui run dev
```

The UI dev command builds the `@netcracker/qip-schemas` workspace before starting Vite on port 4200. Open the UI
through the proxy at [localhost:8080](http://localhost:8080). It serves the dev UI and routes API and AI requests
to the containers.

Open Rocky from the navigation bar. Ask what an HTTP Trigger does, then open a source link in the answer to read
the relevant documentation.

## 7. Rebuild after a change

Rebuild the module you changed, then rebuild its image with Compose. For an AI service change, rerun the
Maven command from step 3 and use the following command.

```bash
docker compose -f infrastructure/docker/docker-compose.yml --profile ai up -d --build qip-ai-service
```

## 8. Stop the stack

```bash
docker compose -f infrastructure/docker/docker-compose.yml --profile ai down
```

Add `-v` to delete the PostgreSQL and MinIO volumes as well.
