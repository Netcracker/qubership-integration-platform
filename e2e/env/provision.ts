/**
 * The `globalSetup` of every run: the run token, the previous-run sweep, and the dispatch to the
 * target that brings the stack to the commit under test.
 *
 * Provisioning runs here rather than in a fixture: a cold service costs tens of seconds and a Maven
 * build costs minutes, and neither may be charged to a test's timeout. How a target provisions is
 * its own module under `env/provision/`, reached through `targetSetup()`, and the header facts,
 * the baseline, and the sampler come from the target's observer.
 */
import { request, type FullConfig } from "@playwright/test";
import { ensureRunToken, readRuns, recordRun, sweepableRuns, type RunRecord } from "../support/run.js";
import { Catalog } from "../support/catalog.js";
import { discardCorpusStateOf } from "../support/corpus.js";
import { formatPreviousRunSweep, sweepPreviousRuns } from "../support/fixtures.js";
import { writeStackHeader } from "../support/report.js";
import { targetSetup } from "./target-setup.js";

/** `auto` provisions; `never` only checks health, so a stack somebody is debugging is left alone. */
export type ProvisionMode = "auto" | "never";

export interface ProvisionReport {
  mode: ProvisionMode;
  durationMs: number;
  /** Maven modules installed because their sources outran the running image. */
  built: string[];
  /**
   * Images rebuilt because their sources outran them: Compose services, which are then recreated,
   * or the suite's images on Kubernetes, which Helm then rolls out.
   */
  rebuilt: string[];
  /**
   * Services recreated for a configuration change. On Compose, the compose file or an env file they
   * read changed. On Kubernetes, a restart left an environment override that the provisioner
   * reverted.
   */
  recreated: string[];
  /**
   * Services started: on Compose because they were missing or unhealthy, on Kubernetes because Helm
   * created their Deployment or changed its spec.
   */
  started: string[];
  /** Services left exactly as they were found. */
  untouched: string[];
  /**
   * Compose support services whose configuration has moved on and which the suite will not recreate.
   *
   * The compose file, and the configuration it bind-mounts: a container reads a mount when it is
   * created and never again. Reported rather than acted on, because their data lives in the
   * container and a recreate is a wipe.
   */
  staleSupport: string[];
  /** Whether nginx was reloaded, which only the Compose provisioner does. */
  proxyReloaded: boolean;
  /** The reload was for `infrastructure/nginx/` rather than for a container that changed address. */
  proxyConfigChanged: boolean;
}

export function provisionMode(): ProvisionMode {
  const raw = process.env.E2E_PROVISION ?? "auto";
  if (raw !== "auto" && raw !== "never") {
    throw new Error(`E2E_PROVISION must be "auto" or "never", got ${JSON.stringify(raw)}`);
  }
  return raw;
}

/**
 * The runs whose manifest entry the sweep dropped.
 *
 * The one decision `forgetRun` made, read back off the manifest rather than re-derived. The sweep
 * reports only the runs that had residue or a failed delete, so a run forgotten because its sweep
 * found nothing is absent from that report — and the corpus state it left would then survive the
 * run that collected it, which is exactly what `discardCorpusStateOf` exists to prevent.
 */
export function forgottenRuns(
  before: readonly RunRecord[],
  after: readonly RunRecord[],
): string[] {
  const recorded = new Set(after.map((each) => each.run));
  return [...new Set(before.map((each) => each.run))].filter((token) => !recorded.has(token));
}

export function formatReport(report: ProvisionReport): string {
  const seconds = (report.durationMs / 1000).toFixed(1);
  const lines = [`[provision] mode=${report.mode}, ${seconds}s`];
  if (report.built.length) lines.push(`[provision]   mvn install: ${report.built.join(", ")}`);
  if (report.rebuilt.length) lines.push(`[provision]   rebuilt: ${report.rebuilt.join(", ")}`);
  // Named apart from a rebuild, and worth the line: recreating a container takes seconds, so a
  // reader who sees this has not been charged the minutes a rebuild costs.
  if (report.recreated.length) {
    lines.push(
      `[provision]   recreated for a configuration change: ${report.recreated.join(", ")}`,
    );
  }
  if (report.started.length) lines.push(`[provision]   started: ${report.started.join(", ")}`);
  // The one line the suite prints instead of acting. Silence here reads as "the stack matches the
  // commit", and the container that does not match is the one holding the data every spec asserts
  // against.
  if (report.staleSupport.length) {
    lines.push(
      `[provision]   left as they are, though their configuration changed after they were created: ` +
        `${report.staleSupport.join(", ")}. The compose file, or a path it mounts into them, was ` +
        `written since. Their data lives in the container, so recreating one ` +
        `discards this stack's chains, sessions or deployments. Recreate by hand if the change ` +
        `concerns them: docker compose up -d --force-recreate ${report.staleSupport.join(" ")}`,
    );
  }
  if (report.proxyReloaded) {
    lines.push(
      report.proxyConfigChanged
        ? "[provision]   nginx reloaded: infrastructure/nginx changed"
        : "[provision]   nginx reloaded",
    );
  }
  if (
    !report.built.length &&
    !report.rebuilt.length &&
    !report.recreated.length &&
    !report.started.length &&
    // "already current" over a notice saying three containers are not would be the report
    // contradicting itself in consecutive lines.
    !report.staleSupport.length
  ) {
    lines.push(`[provision]   nothing to do, ${report.untouched.length} services already current`);
  }
  return lines.join("\n");
}

/**
 * The projects a `--project` selection named, in the order the command line gave them. Empty means
 * the run selected none, and a run that selected none runs every declared project.
 *
 * The argument vector is the only thing that can answer this. `config.grep` serialises to `{}`, and
 * `config.projects` lists every project the config declares whether or not one was selected —
 * `runFilters` in `registry/elements.ts` reads a finished run for the same reason.
 */
export function selectedProjects(argv: readonly string[]): string[] {
  const selected: string[] = [];
  for (let index = 0; index < argv.length; index++) {
    const argument = argv[index];
    if (argument.startsWith("--project=")) {
      selected.push(argument.slice("--project=".length));
      continue;
    }
    // `--project <name...>` is variadic, so `--project schema api` names two and the list ends at
    // the next option.
    if (argument === "--project") {
      while (index + 1 < argv.length && !argv[index + 1].startsWith("-")) {
        selected.push(argv[++index]);
      }
    }
  }
  return selected;
}

/**
 * Whether this run reaches the stack at all.
 *
 * Playwright runs `globalSetup` **once per run**, before any project, and has no flag that skips
 * it: measured on 1.62.1, a config declaring three projects produced one invocation. So without
 * this the `schema` project — the registries and the fixtures, read off disk — cannot run where
 * there is no Docker, which is CI. A project declares itself stack-free in
 * `playwright.config.ts` and nowhere else, and a selection is stack-free only when every project in
 * it is: an unrecognised name, or one project of eight that touches the stack, provisions as usual.
 */
export function needsStack(config: FullConfig): boolean {
  const selected = selectedProjects(config.argv);
  if (selected.length === 0) return true;

  const stackFree = new Set(
    config.projects
      .filter((project) => project.metadata?.stack === false)
      .map((project) => project.name),
  );
  return !selected.every((name) => stackFree.has(name));
}

export default async function globalSetup(config: FullConfig): Promise<void> {
  // Before the run token is minted, because everything downstream of it — the sweep, the run
  // manifest, the header, the sampler — addresses a stack this run has none of.
  if (!needsStack(config)) {
    console.log(`[provision] skipped: ${selectedProjects(config.argv).join(", ")} needs no stack`);
    return;
  }

  // Minted before anything else and exported through the environment, because the workers are
  // child processes and this assignment is how they all end up under one token. The manifest entry
  // goes with it: a run that is interrupted before its teardown is one a later run cleans up after,
  // and the token is the only handle on what it created.
  const run = ensureRunToken();
  recordRun(run);

  const setup = targetSetup();
  console.log(`[provision] target=${setup.name}`);
  const report = await setup.provision(provisionMode());
  console.log(formatReport(report));
  console.log(`[run] token=${run}, entities are named e2e-${run}-…`);

  // After provisioning, because it needs the catalog answering, and before any project, because a
  // previous run's chains are deployed and hold routes this run's fixtures do not collide with but
  // a person reading the stack does.
  const api = await request.newContext();
  try {
    // The candidates are read once and handed to the sweep, so the manifest before and after name
    // the same set of runs and the difference between them is what the sweep forgot.
    const candidates = sweepableRuns(run);
    const swept = await sweepPreviousRuns(new Catalog(api), run, candidates);
    console.log(formatPreviousRunSweep(swept));
    // Driven by the manifest rather than by the sweep's report, because they are not the same list:
    // the report holds only the runs that had residue or a failed delete, so a run forgotten because
    // its sweep found nothing would keep a corpus state naming chains that are gone, and the next
    // `--project=runtime --no-deps` re-run would fail on 404s instead of on "the seed did not run".
    // A run that kept its entry keeps its corpus state too: the state file is the handle on what is
    // left, and discarding it makes the residue uncollectable.
    discardCorpusStateOf(forgottenRuns(candidates, readRuns()));
  } finally {
    await api.dispose();
  }

  // The header of the report: what this run tested. Written here rather than at the end, because a
  // run that dies still has to say what it was pointed at.
  writeStackHeader({
    ...(await setup.observer.header({ run, workers: config.workers, provision: report })),
    target: setup.name,
    absent: setup.absent,
  });

  // The envelope every service is compared against at the end, taken after provisioning has
  // finished restarting things and before any project has asked the stack to do anything. The
  // sampler runs for the rest of the run: a peak lasts seconds, so nothing read afterwards is it.
  setup.observer.startSampler(run, await setup.observer.baseline());
}
