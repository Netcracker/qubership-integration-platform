/**
 * The parts of provisioning that can be pinned without a stack.
 *
 * Everything that talks to Docker is verified by running the suite; these are pure and cheap, and
 * each has a failure mode worth catching here. A mistyped `E2E_PROVISION` must not quietly provision
 * a stack somebody is debugging; the report at the head of the run is the only thing that
 * distinguishes a four-minute Maven build from a hung suite; and the stack-free selection is what
 * this very project runs on a machine with no Docker to fall back to.
 * `specs/schema/provision.spec.ts` reads the watched paths against the compose file and the checkout.
 */
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { test, expect, type FullConfig } from "@playwright/test";
import { imageTag, repoRoot } from "../../env/host.js";
import {
  formatReport,
  forgottenRuns,
  needsStack,
  provisionMode,
  selectedProjects,
  type ProvisionReport,
} from "../../env/provision.js";
import { imageStale, supportStaleness, type ContainerState } from "../../env/provision/compose.js";
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

test.describe("provisioning", { tag: ["@harness"] }, () => {
  test("E2E_PROVISION defaults to auto and honors never", () => {
    expect(withProvisionEnv(undefined, provisionMode)).toBe("auto");
    expect(withProvisionEnv("auto", provisionMode)).toBe("auto");
    expect(withProvisionEnv("never", provisionMode)).toBe("never");
  });

  test("an unknown E2E_PROVISION value is rejected", () => {
    // The dangerous reading is the permissive one: a typo that falls through to `auto` rebuilds and
    // recreates containers on a stack the developer asked the suite to leave alone.
    expect(() => withProvisionEnv("no", provisionMode)).toThrow(/E2E_PROVISION/);
    expect(() => withProvisionEnv("", provisionMode)).toThrow(/E2E_PROVISION/);
  });

  test.describe("report", () => {
    test("names what provisioning did", () => {
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

    test("reports a run where provisioning did nothing", () => {
      const lines = formatReport(NOTHING);
      expect(lines).toContain("nothing to do, 2 services already current");
      expect(lines).not.toContain("rebuilt");
    });
  });

  /** A manifest entry, in the shape the run manifest carries one. */
  function record(run: string): RunRecord {
    return { run, pid: 4242, startedAt: "2026-09-09T10:00:00.000Z" };
  }

  test("every run the sweep forgot loses its corpus state", () => {
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

  test.describe("project selection", () => {
    /** The two projects this suite has to tell apart, in the shape `FullConfig` carries them. */
    const projects = [
      { name: "schema", metadata: { stack: false } },
      { name: "api", metadata: {} },
    ];

    /** A config with the argument vector a command line would have produced. */
    function configFor(...argv: string[]): FullConfig {
      return { argv: ["node", "playwright", "test", ...argv], projects } as unknown as FullConfig;
    }

    test("reads a --project selection off the argument vector", () => {
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

    test("skips provisioning only for stack-free projects", () => {
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
  });

  test.describe("support containers", () => {
    test("a container with changed configuration is only named", () => {
      const lines = formatReport({ ...NOTHING, staleSupport: ["postgres", "consul"] });

      // The suite starts these and never recreates them: `infrastructure/docker/docker-compose.yml` declares no
      // volume for their data, so a recreate discards the catalog rows, the recorded sessions or the
      // deployment state the stack is holding. Reporting is the whole of what the suite can do, and
      // silence would read as a stack that matches the commit.
      expect(lines).toContain("left as they are, though their configuration changed");
      expect(lines).toContain("postgres, consul");
      expect(lines).toContain("--force-recreate postgres consul");
      // And the report does not claim in one line what it denies in the next.
      expect(lines).not.toContain("nothing to do");
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

    test("a container is stale whether or not it answers", async () => {
      // The case a gate on health made unreachable, and the reason the notice was written: postgres runs
      // `/docker-entrypoint-initdb.d` only against an empty data directory, so a schema added under
      // `infrastructure/docker/init-db` is inert until the container is replaced. A container that is stopped,
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

    test("a stale proxy is recreated", async () => {
      // The split is a fact about the compose file rather than a preference: the proxy holds nothing but
      // a bind-mounted configuration, so recreating it costs seconds, while recreating postgres,
      // opensearch or consul discards the data this stack is holding.
      const found = await supportStaleness(repoRoot(), new Map([["ui-proxy", container(0)]]));
      expect(found.recreate).toEqual(["ui-proxy"]);
      expect(found.report).toEqual([]);
    });

    test("a container newer than its configuration is not stale", async () => {
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
  });

  test.describe("service images", () => {
    const REPOSITORY = "infrastructure-qip-testing-service";
    const sources = ["module"];

    /** A checkout holding one service, and an image store holding the image its last build tagged. */
    function built(body: (root: string, inspect: (reference: string) => Promise<string | null>) => Promise<void>) {
      return async () => {
        const root = fs.mkdtempSync(path.join(os.tmpdir(), "e2e-compose-image-"));
        try {
          fs.mkdirSync(path.join(root, "module"));
          fs.writeFileSync(path.join(root, "module/main.go"), "package main");
          const images = new Map([
            [REPOSITORY, "sha256:built"],
            [`${REPOSITORY}:${imageTag(root, sources)}`, "sha256:built"],
          ]);
          await body(root, async (reference) => images.get(reference) ?? null);
        } finally {
          fs.rmSync(root, { recursive: true, force: true });
        }
      };
    }

    test("an image built from the sources as they stand is current", built(async (root, inspect) => {
      expect(await imageStale(REPOSITORY, imageTag(root, sources), "sha256:built", inspect)).toBe(false);
      // With no container, the image Compose would start is the one compared.
      expect(await imageStale(REPOSITORY, imageTag(root, sources), null, inspect)).toBe(false);
    }));

    test("a changed source makes the image stale", built(async (root, inspect) => {
      fs.writeFileSync(path.join(root, "module/main.go"), "package main // edited");
      expect(await imageStale(REPOSITORY, imageTag(root, sources), "sha256:built", inspect)).toBe(true);
    }));

    test("a touch that changes no content leaves the image current", built(async (root, inspect) => {
      // A reproducible build keeps the image's creation time, so under the old mtime check a file
      // rewritten with the same content, such as a regenerated `AGENTS.md`, made the service stale on
      // every run.
      const later = new Date(Date.now() + 86_400_000);
      fs.utimesSync(path.join(root, "module/main.go"), later, later);
      expect(await imageStale(REPOSITORY, imageTag(root, sources), "sha256:built", inspect)).toBe(false);
    }));

    test("a container running another image is stale", built(async (root, inspect) => {
      // An image built outside the suite carries no hash tag, so which sources it holds is unknown.
      expect(await imageStale(REPOSITORY, imageTag(root, sources), "sha256:other", inspect)).toBe(true);
    }));
  });
});
