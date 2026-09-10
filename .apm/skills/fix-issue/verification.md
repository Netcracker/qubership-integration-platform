# Reproducing and verifying, by module

Companion to `SKILL.md`, gates 2 and 4. Every recipe here was used in a run; every trap cost one.

## Every module

- Seed through the API, shaped by the OpenAPI document. All three services publish one; query
  it rather than reading it whole (234 KB):

  ```bash
  curl -s http://localhost:8091/v3/api-docs > "$TMP/openapi.json"
  jq -r '.paths | keys[] | select(test("snapshots"))' "$TMP/openapi.json"
  jq -r '.components.schemas.SnapshotRequest.properties | keys' "$TMP/openapi.json"
  ```

  The spec is authoritative on shape and silent on behavior; the
  `runtime-catalog-api-testing` skill holds the behavior. Order of consultation: spec, that
  skill, the Java source.
- Prefix every seeded entity with a run token and delete it at the end.
- Capture "before" first. If you must recapture it later, copy the touched files aside and
  restore them; `git stash` does not carry an intent-to-add file and once produced a baseline on
  fixed code.
- Match the log level field, not the word "error": every line carries `error_code=`, so a naive
  grep matches almost everything.

  ```bash
  docker logs qip-runtime-catalog --since 10m 2>&1 | grep -E '^\[[^]]+\] \[ERROR\]'
  ```

## ui

Reproduction is Playwright from a scratch directory. Never add it to `ui/package.json`: the
workspace has no end-to-end infrastructure, and a browser download would land on the whole team.

```bash
mkdir -p "$TMP/e2e" && cd "$TMP/e2e" && npm init -y && npm i playwright
npx playwright install chromium     # ~115 MB unless the cached build already matches
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

Static checks, from `ui/` as the working directory (`eslint` fails from the root):

```bash
cd ui && npx tsc --noEmit && npx eslint src/ && npx prettier --check "src/**/*.{ts,tsx,css}" && npx jest --coverage=false
```

Or hand the diff to the `npm-verifier` agent. The suite runs in `jsdom`, which has no layout
engine; it passed identically before and after a fix that closed two visual defects.

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

### See what an element compiles to

For a defect in what the engine runs, read the deployment the engine fetches. It needs a
snapshot and a deployment first:

```bash
curl -s -X POST http://localhost:8091/v1/catalog/domains/default/deployments/update \
  -H 'Content-Type: application/json' -d '{"excludeDeployments":[]}' > "$TMP/d.json"
jq -r --arg c "$CHAIN_ID" '[.update[]|select(.deploymentInfo.chainId==$c)]|last|.configuration.xml' "$TMP/d.json"
```

- The body `{"excludeDeployments":[]}` is required; `[]` gives 400.
- A chain with no trigger compiles to an empty `<routes/>`; add an `http-trigger` with
  `contextPath` and wire it with `POST /v1/chains/{id}/dependencies`.
- `PATCH /v1/chains/{id}/elements/{elementId}` replaces the whole properties map; resend
  everything the create returned.
- Two variants of one element in one chain give a side-by-side diff in one compiled XML.

### Rebuild the container from the worktree

The Dockerfile copies a prebuilt jar, so `docker compose up --build` after `mvn compile` ships
the old jar. Two runs verified a fix against unchanged code this way.

```bash
mvn -B -f "$WT/pom.xml" -pl runtime-catalog package -DskipTests -Dgpg.skip=true -Dmaven.javadoc.skip=true
ls "$WT"/runtime-catalog/target/qip-runtime-catalog-*-exec.jar          # exactly one file, or the COPY glob fails
docker compose -f "$WT/infrastructure/docker-compose.yml" up -d --build qip-runtime-catalog
```

- `-B` turns off colored output. A build piped into `grep '^\[ERROR\]'` swallowed its own
  failure because the marker carried escape codes, and the container kept the branch jar while
  the run reported "verified on main". Check `${PIPESTATUS[0]}` when you must pipe.
- After the container is up, confirm it runs the branch: a request that answers differently on
  `main` and on the branch, or the jar timestamp inside the container.
- The compose service is `qip-runtime-catalog`; the engine is `qip-engine`.
- Restore the user's container at the end of the run from the user's checkout, and say so in
  the report.

### Java traps

- A method named `isX()` or `getX()` on an entity or a DTO is a new JSON field to Jackson and a
  new attribute to a `@Converter`. An `isEmpty()` added to an import result made every stored
  session unreadable and cost 13 minutes on the stand. Grep for `@Converter` and `@JsonProperty`
  on the class before adding an accessor.
- MapStruct ignores `defaultExpression` and `SET_TO_DEFAULT` for a null collection; only
  `@IterableMapping(nullValueMappingStrategy = RETURN_DEFAULT)` works. Read the generated
  `*MapperImpl` under `target/generated-sources` to see what actually runs.
- A mutation that leaves an unused import fails checkstyle before the test runs, and the red
  build looks like the mutation worked. Mutate without touching imports.
- `runtime-catalog/api-spec/openapi.yaml` is regenerated by `OpenApiSpecGeneratorTest` and
  checked in CI. A new request type or annotation changes it; that is a public contract change.

### Static checks

Hand the diff to the `maven-verifier` agent, or run the module directly with a long timeout:

```bash
mvn -B -f "$WT/pom.xml" -pl runtime-catalog test -Dgpg.skip=true
```

Checkstyle runs on compile, so a style violation stops the build before the tests.

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
