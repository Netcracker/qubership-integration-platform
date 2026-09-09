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
- Stop only what you started. Leave containers that were already running, and if a
  reproduction has to recreate one of the user's containers, put it back and say so.

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

The run works in `/home/dmitrii/qip-wt-<N>`, a worktree of the user's checkout. Everything the
user has locally is visible there except uncommitted work, which is the point.

- Docker build contexts resolve relative to the compose file, so
  `docker compose -f "$WT/infrastructure/docker-compose.yml" up -d --build <service>` builds the
  branch and replaces the container of the same name.
- `~/.m2` is shared with every other worktree. A temporary version installed there for an
  experiment leaks into the next run; remove it before the run ends.
- Review lenses get the worktree path and read nothing else. One lens read the user's checkout
  and cited a document that exists only on the user's branch.
- Never `git stash`, `git checkout`, or `git add` in the user's checkout. Another run may be
  using it at the same time; one run watched its stash disappear under another.
- A worktree created by the harness under `.claude/worktrees` guards against `git` commands
  that name a path outside it. Use plain `git` from inside that worktree and put scripts that
  mention Git in a file.

## Waiting

Agents, background commands, and monitors notify the session when they finish. `sleep` is
blocked by a hook, `until [ -f /tmp/nonexistent ]` loops burned an hour in one session, and
empty timers woke a finished session four times. Wait by doing nothing that touches the diff.
