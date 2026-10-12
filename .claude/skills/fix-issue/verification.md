# Reproducing and verifying, by module

Companion to `SKILL.md`, gates 2 and 4.

## Every module

- Seed through the API with the shapes the product itself writes: what the UI form sends, or
  what the product's own export contains. A value only a hand-made request stores is not a
  reproduction (see gate 1). Read the shapes from the OpenAPI document; all three services
  publish one, so query it rather than reading it whole (234 KB):

  ```bash
  curl -s http://localhost:8091/v3/api-docs > "$TMP/openapi.json"
  jq -r '.paths | keys[] | select(test("snapshots"))' "$TMP/openapi.json"
  jq -r '.components.schemas.SnapshotRequest.properties | keys' "$TMP/openapi.json"
  ```

  The spec is authoritative on shape and silent on behavior. `e2e/support/catalog.ts`, the
  end-to-end suite's typed client, holds the calls that work: the body each one needs, the
  status it returns, and the endpoint to poll. Order of consultation: spec, that client, the
  Java source.
- Capture "before" first. If you must recapture it later, copy the touched files aside and
  restore them; `git stash` does not carry an intent-to-add file and once produced a baseline on
  fixed code.
- Match the log level field, not the word "error": every line carries `error_code=`, so a naive
  grep matches almost everything.

  ```bash
  docker logs qip-runtime-catalog --since 10m 2>&1 | grep -E '^\[[^]]+\] \[ERROR\]'
  ```

  An asynchronous worker, such as the import, logs its error a second or two after the request
  returns. Deduplicate error lines by timestamp across scenarios, so a late error is not blamed
  on the next scenario.

## ui

A defect only a browser shows (layout, focus, a missing redraw, a screen that throws) gets its
regression case in `e2e/specs/ui/`, following "The browser layer" in `e2e/AGENTS.md`; the #679
cases in `e2e/specs/ui/chain-tabs.spec.ts` are the shape. Run it with
`cd e2e && E2E_PROVISION=never npm test -- --project=ui`, which builds and serves the bundle itself. A defect jsdom
renders the same way gets a Jest case under `ui/tests/` instead.

Evidence that does not become a case, such as a screenshot for the pull request in both themes,
comes from a scratch script in `$TMP` that loads the end-to-end suite's Playwright. Never add Playwright
to `ui/package.json`: a browser download would land on the whole team.

```bash
(cd "$WT/e2e" && npm ci && npx playwright install chromium)   # e2e has its own lockfile
node -e 'const { chromium } = require(process.env.WT + "/e2e/node_modules/playwright"); ...'
```

Drive the UI through nginx on 8080, never Vite on 4200, which serves no data.

- Anchor locators on something the state change cannot remove. A row filtered by
  `hasText: <name>` stops matching the moment the name becomes an input.
- antd v6 has no `.ant-select-selector`; the padding lives on `.ant-select`. Read the DOM before
  writing a selector.
- Screenshot the element itself, at `deviceScaleFactor: 4`, and look at the picture:
  `await locator.screenshot({ path: "control.png" })`.
- Measure a healthy peer: a neighboring non-editable column showed that editable columns sat
  12 px right of their headers, invisible when the broken cell is measured against itself.
- Both themes, every entry and exit: commit, click away, Escape, cancel.
- Do not start seeding until the stack answers: a cold stack once failed the first script.

A fresh worktree has no `node_modules`. Install and build the schemas package first; without
them `npx` downloads a stray `prettier` or runs a global ESLint 6 ("couldn't find a configuration
file"). A symlinked `node_modules` resolves `@netcracker/qip-ui` to the user's checkout and its
stale `dist-lib` types, and `tsc` then passed on broken code.

```bash
cd "$WT" && npm ci && npm -w @netcracker/qip-schemas run build
```

Static checks, from `ui/` as the working directory (`eslint` fails from the root):

```bash
cd "$WT/ui" && npx tsc --noEmit && npx eslint src/ && npx prettier --check "src/**/*.{ts,tsx,css}" && npx jest --coverage=false
```

Or hand the diff to the `npm-verifier` agent.

Sonar counts every changed line in a touched file as new code. Extracting constants in a file
you fixed once moved 27 lines and failed the coverage gate on methods the fix never touched.
No cosmetics in a file that carries a fix.

## runtime-catalog, sessions-management, engine, micro-engine

### Reproduce through the API

The criterion is the response. Build a matrix of requests, run it before and after, and `diff`
the two outputs; a 105-pair matrix of (column, condition) was the evidence for #808.

```bash
for c in "${CASES[@]}"; do
  curl -s -o "$TMP/after/$c.body" -w "$c %{http_code}\n" ... >> "$TMP/after/status"
done
diff "$TMP/before/status" "$TMP/after/status"
```

The healthy peer is the sibling endpoint on the same mapper: `PUT /v1/chains/{id}` accepted a
body without `labels` while `POST /v1/chains` returned 500, which located the defect in one
generated mapper method.

Run each scenario in the four directions the `runtime-catalog` instruction lists, and judge it
by the body, the log, and the rows the call should have written, not by the status.

A reproduced defect becomes a regression case in `e2e/specs/api/` when no unit test can reach
it, such as a Hibernate cascade or a transaction boundary.

### See what an element compiles to

For a defect in what the engine runs, read the deployment the engine fetches. It needs a
snapshot and a deployment first:

```bash
curl -s -X POST http://localhost:8091/v1/catalog/domains/default/deployments/update \
  -H 'Content-Type: application/json' -d '{"excludeDeployments":[{"deploymentId":"none"}]}' > "$TMP/d.json"
jq -r --arg c "$CHAIN_ID" '[.update[]|select(.deploymentInfo.chainId==$c)]|last|.configuration.xml' "$TMP/d.json"
```

- A body is required; `[]` gives 400. An empty `excludeDeployments` is answered from a cache
  that goes stale after the first call on this stack, so pass one bogus entry, as the
  `runtime-catalog` instruction explains.
- A chain with no trigger compiles to an empty `<routes/>`; add an `http-trigger` with
  `contextPath` and wire it with `POST /v1/chains/{id}/dependencies`.
- `PATCH /v1/chains/{id}/elements/{elementId}` replaces the whole properties map; resend
  everything the create returned.
- Two variants of one element in one chain give a side-by-side diff in one compiled XML.

### Rebuild the container from the worktree

The Dockerfile copies a prebuilt jar, so `docker compose up --build` after `mvn compile` ships
the old jar. Two runs verified a fix against unchanged code this way.

Build with `-am`, for the reason `maven-verifier` gives.

```bash
mvn -B -f "$WT/pom.xml" -pl runtime-catalog -am package -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true
ls "$WT"/runtime-catalog/target/qip-runtime-catalog-*-exec.jar          # exactly one file, or the COPY glob fails
docker tag infrastructure-qip-runtime-catalog infrastructure-qip-runtime-catalog:pre-<N>
docker compose -f "$WT/infrastructure/docker-compose.yml" up -d --build --no-deps qip-runtime-catalog
```

`--no-deps` keeps compose from recreating `postgreSQL`, which has no named volume: a recreate
wipes the database. To restore, retag and start without a build from the compose file the stack
runs from (`stack.md`):

```bash
docker tag infrastructure-qip-runtime-catalog:pre-<N> infrastructure-qip-runtime-catalog
docker compose -f "$STACK_COMPOSE" up -d --no-build --no-deps qip-runtime-catalog
docker rmi infrastructure-qip-runtime-catalog:pre-<N>
```

- `-B` turns off colored output. A build piped into `grep '^\[ERROR\]'` swallowed its own
  failure because the marker carried escape codes, and the container kept the branch jar while
  the run reported "verified on main". Check `${PIPESTATUS[0]}` when you must pipe.
- After the container is up, confirm it runs the branch: a request that answers differently on
  `main` and on the branch, or the jar inside the container. A fix in
  `integration-build-pipeline` reaches the stack only inside the catalog jar, so check the
  library there:

  ```bash
  docker exec qip-runtime-catalog ls -l --time-style=+%H:%M /app/qip-runtime-catalog.jar
  docker inspect qip-runtime-catalog --format '{{index .Config.Labels "com.docker.compose.project.working_dir"}}'
  ```
- The compose service is `qip-runtime-catalog`; the engine is `qip-engine`.

### Run the branch beside the stack

When another session uses the stack, or the user's container should stay as it is, run the
branch image as a second container on its own port. It shares the stack's database and Consul,
so give it a database of its own (`pg_dump` into `qip<N>`) when the branch adds a migration.

```bash
docker build -q -t qip-rc-<N> "$WT/runtime-catalog"
docker inspect qip-runtime-catalog --format '{{range .Config.Env}}{{println .}}{{end}}' > "$TMP/rc.env"
docker run -d --name qip-rc-<N> --network infrastructure_default --env-file "$TMP/rc.env" -p 18091:8080 \
  qip-rc-<N> $(docker inspect qip-runtime-catalog --format '{{join .Config.Cmd " "}}')
```

Point the "before" at `:8091` and the "after" at `:18091`, then `docker rm -f qip-rc-<N>` and
`docker rmi qip-rc-<N>`. The same works for `qip-engine`.

### Measure what the database does

For a change to an entity, a fetch strategy, or a query, count the statements per call and time
them, before and after. A reviewer asked for exactly this after a `LAZY` became `EAGER`.

```bash
docker exec postgreSQL psql -U postgres -qtAX -c "alter system set log_min_duration_statement = 0" -c "select pg_reload_conf()"
docker logs postgreSQL --since "$START" 2>&1 | grep -c execute
docker exec postgreSQL psql -U postgres -qtAX -c "alter system reset log_min_duration_statement" -c "select pg_reload_conf()"
```

Add `EXPLAIN ANALYZE` for the queries that changed, and a table of medians over about 30 calls.

### Java traps

- A method named `isX()` or `getX()` on an entity or a DTO is a new JSON field to Jackson and a
  new attribute to a `@Converter`. An `isEmpty()` added to an import result made every stored
  session unreadable and cost 13 minutes on the stand. Grep for `@Converter` and `@JsonProperty`
  on the class before adding an accessor.
- MapStruct ignores `defaultExpression` and `SET_TO_DEFAULT` for a null collection; only
  `@IterableMapping(nullValueMappingStrategy = RETURN_DEFAULT)` works. Read the generated
  `*MapperImpl` under `target/generated-sources` to see what actually runs.
- Mutate one part of the fix at a time and record which test catches it; `git show
  origin/main:"$F" > "$F"` restores a file to its unfixed state without `git stash`. Run mutation
  builds with `-Dcheckstyle.skip=true`: an unused import or an empty block fails checkstyle before
  any test runs, and the red build looks like the mutation was caught.

### Static checks

Hand the diff to the `maven-verifier` agent, which scopes the tests, or run the module directly
with a long timeout:

```bash
mvn -B -f "$WT/pom.xml" -pl runtime-catalog -am test -Dgpg.skip=true
```

Checkstyle runs on compile, so a style violation stops the build before the tests.

## integration-build-pipeline

The library compiles a chain into Camel XML; each element's Handlebars template is
`src/main/resources/elements/<type>/template.hbs`, with shared partials under `shared/`.

- **The criterion is a golden pair.** `TemplateServiceTest` renders each
  `testData/input/builder/templates/<case>.yml` and compares it with
  `testData/output/builder/templates/<case>.xml`. A new pair is registered in the test's argument
  list, and it fails on `main`.
- **The stack sees the fix only inside the catalog jar.** Build both modules in one reactor,
  `-pl integration-build-pipeline,runtime-catalog -am package`, then rebuild the catalog
  container. Confirm the library inside the jar:
  `unzip -l runtime-catalog/target/qip-runtime-catalog-*-exec.jar | grep qip-integration-build-pipeline`.
- **A snapshot stores its compiled XML.** Existing snapshots and deployments keep the defect until
  the chain gets a new snapshot and a redeploy. Verify on a new snapshot, and say in the pull
  request that old snapshots stay as they are.
- The module has no Sonar analysis: `SONAR_INTEGRATION_BUILD_PIPELINE_PROJECT_KEY` is unset, so CI skips
  the job.

## vscode-extension

Reproduction is the extension's own code run against real exports. For #807 a snapshot of a
`soap` service call was compiled through the catalog and the Camel XML showed no `<toD>`. For
issue 811 the real exports from the catalog were run through the AJV instance the extension carried.
Golden element samples live under `schemas/src/test/resources/samples/elements/`; look there
before writing one. A run nearly missed `service-call-soap.yaml` because its shell had drifted
into another directory.

```bash
cd vscode-extension && npx tsc --noEmit && npx eslint src tests && npx jest -c jest.config.cjs
```

The extension embeds the UI package for its dialogs. A claim that "the extension renders its own
form" once justified a stop, and it was wrong: check `@netcracker/qip-ui` imports first.

## schemas

A schema change is verified against documents, not against itself: export real entities from
the catalog and validate them with `ajv` before and after. The `readOnly` keyword is honored by
the networknt validator on the backend, so a schema edit can change what the catalog accepts;
say which documents you validated.

```bash
cd schemas && npm run build && npx jest
```

The UI reads property descriptions from the built package, so rebuild `dist` before checking a
form.

## infrastructure

- **Compose and nginx.** Reproduce beside the stack in a throwaway container, never by editing
  the user's `ui-proxy`. Docker Desktop refuses bind mounts from `/tmp`, so the config the
  container reads lives under `$HOME`. The criterion is the response body, byte for byte, from
  each upstream.
- **Helm.** Render before and after and diff the render:

  ```bash
  helm template qip "$WT/infrastructure/qip-dev" > "$TMP/after.yaml"
  diff "$TMP/before.yaml" "$TMP/after.yaml"
  ```

  A probe value equal to the Kubernetes default is a line with no criterion behind it; two of
  them were removed after a run at the cost of three CI cycles.
- New shell scripts and YAML files are linted by `super-linter` under `shfmt`, `shellcheck`,
  and `yamllint`. Copy the formatting of the neighboring file, or run the linter locally.
