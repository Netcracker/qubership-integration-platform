# The local stack

Companion to `SKILL.md`, gate 2. What to start, what each symptom means, and what breaks.

## Bring it up

```bash
docker compose -f infrastructure/docker-compose.yml up -d
docker ps --format '{{.Names}}\t{{.Status}}'
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8091/actuator/health   # 200
curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/                  # 200
nohup npm -w @netcracker/qip-ui run dev > "$TMP/vite.log" 2>&1 &                 # only for a UI run
```

- `npm run dev` fetches documentation over the network before Vite starts; the first run is
  slow. `vite` is hoisted to the repo-root `node_modules/.bin`.

## Reach the services

| Need | How |
|---|---|
| SQL | `docker exec postgreSQL psql -U postgres -d postgres`; there is no `psql` on the host |
| Consul KV | header `X-Consul-Token` with `CONSUL_ADMIN_TOKEN` from `infrastructure/qip-dev.env`; without it `?keys` returns `[]`, which looks like an empty store |
| A chain's HTTP trigger on the classic engine | `http://localhost:8092/routes/<contextPath>`; 8080 proxies it only while Vite runs |
| A JVM service in a debugger | `jdb -attach 5006` for the catalog, `5007` for the engine |
| The end-to-end suite against the running stack | `E2E_PROVISION=never`; by default the suite runs `mvn install` and rebuilds every service that is stale against its own checkout (`e2e/AGENTS.md`, Provisioning) |

## Read the symptom

| Symptom | Meaning |
|---|---|
| 8080 answers 502 | Vite is down; the app is fine |
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
