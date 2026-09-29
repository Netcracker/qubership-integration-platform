/**
 * The parts of the reporting layer that are pure, pinned without a stack.
 *
 * Everything that reads Docker or the services is verified by running the suite. What is here is
 * what would otherwise fail silently: a case catalog that quietly drops a project's tests, a
 * start-of-run sweep that decides ownership from the shape of a token, and an engine log filter
 * that reports a chain as silent because it matched the wrong field.
 */
import { test, expect } from "@playwright/test";
import { ComposeEnv } from "../../env/compose.js";
import { caseRows, renderCatalog, type StackHeader } from "../../support/report.js";
import { filterChainLog, sessionLink } from "../../support/diagnostics.js";
import { sweepableRuns, type RunRecord } from "../../support/run.js";
import { COMPONENT_TAGS, type JsonReport } from "../../registry/elements.js";

const REPORT: JsonReport = {
  stats: { expected: 2, unexpected: 1, skipped: 0, flaky: 0, duration: 91_000 },
  suites: [
    {
      specs: [
        {
          title: "the chain answers on its route",
          file: "runtime/routing.spec.ts",
          tags: ["engine", "tier1"],
          tests: [
            {
              status: "expected",
              projectName: "runtime",
              annotations: [{ type: "covers", description: "condition" }],
              results: [{ status: "passed", duration: 1200, attachments: [] }],
            },
          ],
        },
        {
          title: "the trace names both branches",
          file: "runtime/routing.spec.ts",
          tags: ["engine", "sessions", "tier1"],
          tests: [
            {
              status: "unexpected",
              projectName: "runtime",
              annotations: [],
              results: [
                {
                  status: "failed",
                  duration: 4300,
                  attachments: [
                    { name: "engine.log", path: "/tmp/engine.log" },
                    { name: "trace", path: "test-results/routing/trace.zip" },
                  ],
                },
              ],
            },
          ],
        },
      ],
      suites: [
        {
          specs: [
            {
              title: "a spec nobody tagged",
              file: "api/orphan.spec.ts",
              tags: ["tier2"],
              tests: [{ status: "expected", projectName: "api", results: [{ status: "passed" }] }],
            },
          ],
        },
      ],
    },
  ],
};

const HEADER: StackHeader = {
  run: "abc123",
  target: "compose",
  absent: [{ name: "brokers", reason: "E2E_BROKERS=0" }],
  startedAt: "2026-09-09T08:00:00.000Z",
  commit: { sha: "0123456789abcdef0123", branch: "test/e2e-suite", dirty: true },
  workers: 8,
  provision: {
    mode: "auto",
    durationMs: 128_200,
    built: ["engine"],
    rebuilt: ["qip-engine"],
    recreated: [],
    started: [],
    untouched: ["consul"],
    staleSupport: [],
    proxyReloaded: true,
    proxyConfigChanged: false,
  },
  services: [
    {
      role: "engine",
      url: "http://localhost:8092",
      build: { build: { version: "1.2.3-SNAPSHOT", time: "2026-09-08T15:08:13.594Z" } },
      container: "qip-engine",
      imageId: "sha256:aabbccddeeff00112233",
      imageCreated: "2026-09-08T15:10:00Z",
    },
  ],
};

test("every test in the report becomes a row, nested suites included", { tag: ["@infra", "@tier1"] }, () => {
  const rows = caseRows(REPORT);
  expect(rows.map((each) => each.title)).toEqual([
    "the chain answers on its route",
    "the trace names both branches",
    "a spec nobody tagged",
  ]);
  expect(rows[0].status).toBe("passed");
  expect(rows[0].covers).toEqual(["condition"]);
  expect(rows[0].project).toBe("runtime");
});

test("a failed row carries the path of its trace", { tag: ["@infra", "@tier1"] }, () => {
  // The trace is the reason `retain-on-failure` replaced `on-first-retry`, and a catalog that does
  // not name it makes the reader hunt through the HTML report for the one case that failed.
  const failed = caseRows(REPORT)[1];
  expect(failed.status).toBe("failed");
  expect(failed.trace).toBe("test-results/routing/trace.zip");
});

test("the catalog opens with what the run was pointed at", { tag: ["@infra", "@tier1"] }, () => {
  const catalog = renderCatalog(REPORT, HEADER, COMPONENT_TAGS);
  expect(catalog).toContain("Target: compose");
  // A project the target leaves out is named with its reason, so its missing cases are not read as
  // cases that vanished.
  expect(catalog).toContain("Not run on this target: brokers (E2E_BROKERS=0)");
  expect(catalog).toContain("`abc123`");
  expect(catalog).toContain("0123456789ab");
  expect(catalog).toContain("test/e2e-suite");
  expect(catalog).toContain("working tree dirty");
  expect(catalog).toContain("Workers: 8");
  expect(catalog).toContain("mode=auto, 128.2 s");
  expect(catalog).toContain("1.2.3-SNAPSHOT");
  expect(catalog).toContain("Wall time 91.0 s: 2 passed, 1 failed");
});

test("a Kubernetes header names the Helm release and each image by its reference", { tag: ["@infra", "@tier1"] }, () => {
  const k8sReport = { ...REPORT, config: { metadata: { target: "k8s" } } };
  const header: StackHeader = {
    ...HEADER,
    target: "k8s",
    absent: [],
    release: {
      context: "docker-desktop",
      namespace: "qip-e2e",
      name: "qip",
      revision: 7,
      status: "deployed",
      chart: "qip-0.0.1",
    },
    services: [
      {
        ...HEADER.services[0],
        url: "http://localhost:30092",
        imageId: "ghcr.io/netcracker/qubership-integration-engine:e2e-0123456789ab",
      },
    ],
  };
  const catalog = renderCatalog(k8sReport, header, COMPONENT_TAGS);
  expect(catalog).toContain(
    "- Helm release: qip in qip-e2e, revision 7, deployed, chart qip-0.0.1, kube-context docker-desktop",
  );
  expect(catalog).toContain("| qubership-integration-engine:e2e-0123456789ab |");
  // Compose has no release, and its line is left out rather than printed empty.
  expect(renderCatalog(REPORT, HEADER, COMPONENT_TAGS)).not.toContain("Helm release");
});

test("a catalog with no header says so rather than looking complete", { tag: ["@infra", "@tier1"] }, () => {
  // The failure this guards is the one the header exists for: a report that does not say what it
  // tested reads exactly like one that does, and half a day was spent testing a stale jar.
  expect(renderCatalog(REPORT, null, COMPONENT_TAGS)).toContain("cannot say what it was run against");
});

test("a header from a run on the other target is left out of the catalog", { tag: ["@infra", "@tier1"] }, () => {
  // A report copied aside and reconciled later meets whatever run wrote `stack.json` last.
  const k8sReport = { ...REPORT, config: { metadata: { target: "k8s" } } };
  const catalog = renderCatalog(k8sReport, HEADER, COMPONENT_TAGS);
  expect(catalog).toContain("Target: k8s");
  expect(catalog).toContain("describes a run on compose, not the k8s run this report came from");
  expect(catalog).not.toContain("`abc123`");
});

test("the catalog groups by component tag and names the untagged", { tag: ["@infra", "@tier1"] }, () => {
  const catalog = renderCatalog(REPORT, HEADER, COMPONENT_TAGS);
  expect(catalog).toContain("## @engine");
  expect(catalog).toContain("## @sessions");
  expect(catalog).toContain("## No component tag");
  // Two component tags on one test means two tables, because the two people who own those services
  // each read only theirs.
  expect(catalog.split("the trace names both branches").length - 1).toBe(2);
  expect(catalog).toContain("a spec nobody tagged");
});

test("a sweep collects a finished run, never one still going", { tag: ["@infra", "@tier1"] }, () => {
  const records: RunRecord[] = [
    { run: "mine00", pid: 1, startedAt: "2026-09-09T08:00:00.000Z" },
    { run: "gone00", pid: 4242, startedAt: "2026-09-08T08:00:00.000Z" },
    { run: "live00", pid: 4243, startedAt: "2026-09-09T07:00:00.000Z" },
  ];
  const alive = (pid: number) => pid === 4243;

  // Shape is not ownership: every run's token looks like this one's, and two people running the
  // suite against the same stack is a supported case. A live pid is what protects the other run.
  expect(sweepableRuns("mine00", records, alive).map((each) => each.run)).toEqual(["gone00"]);
});

test("the engine log filter keeps the chain's lines and the tail", { tag: ["@infra", "@tier1"] }, () => {
  const chain = { id: "7a4ccf3d", name: "e2e-abc123-loop" };
  const log = [
    "[chain_id=-] noise one",
    "[chain_id=7a4ccf3d] the chain started",
    "[chain_id=-] noise two",
    "[chain_id=7a4ccf3d] the chain finished",
    "[chain_id=-] noise three",
  ].join("\n");

  const filtered = filterChainLog(log, [chain], { matched: 10, tail: 2 });
  expect(filtered).toContain("the chain started");
  expect(filtered).toContain("the chain finished");
  expect(filtered).toContain("engine lines for e2e-abc123-loop (7a4ccf3d): 2");
  // The tail is kept whatever the filter matched: a route that never started logs nothing under
  // its own id, and the reason for that is in the lines around it.
  expect(filtered).toContain("noise three");
});

test("a chain that logged nothing is reported as silent, not as absent", { tag: ["@infra", "@tier1"] }, () => {
  const filtered = filterChainLog("[chain_id=-] noise", [{ id: "nope", name: "e2e-abc123-x" }]);
  expect(filtered).toContain("engine lines for e2e-abc123-x (nope): 0");
  expect(filtered).toContain("the chain logged nothing in this window");
});

test("the session link opens the trace under its chain", { tag: ["@infra", "@tier1"] }, () => {
  // Pinned to Compose, whose proxy answers on 8080, so the case holds under `npm run test:k8s` too.
  const original = process.env.QIP_TARGET;
  process.env.QIP_TARGET = "compose";
  try {
    const env = new ComposeEnv();
    // `ui/src/App.tsx:244` nests the session page inside `/chains/:chainId`, so a link without the
    // chain id resolves to nothing a person can read.
    expect(sessionLink(env, { id: "s-1", chainId: "c-1" })).toBe(
      "http://localhost:8080/chains/c-1/sessions/s-1",
    );
    expect(sessionLink(env, { id: "s-1" })).toBe("http://localhost:8080/sessions");
  } finally {
    if (original === undefined) delete process.env.QIP_TARGET;
    else process.env.QIP_TARGET = original;
  }
});
