/**
 * The parts of provisioning that can be pinned without a stack.
 *
 * Everything that talks to Docker is verified by running the suite; these are pure and cheap, and
 * each has a failure mode worth catching here. A mistyped `E2E_PROVISION` must not quietly provision
 * a stack somebody is debugging; the report at the head of the run is the only thing that
 * distinguishes a four-minute Maven build from a hung suite; and the stack-free selection is what
 * this very project runs on a machine with no Docker to fall back to.
 */
import fs from "node:fs";
import path from "node:path";
import { test, expect, type FullConfig } from "@playwright/test";
import { composeFile } from "../../env/compose.js";
import { repoRoot } from "../../env/host.js";
import {
  formatReport,
  forgottenRuns,
  needsStack,
  provisionMode,
  selectedProjects,
  type ProvisionReport,
} from "../../env/provision.js";
import {
  provisionedSources,
  supportConfig,
  supportStaleness,
  type ContainerState,
} from "../../env/provision/compose.js";
import type { RunRecord } from "../../support/run.js";

function withProvisionEnv<T>(value: string | undefined, body: () => T): T {
  const original = process.env.E2E_PROVISION;
  if (value === undefined) delete process.env.E2E_PROVISION;
  else process.env.E2E_PROVISION = value;
  try {
    return body();
  } finally {
    if (original === undefined) delete process.env.E2E_PROVISION;
    else process.env.E2E_PROVISION = original;
  }
}

const NOTHING: ProvisionReport = {
  mode: "auto",
  durationMs: 1500,
  built: [],
  rebuilt: [],
  recreated: [],
  started: [],
  untouched: ["consul", "postgres"],
  staleSupport: [],
  proxyReloaded: false,
  proxyConfigChanged: false,
};

test("provisioning defaults to auto and honors never", { tag: ["@infra", "@tier1"] }, () => {
  expect(withProvisionEnv(undefined, provisionMode)).toBe("auto");
  expect(withProvisionEnv("auto", provisionMode)).toBe("auto");
  expect(withProvisionEnv("never", provisionMode)).toBe("never");
});

test("an unknown E2E_PROVISION value is rejected rather than read as auto", { tag: ["@infra", "@tier1"] }, () => {
  // The dangerous reading is the permissive one: a typo that falls through to `auto` rebuilds and
  // recreates containers on a stack the developer asked the suite to leave alone.
  expect(() => withProvisionEnv("no", provisionMode)).toThrow(/E2E_PROVISION/);
  expect(() => withProvisionEnv("", provisionMode)).toThrow(/E2E_PROVISION/);
});

test("the report names what provisioning did", { tag: ["@infra", "@tier1"] }, () => {
  const lines = formatReport({
    ...NOTHING,
    durationMs: 254_000,
    built: ["engine"],
    rebuilt: ["qip-engine"],
    started: ["ui-proxy"],
    proxyReloaded: true,
  });
  expect(lines).toContain("mode=auto, 254.0s");
  expect(lines).toContain("mvn install: engine");
  expect(lines).toContain("rebuilt: qip-engine");
  expect(lines).toContain("started: ui-proxy");
  expect(lines).toContain("nginx reloaded");
});

test("the report says so when provisioning did nothing", { tag: ["@infra", "@tier1"] }, () => {
  const lines = formatReport(NOTHING);
  expect(lines).toContain("nothing to do, 2 services already current");
  expect(lines).not.toContain("rebuilt");
});

/** A manifest entry, in the shape the run manifest carries one. */
function record(run: string): RunRecord {
  return { run, pid: 4242, startedAt: "2026-09-09T10:00:00.000Z" };
}

test("the corpus state is discarded for every run the sweep forgot", { tag: ["@infra", "@tier1"] }, () => {
  const swept = [record("aa11aa"), record("bb22bb")];

  // The case the sweep's own report cannot see: a run whose sweep found nothing is not in that
  // report at all, and its corpus state would then survive the run that collected it — leaving the
  // next `--project=runtime --no-deps` re-run reading chain ids that are gone.
  expect(forgottenRuns(swept, [])).toEqual(["aa11aa", "bb22bb"]);
  expect(forgottenRuns(swept, [record("bb22bb")])).toEqual(["aa11aa"]);

  // A run that kept its entry keeps its corpus state: the state file is what still names the chains
  // the failed delete left deployed.
  expect(forgottenRuns(swept, swept)).toEqual([]);
  // `E2E_SWEEP=never` collects nothing and forgets nothing, and discards nothing with it.
  expect(forgottenRuns([], [record("aa11aa")])).toEqual([]);
});

/** The two projects this suite has to tell apart, in the shape `FullConfig` carries them. */
const projects = [
  { name: "schema", metadata: { stack: false } },
  { name: "api", metadata: {} },
];

/** A config with the argument vector a command line would have produced. */
function configFor(...argv: string[]): FullConfig {
  return { argv: ["node", "playwright", "test", ...argv], projects } as unknown as FullConfig;
}

test("a --project selection is read off the argument vector", { tag: ["@infra", "@tier1"] }, () => {
  expect(selectedProjects(["node", "playwright", "test"])).toEqual([]);
  expect(selectedProjects(["--project=schema"])).toEqual(["schema"]);
  // `--project <name...>` is variadic, and the list ends at the next option rather than at the
  // first name.
  expect(selectedProjects(["--project", "schema", "api", "--grep", "@infra"])).toEqual([
    "schema",
    "api",
  ]);
  expect(selectedProjects(["--project", "schema", "--project", "api"])).toEqual(["schema", "api"]);
});

test("only a selection of stack-free projects skips provisioning", { tag: ["@infra", "@tier1"] }, () => {
  expect(needsStack(configFor("--project=schema"))).toBe(false);
  expect(needsStack(configFor("--project", "schema", "--grep", "@infra"))).toBe(false);

  // A run that selects nothing runs every project, one of which restarts containers.
  expect(needsStack(configFor())).toBe(true);
  expect(needsStack(configFor("--grep", "@catalog"))).toBe(true);
  // One stack-touching project in the selection is enough, and so is a name no project declares:
  // the safe reading of an argument this does not understand is that the stack is needed.
  expect(needsStack(configFor("--project=schema", "--project=api"))).toBe(true);
  expect(needsStack(configFor("--project=squema"))).toBe(true);
});

test("a support container whose configuration has moved on is named rather than recreated", { tag: ["@infra", "@tier1"] }, () => {
  const lines = formatReport({ ...NOTHING, staleSupport: ["postgres", "consul"] });

  // The suite starts these and never recreates them: `infrastructure/docker-compose.yml` declares no
  // volume for their data, so a recreate discards the catalog rows, the recorded sessions or the
  // deployment state the stack is holding. Reporting is the whole of what the suite can do, and
  // silence would read as a stack that matches the commit.
  expect(lines).toContain("left as they are, though their configuration changed");
  expect(lines).toContain("postgres, consul");
  expect(lines).toContain("--force-recreate postgres consul");
  // And the report does not claim in one line what it denies in the next.
  expect(lines).not.toContain("nothing to do");
});

test("every support service watches the configuration the compose file mounts into it", { tag: ["@infra", "@tier1"] }, () => {
  // A path that has moved is not an error: `newestMtimeMs` skips one that does not exist, so the
  // service reads as current forever and the run says "nothing to do" over a container holding a
  // consul policy or an init script from before the change. Nothing else would catch that, which is
  // why the list is read back against the mounts it was written from.
  const compose = fs.readFileSync(composeFile(), "utf-8");
  const watched = supportConfig().flatMap((each) => each.config);
  expect(watched.length, "no support service watches any bind mount").toBeGreaterThan(0);

  for (const each of watched) {
    expect(fs.existsSync(path.resolve(repoRoot(), each)), `${each} is watched but not in the checkout`).toBe(true);
    // The compose file spells a mount relative to its own directory, so `infrastructure/consul`
    // reads as `./consul`. A watch may be broader than the mount and consul's is: the compose file
    // mounts `./consul/server.json` and `./consul/consul-acl.json` by name, and watching the
    // directory is what makes a third file added beside them count.
    const mount = `./${each.replace(/^infrastructure\//, "")}`;
    const mounted = new RegExp(`${mount.replace(/[.*+?^${}()|[\]\\]/g, "\\$&")}[:/]`);
    expect(mounted.test(compose), `${each} is watched but ${mount} is mounted nowhere`).toBe(true);
  }

  // Every path listed being real is half of it. The other half is that the three containers holding
  // this stack's data still list one: an emptied entry reads as a service with nothing to watch, and
  // the notice then stays silent over a postgres that never ran the init script it was given.
  const empty = supportConfig().filter((each) => each.config.length === 0).map((each) => each.service);
  // The proxy is the deliberate exception: its `./nginx` is answered by a reload rather than by a
  // recreate, so listing it here would recreate a container the suite has a cheaper answer for.
  expect(empty, "a support service watches nothing bind-mounted into it").toEqual(["ui-proxy"]);
});

/**
 * A container as `docker inspect` describes one, created at `createdMs`.
 *
 * `supportStaleness` reads the file system and this map, so a synthetic creation time is the whole
 * of what a case needs: 0 is older than every file in the checkout, and a time in the future is
 * newer than all of them.
 */
function container(createdMs: number, state: Partial<ContainerState> = {}): ContainerState {
  return {
    id: "c0ffee",
    running: true,
    health: "healthy",
    imageId: "sha256:deadbeef",
    createdMs,
    ...state,
  };
}

test("a support container is stale whether or not it answers", { tag: ["@infra", "@tier1"] }, async () => {
  // The case a gate on health made unreachable, and the reason the notice was written: postgres runs
  // `/docker-entrypoint-initdb.d` only against an empty data directory, so a schema added under
  // `infrastructure/init-db` is inert until the container is replaced. A container that is stopped,
  // or still inside its healthcheck's `starting` window, is started rather than recreated — and
  // `docker compose up -d` starts the container that is already there, with the mounts it already
  // has.
  for (const state of [{ running: false, health: "none" }, { health: "starting" }]) {
    const found = await supportStaleness(
      repoRoot(),
      new Map([["postgreSQL", container(0, state)]]),
    );
    expect(found.report, `a ${state.health} postgres was not reported`).toEqual(["postgres"]);
    expect(found.recreate).toEqual([]);
  }
});

test("a stale proxy is recreated rather than left for a person", { tag: ["@infra", "@tier1"] }, async () => {
  // The split is a fact about the compose file rather than a preference: the proxy holds nothing but
  // a bind-mounted configuration, so recreating it costs seconds, while recreating postgres,
  // opensearch or consul discards the data this stack is holding.
  const found = await supportStaleness(repoRoot(), new Map([["ui-proxy", container(0)]]));
  expect(found.recreate).toEqual(["ui-proxy"]);
  expect(found.report).toEqual([]);
});

test("a support container newer than its configuration is not stale", { tag: ["@infra", "@tier1"] }, async () => {
  // The reading has to be a comparison rather than a report of everything it was handed: a stale
  // list nobody can empty recreates the proxy on every run and prints a notice about postgres that
  // no change is behind.
  const ahead = Date.now() + 86_400_000;
  const found = await supportStaleness(
    repoRoot(),
    new Map([
      ["postgreSQL", container(ahead)],
      ["ui-proxy", container(ahead)],
      // A container that does not exist is created by this run, and it reads the configuration as
      // it stands.
      ["opensearch", null],
    ]),
  );
  expect(found).toEqual({ recreate: [], report: [] });
});

test("every source a service is rebuilt for is in the checkout", { tag: ["@infra", "@tier1"] }, () => {
  for (const each of provisionedSources()) {
    expect(each.sources.length, `${each.service} watches nothing`).toBeGreaterThan(0);
    for (const source of each.sources) {
      expect(
        fs.existsSync(path.resolve(repoRoot(), source)),
        `${each.service} watches ${source}, which is not in the checkout`,
      ).toBe(true);
    }

    // Two of the three inputs to a jar this image runs sit outside the module. `parent/pom.xml` is
    // the parent of every Spring service and carries the dependency versions and the repackage
    // plugin, so a bump there changes every jar the build produces; the `Dockerfile` is the only
    // other input to the image, since all three copy nothing but `target/`. Without them a run after
    // either edit prints "nothing to do" and tests the jars from before it. The Go service has no
    // Maven module and watches its whole directory, Dockerfile included.
    if (each.mavenModule === undefined) continue;
    expect(each.sources, `${each.service} is not rebuilt for a change to the parent POM`).toContain(
      "parent/pom.xml",
    );
    expect(each.sources, `${each.service} is not rebuilt for a change to its Dockerfile`).toContain(
      `${each.mavenModule}/Dockerfile`,
    );
  }
});
