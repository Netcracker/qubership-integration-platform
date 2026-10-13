/**
 * What the run tested, and what it found: the two documents a person reads after the suite stops.
 *
 * `test-results/stack.json` is written at the head of the run and answers "what was this?" — the
 * commit, the build each service reports, the image every container runs, and what provisioning
 * did to get there. Context for it: a stale jar was tested for half a day because a `--build` alone
 * repackages whatever sits in `target/`. A report that does not say what it tested cannot be
 * trusted, and it is the header that makes the case catalog a release artifact rather than a log.
 *
 * `test-results/cases.md` is written after the run, by the same step that reconciles the coverage
 * registry, because both read the JSON reporter's output and Playwright writes it only once the
 * run is over. It is the document QA can hold: one table per component tag, a row per test, and
 * the path of a failed test's trace. The HTML report is for the person debugging one case.
 *
 * The module loads outside Playwright: `registry/reconcile.mjs` runs it under
 * `node --experimental-strip-types`, where a `.js` specifier pointing at a `.ts` file resolves to
 * nothing at all. Its imports therefore name the `.ts` file itself, which Node resolves and which
 * `allowImportingTsExtensions` in `tsconfig.json` lets `tsc` check. That is what lets the report
 * walk and the container table be shared with the registry rather than copied here: a fourth copy
 * of a recursive walk is a fourth place it can stop one level early and report nothing.
 *
 * `COMPONENT_TAGS` stays a parameter rather than an import, so `specs/schema/report.spec.ts` can
 * render the catalog against a tag set it names.
 */
import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";
import { fileURLToPath } from "node:url";
import { eachSpec, reportTarget } from "../registry/elements.ts";
import type { JsonReport, JsonResult, JsonTest } from "../registry/elements.ts";
import { CONTAINER } from "../env/compose-containers.ts";
import { serviceUrl } from "../env/containers.ts";
import type { ServiceRole } from "../env/index.ts";
import type { ProvisionReport } from "../env/provision.ts";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const RESULTS = path.join(HERE, "..", "test-results");

/** Written by `globalSetup`, read by the case catalog. */
export const STACK_FILE = path.join(RESULTS, "stack.json");
/** Written by `npm run reconcile`, beside the JSON report it is built from. */
const CASES_FILE = path.join(RESULTS, "cases.md");

// ---------------------------------------------------------------------------
// The run header
// ---------------------------------------------------------------------------

/** What one service reported about itself, and what its container is actually running. */
export interface ServiceFacts {
  role: string;
  url: string;
  /** `/actuator/info` for the Spring services, `/api/v1/mode` for the Go one. Null when silent. */
  build: unknown;
  container: string;
  imageId: string | null;
  imageCreated: string | null;
}

/** A project or spec file the run leaves out, and why. */
export interface Absence {
  name: string;
  reason: string;
}

export interface StackHeader {
  run: string;
  /** `compose` or `k8s`. */
  target: string;
  /** What this target does not run, so a missing project reads as a decision rather than a loss. */
  absent: Absence[];
  startedAt: string;
  /** `null` on every field when `git` could not answer: a guess here is worse than an admission. */
  commit: { sha: string | null; branch: string | null; dirty: boolean | null };
  workers: number;
  provision: ProvisionReport;
  services: ServiceFacts[];
  /** The Helm release a Kubernetes run tested; absent on Compose. */
  release?: ReleaseFacts;
}

/** What the header records about the Helm release on the Kubernetes target. */
export interface ReleaseFacts {
  context: string | null;
  namespace: string;
  name: string;
  revision: number | null;
  status: string | null;
  chart: string | null;
}

/**
 * What each service is asked about itself. The URL and the container come from `env/containers.ts`.
 *
 * Only the info path lives here: it is the one thing that differs per role and has nothing to do
 * with addressing. Everything else is read off the table `ComposeEnv.url` reads, so a port that
 * moves moves once.
 */
const PROBED: Array<{ role: ServiceRole; info: string }> = [
  { role: "runtime-catalog", info: "/actuator/info" },
  { role: "engine", info: "/actuator/info" },
  { role: "sessions-management", info: "/actuator/info" },
  // The Go service serves no actuator; `/api/v1/mode` is the one thing it says about itself.
  { role: "testing-service", info: "/api/v1/mode" },
];

/**
 * One `git` reading, or `null` when the command could not run.
 *
 * `null` rather than `""`, because `""` is a valid-looking answer: it reads as a clean working tree
 * and prints as a commit on a branch with no name. The header exists to stop a run being credited to
 * the wrong tree — a stale jar was tested for half a day — so an unknown answers unknown.
 */
function git(args: string[]): string | null {
  try {
    // `HERE` is inside the checkout, which is all `git` needs to answer about the commit.
    return execFileSync("git", args, { cwd: HERE, encoding: "utf-8" }).trim();
  } catch {
    return null;
  }
}

function inspect(container: string, format: string): string | null {
  try {
    return execFileSync("docker", ["inspect", "-f", format, container], {
      encoding: "utf-8",
    }).trim();
  } catch {
    return null;
  }
}

/** What a target's observer collects; `globalSetup` adds the target and what it leaves out. */
export type StackFacts = Omit<StackHeader, "target" | "absent">;

/**
 * Everything the header records about a Compose stack, collected once.
 *
 * Every probe answers null rather than throwing: a header that cannot be written turns a green run
 * red for no reason a reader would accept, and a missing field says as much as a missing file.
 */
export async function collectStackHeader(about: {
  run: string;
  workers: number;
  provision: ProvisionReport;
}): Promise<StackFacts> {
  const services: ServiceFacts[] = [];
  for (const each of PROBED) {
    const container = CONTAINER[each.role];
    const url = serviceUrl(each.role);
    const build = await fetch(`${url}${each.info}`, { signal: AbortSignal.timeout(5_000) })
      .then((response) => (response.ok ? (response.json() as Promise<unknown>) : null))
      .catch(() => null);
    services.push({
      role: each.role,
      url,
      build,
      container,
      imageId: inspect(container, "{{.Image}}"),
      imageCreated: inspect(container, "{{.Created}}"),
    });
  }

  return {
    run: about.run,
    startedAt: new Date().toISOString(),
    commit: collectCommitFacts(),
    workers: about.workers,
    provision: about.provision,
    services,
  };
}

/** The commit under test, for the header of either target. */
export function collectCommitFacts(): StackHeader["commit"] {
  const status = git(["status", "--porcelain"]);
  return {
    sha: git(["rev-parse", "HEAD"]),
    branch: git(["rev-parse", "--abbrev-ref", "HEAD"]),
    dirty: status === null ? null : status !== "",
  };
}

export function writeStackHeader(header: StackHeader): void {
  fs.mkdirSync(path.dirname(STACK_FILE), { recursive: true });
  fs.writeFileSync(STACK_FILE, JSON.stringify(header, null, 2));
}

function readStackHeader(file: string = STACK_FILE): StackHeader | null {
  if (!fs.existsSync(file)) return null;
  return JSON.parse(fs.readFileSync(file, "utf-8")) as StackHeader;
}

// ---------------------------------------------------------------------------
// The case catalog
// ---------------------------------------------------------------------------

export interface CaseRow {
  title: string;
  file: string;
  project: string;
  /** Playwright's outcome, translated: `passed`, `failed`, `flaky`, `skipped`, `timed out`. */
  status: string;
  tags: string[];
  covers: string[];
  durationMs: number;
  /** Where the trace of a failed run landed, when one was retained. */
  trace?: string;
}

/** Playwright's `status` on a test, in the words the catalog's reader uses. */
function outcome(test: JsonTest, last: JsonResult | undefined): string {
  if (test.status === "expected") return "passed";
  if (test.status === "flaky") return "flaky";
  if (test.status === "skipped") return "skipped";
  return last?.status === "timedOut" ? "timed out" : "failed";
}

/** One row per test in the report, in the order Playwright listed them. */
export function caseRows(report: JsonReport): CaseRow[] {
  const rows: CaseRow[] = [];

  // `eachSpec` rather than a walk of its own: the registry already flattens the report's arbitrary
  // suite nesting, and a walk that stops one level early reports nothing rather than failing.
  for (const spec of eachSpec(report)) {
    for (const test of spec.tests ?? []) {
      const results = test.results ?? [];
      const last = results[results.length - 1];
      rows.push({
        title: spec.title ?? "?",
        file: spec.file ?? "?",
        project: test.projectName ?? "?",
        status: outcome(test, last),
        tags: spec.tags ?? [],
        covers: (test.annotations ?? [])
          .filter((each) => each.type === "covers" && each.description)
          .map((each) => each.description as string),
        durationMs: last?.duration ?? 0,
        trace: (last?.attachments ?? []).find((each) => each.name === "trace")?.path,
      });
    }
  }
  return rows;
}

function escapeCell(text: string): string {
  return text.replace(/\|/g, "\\|");
}

/** A Docker image id shortened to 12 digits; an image reference, as on Kubernetes, by its name. */
function imageLabel(imageId: string | null): string {
  if (imageId === null) return "-";
  return imageId.startsWith("sha256:")
    ? imageId.replace("sha256:", "").slice(0, 12)
    : (imageId.split("/").pop() ?? imageId);
}

function headerSection(header: StackHeader | null, report: JsonReport): string[] {
  const stats = report.stats ?? {};
  const wall = typeof stats.duration === "number" ? `${(stats.duration / 1000).toFixed(1)} s` : "?";
  const lines: string[] = [];

  const target = reportTarget(report);
  if (!header) {
    lines.push(
      "No `test-results/stack.json`, so this catalog cannot say what it was run against.",
      "The header is written by `globalSetup`; a catalog without one came from a report alone.",
      "",
    );
  } else if (header.target !== target) {
    // A report copied aside and reconciled after a run on the other target meets that run's header.
    lines.push(
      `- Target: ${target}`,
      "",
      `\`test-results/stack.json\` describes a run on ${header.target}, not the ${target} run this ` +
        "report came from, so the rest of the header is left out.",
      "",
    );
  } else {
    // `git` answering nothing renders as `unknown`, never as a clean tree on an unnamed branch.
    const dirty =
      header.commit.dirty === null
        ? ", working tree unknown"
        : header.commit.dirty
          ? ", working tree dirty"
          : "";
    lines.push(
      `- Target: ${header.target}`,
      ...(header.absent.length
        ? [
            `- Not run on this target: ` +
              header.absent.map((each) => `${each.name} (${each.reason})`).join(", "),
          ]
        : []),
      `- Run token: \`${header.run}\``,
      `- Commit: \`${header.commit.sha?.slice(0, 12) ?? "unknown"}\` on ` +
        `\`${header.commit.branch ?? "unknown"}\`${dirty}`,
      `- Started: ${header.startedAt}`,
      `- Workers: ${header.workers}`,
      ...(header.release
        ? [
            `- Helm release: ${header.release.name} in ${header.release.namespace}, revision ` +
              `${header.release.revision ?? "unknown"}, ${header.release.status ?? "status unknown"}, ` +
              `chart ${header.release.chart ?? "unknown"}, kube-context ` +
              `${header.release.context ?? "unknown"}`,
          ]
        : []),
      `- Provisioning: mode=${header.provision.mode}, ` +
        `${(header.provision.durationMs / 1000).toFixed(1)} s` +
        (header.provision.built.length ? `, built ${header.provision.built.join(", ")}` : "") +
        (header.provision.rebuilt.length
          ? `, rebuilt ${header.provision.rebuilt.join(", ")}`
          : "") +
        (header.provision.recreated.length
          ? `, recreated ${header.provision.recreated.join(", ")}`
          : "") +
        (header.provision.started.length
          ? `, started ${header.provision.started.join(", ")}`
          : ""),
      "",
    );
    // Its own line rather than another comma-separated item, because it is the one entry that says
    // what provisioning did **not** do. Postgres, OpenSearch and Consul mount no volume, so a
    // recreate discards this stack's chains, sessions and deployments and the suite refuses to make
    // that call — which leaves a container running a configuration the checkout has moved past. The
    // catalog exists to say what was tested, so a reader who is not told is reading a stack
    // description that is quietly wrong.
    if (header.provision.staleSupport.length) {
      lines.push(
        `> **Support containers whose configuration changed after they were created:** ` +
          `${header.provision.staleSupport.join(", ")}. The compose file, or a path it mounts into ` +
          `them — \`infrastructure/init-db\`, \`infrastructure/opensearch/opensearch.yml\` — was ` +
          `written since. Left as they were found: their data lives in the container, so recreating ` +
          `one discards this stack's chains, sessions or deployments. Recreate by hand if the ` +
          `change concerns them: ` +
          `\`docker compose up -d --force-recreate ${header.provision.staleSupport.join(" ")}\`.`,
        "",
      );
    }
    lines.push(
      "| Service | Version | Built | Image | Image created |",
      "| --- | --- | --- | --- | --- |",
    );
    for (const service of header.services) {
      const build = (service.build ?? {}) as { build?: { version?: string; time?: string } };
      // The Go service serves no build info, only `{"production": false}`. Printing what it did
      // say beats a dash, which reads as "did not answer".
      const version =
        build.build?.version ?? (service.build ? JSON.stringify(service.build).slice(0, 60) : "-");
      lines.push(
        `| ${service.role} | ${version} | ${build.build?.time ?? "-"} | ` +
          `${imageLabel(service.imageId)} | ` +
          `${service.imageCreated ?? "-"} |`,
      );
    }
    lines.push("");
  }

  lines.push(
    `Wall time ${wall}: ${stats.expected ?? 0} passed, ${stats.unexpected ?? 0} failed, ` +
      `${stats.flaky ?? 0} flaky, ${stats.skipped ?? 0} skipped.`,
    "",
  );
  return lines;
}

function table(rows: CaseRow[]): string[] {
  const lines = [
    "| Test | Covers | Project | Status | Trace |",
    "| --- | --- | --- | --- | --- |",
  ];
  for (const row of rows) {
    lines.push(
      `| ${escapeCell(row.title)} | ${escapeCell(row.covers.join(", ")) || "-"} | ` +
        `${row.project} | ${row.status} | ${row.trace ? escapeCell(row.trace) : "-"} |`,
    );
  }
  return lines;
}

/**
 * The catalog, grouped by component tag.
 *
 * A test carrying two component tags appears under both: the grouping is for the person who owns
 * one service and wants that service's cases, and dropping the second tag would hide a case from
 * one of the two people it concerns. `reconcile()` fails a test that carries no component tag at
 * all, so the untagged group is empty in a run that passed.
 */
export function renderCatalog(
  report: JsonReport,
  header: StackHeader | null,
  componentTags: readonly string[],
): string {
  const rows = caseRows(report);
  const lines: string[] = ["# End-to-end cases", ""];
  lines.push(...headerSection(header, report));

  for (const tag of componentTags) {
    const tagged = rows.filter((row) => row.tags.includes(tag));
    if (tagged.length === 0) continue;
    const failed = tagged.filter((row) => row.status !== "passed" && row.status !== "skipped");
    lines.push(
      `## @${tag}`,
      "",
      `${tagged.length} case(s), ${failed.length} not passing.`,
      "",
      ...table(tagged),
      "",
    );
  }

  const untagged = rows.filter(
    (row) => !row.tags.some((tag) => componentTags.includes(tag)),
  );
  if (untagged.length > 0) {
    lines.push(
      "## No component tag",
      "",
      "These cases are invisible to `--grep @engine` and to the grouping above. " +
        "`npm run reconcile` fails on them.",
      "",
      ...table(untagged),
      "",
    );
  }

  return lines.join("\n");
}

/** Writes the catalog beside the report it was built from, and answers with the path. */
export function writeCaseCatalog(
  report: JsonReport,
  componentTags: readonly string[],
  file: string = CASES_FILE,
): string {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, `${renderCatalog(report, readStackHeader(), componentTags)}\n`);
  return file;
}
