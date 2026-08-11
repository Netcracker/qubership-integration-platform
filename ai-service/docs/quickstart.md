# ai-service quickstart

The public repository builds without private assets. A working CREATE runtime also needs the private
`integration-platform-skills` distribution.

## 1. Add the private distribution when available

Place the directory at the repository root:

```text
qubership-integration-platform/
├── ai-service/
├── infrastructure/
└── integration-platform-skills/
```

The distribution must contain:

```text
integration-platform-skills/
├── .apm/skills/
├── addons/
├── product-pipelines/
└── skills/skill-catalog.yaml
```

The certified Knowledge Package must be available at:

```text
integration-platform-skills/.apm/skills/cip-runtime-context-loader/assets/knowledge-export/
```

Do not copy `.dev/materials/experimental-migration`. Do not add credentials to the private distribution.

Skip this step for a public-source build.

## 2. Build the repository

Use JDK 21. Newer JDKs can fail annotation processing in existing modules. Run this command from the repository root:

```bash
mvn clean package -DskipTests
```

Maven detects a certified manifest in `integration-platform-skills`, validates the export, regenerates the Java skill
and addon indexes, and stages the runtime package in `ai-service/target/knowledge-runtime`. The build rejects
incompatible schemas, invalid certification, checksum mismatches, duplicate object IDs, and invalid capability maps.

If the certified manifest is absent, Maven skips private index generation. An empty `integration-platform-skills`
directory does not activate the private build. The public service starts, but CREATE remains unavailable without its
skills and Knowledge Package.

## 3. Configure credentials

Create the untracked file `infrastructure/.env.local`:

```dotenv
LLM_API_KEY=<api-key>
LLM_BASE_URL=<provider-base-url>
LLM_CHAT_MODEL=<model-name>
```

Add APIHub credentials only when you run scenarios that call APIHub. Use
`infrastructure/docker-compose.env.local.yml` as the Compose override. Credentials remain local and are not part of
`integration-platform-skills`.

## 4. Build and start the containers

```bash
docker compose \
  -f infrastructure/docker-compose.yml \
  -f infrastructure/docker-compose.env.local.yml \
  --profile ai up -d --build
```

Compose mounts `ai-service/target/knowledge-runtime` into the sidecar. The same command starts both public and full
builds. Without a staged package, the sidecar stays live but not ready, and knowledge operations return an unavailable
response.

- ai-service health: `http://localhost:8094/q/health`
- Knowledge sidecar URL inside Compose: `http://knowledge-sidecar:8095`
- Knowledge sidecar liveness: `http://knowledge-sidecar:8095/v1/health/live`
- Knowledge sidecar readiness: `http://knowledge-sidecar:8095/v1/health/ready`

## 5. Update from a new upstream release

Run a dry-run first:

```bash
python3 integration-platform-skills/scripts/sync-upstream-skills.py \
  --source <upstream-release>/skills \
  --knowledge-export <upstream-release>/skills/cip-runtime-context-loader/assets/knowledge-export \
  --ips-root integration-platform-skills \
  --repo-root .
```

Apply the reviewed update:

```bash
python3 integration-platform-skills/scripts/sync-upstream-skills.py \
  --source <upstream-release>/skills \
  --knowledge-export <upstream-release>/skills/cip-runtime-context-loader/assets/knowledge-export \
  --ips-root integration-platform-skills \
  --repo-root . \
  --apply
```

The updater preserves local addon bodies and product pipelines. It validates the incoming Knowledge Package before
replacement and keeps the prior export as `knowledge-export.previous` for rollback. Maven does not download the latest
release and does not compile Markdown knowledge, so builds remain pinned and reproducible.
