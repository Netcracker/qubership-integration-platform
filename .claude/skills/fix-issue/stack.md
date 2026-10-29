# The local stack

Companion to `SKILL.md`, gate 2. What to start, what each symptom means, and what breaks.

## Bring it up

```bash
docker ps --format '{{.Names}}\t{{.Status}}'                              # up already: leave it as it is
docker compose -f "$REPO/infrastructure/docker/docker-compose.yml" up -d   # only when it is down
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8091/actuator/health   # 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:4200/                  # 200: Vite runs, reuse it
```

- Start the stack from the main checkout, `$REPO`, not from `$WT`: the worktree is removed when
  the run ends, and the stack outlives it.
- A UI run needs Vite on 4200. When it answers, reuse it and leave it running. Otherwise start
  `npm -w @netcracker/qip-ui run dev` from `$WT` as a background command, with its output in
  `$TMP/vite.log`, and stop it at the end. Before Vite starts, `npm run dev` copies
  `help/docs` into the UI (`ui/.documentation-config.json` has `"source": "local"`). `vite` is
  hoisted to the repo-root `node_modules/.bin`.

## Wait in the background

A service that is starting, a build, or a deployment is awaited with an until-loop run as a
background command (`run_in_background`), or with the Monitor tool. Never `sleep N; curl` in the
foreground: the harness blocks a foreground `sleep`.

```bash
until curl -sf -o /dev/null http://localhost:8091/actuator/health; do sleep 5; done
```

## Reach the services

| Need | How |
|---|---|
| SQL | `docker exec postgreSQL psql -U postgres -d postgres`; there is no `psql` on the host |
| Consul KV | header `X-Consul-Token` with `CONSUL_ADMIN_TOKEN` from `infrastructure/docker/qip-dev.env`; without it `?keys` returns `[]`, which looks like an empty store |
| A chain's HTTP trigger on the classic engine | `http://localhost:8092/routes/<contextPath>`; 8080 never reaches the engine's `/routes` |
| A JVM service in a debugger | `jdb -attach 5006` for the catalog, `5007` for the engine |
| The end-to-end suite against the running stack | `E2E_PROVISION=never`; by default the suite runs `mvn install` and rebuilds every service that is stale against its own checkout (the `e2e` instruction, Provisioning) |

## Read the symptom

| Symptom | Meaning |
|---|---|
| 8080 answers 200 with an empty body | Vite is down; nginx answers its own `@error` location (`infrastructure/docker/nginx/routes.conf`), and the app is fine |
| 8080 answers 000 | the `ui-proxy` container is not running |
| 8091 healthy, requests fail with connection errors | postgres lost its network after a Docker Desktop restart; `compose up -d` does not fix it, check `docker inspect postgreSQL --format '{{json .NetworkSettings.Networks}}'` and recreate the container |
| `docker` returns HTTP 500 or hangs | Docker Desktop died; it has done so in five runs. Wait for it, then `docker ps` before touching anything |
| `mounts denied: The path ... is not shared` | a bind mount from `/tmp`; move the file under `$HOME` |
| Chrome tab reports a browser-internal URL or times out twice in a row | the renderer is gone; stop using the browser, verify through the API, and say so |

## Worktrees

The run works in `$HOME/qip-wt-<N>`, a worktree of the user's checkout. Everything the
user has locally is visible there except uncommitted work, which is the point.

- The running stack may come from a checkout other than the user's. Restore from the compose
  file it runs from:
  `STACK_COMPOSE=$(docker inspect qip-runtime-catalog --format '{{index .Config.Labels "com.docker.compose.project.config_files"}}')`.
- `~/.m2` is shared with every other worktree; install nothing there (see `maven-verifier`).
- Never `git stash`, `git checkout`, or `git add` in the user's checkout. Another run may be
  using it at the same time; one run watched its stash disappear under another.
- A worktree created by the harness under `.claude/worktrees` guards against `git` commands
  that name a path outside it. Use plain `git` from inside that worktree and put scripts that
  mention Git in a file.
