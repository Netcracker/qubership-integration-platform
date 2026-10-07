/**
 * What the run cost the stack, and whether the services came out of it as the processes that went in.
 *
 * Two readings, taken at different times and for different reasons.
 *
 * The **envelope** is identity: the process behind each role, its restart count, and how close its
 * Metaspace sits to the ceiling it was given. It is taken once in `globalSetup`, before any project
 * runs, and again by `specs/env/process-envelope.spec.ts` once the load is over. A catalog killed
 * by an import surfaces today as dozens of unrelated red specs; comparing the two readings makes it
 * one named failure. Both Metaspace defects this product has had were found with `jcmd` rather than
 * through an API, and nothing else in the suite watches for either.
 *
 * The **peak** is cost, and it has to be sampled. Measured while this was written: peak memory and
 * peak CPU run at roughly twice the idle reading and the peak lasts seconds, so a single
 * `docker stats` after the run reports a stack at rest. So a loop runs for the whole run, keeps the
 * maximum per container, and writes it where a spec can read it — the workers are separate
 * processes, so a file is the only thing the runner can hand them.
 *
 * CPU is recorded beside memory because memory is not the resource that runs out first: the stack
 * reached 94% of its 12 allocated cores under a load lighter than a full run while using 29% of its
 * memory.
 *
 * The parsing here is exercised by `specs/schema/resources.spec.ts`, which needs no stack.
 */
import { spawn } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import type { Metaspace, ProcessEnvelope, ResourcePeak, ServiceRole } from "./index.js";
import { CONTAINER, roleOfContainer } from "./compose-containers.js";
import { ROLES } from "./containers.js";
import { stateFileName } from "./target.js";
import { capture } from "./host.js";
import { readStateFile, writeStateFile } from "../support/state-file.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/**
 * Where the sampler leaves what it saw, with a name of its own per target.
 *
 * Outside `test-results/`, which Playwright clears at the start of every run — and the file is
 * written by `globalSetup`, which runs after that clearing but is read by workers that may outlive
 * a `--no-deps` re-run of one case.
 */
export const RESOURCES_FILE =
  process.env.E2E_RESOURCES_FILE ?? path.join(HERE, "..", stateFileName(".e2e-resources.json"));

/** How often the loop reads the stack. `docker stats --no-stream` itself costs about a second. */
const SAMPLE_INTERVAL_MS = Number(process.env.E2E_SAMPLE_INTERVAL_MS ?? 5_000);

/** The sampler's own lifetime, so an interrupted run leaves nothing reading the stack forever. */
const MAX_SAMPLE_MS = Number(process.env.E2E_SAMPLE_MAX_MS ?? 30 * 60_000);

/** Where the loop appends its readings: one `docker stats` row per container per tick. */
export const SAMPLES_FILE =
  process.env.E2E_SAMPLES_FILE ?? path.join(HERE, "..", stateFileName(".e2e-samples.jsonl"));

export interface RunResources {
  startedAt: string;
  /**
   * The run token of the run that started this sampler.
   *
   * Ownership, and it is read at the end: a stack-free `--project=schema` run has no sampler of its
   * own, and stopping the one it finds takes the readings out from under a full run going on beside
   * it — whose `process-envelope` band check then fails on an empty file.
   */
  run?: string;
  /** The envelope of every role before any project ran, which is what the end is compared against. */
  baseline: ProcessEnvelope[];
  /** The detached loop doing the sampling, so a later run can stop one this run left behind. */
  samplerPid?: number;
  /**
   * When that pid was started, as this host's uptime clock reads it.
   *
   * A pid alone is not an identity across runs: the file outlives the process, and after a reboot
   * or a pid wrap the number names something else entirely — which `stopResourceSampler` would then
   * signal. This is checked against the process's own start time before any signal is sent.
   */
  samplerStartedAtTicks?: number;
}

// ---------------------------------------------------------------------------
// Reading one sample
// ---------------------------------------------------------------------------

/** One row of `docker stats --format "{{json .}}"`, in the fields this module reads. */
export interface StatsRow {
  Name?: string;
  MemUsage?: string;
  CPUPerc?: string;
}

const UNITS: Record<string, number> = {
  B: 1,
  KIB: 1024,
  MIB: 1024 ** 2,
  GIB: 1024 ** 3,
  TIB: 1024 ** 4,
  KB: 1000,
  MB: 1000 ** 2,
  GB: 1000 ** 3,
  TB: 1000 ** 4,
};

/**
 * `983.1MiB` as bytes, and `0` for anything that does not parse.
 *
 * Both spellings are needed from one function: `docker stats` reports `MiB` and `jcmd` reports
 * `MB`, and the two are not the same number.
 */
export function parseSize(text: string): number {
  const found = /^\s*([\d.]+)\s*([A-Za-z]+)\s*$/.exec(text);
  if (!found) return 0;
  const unit = UNITS[found[2].toUpperCase()];
  return unit === undefined ? 0 : Number(found[1]) * unit;
}

/** The used half of `983.1MiB / 15.62GiB`, in bytes. */
export function parseMemUsage(text: string): number {
  return parseSize(text.split("/")[0] ?? "");
}

/** `5.56%` as `5.56`. Percent of one core, so a value above 100 is more than one core. */
export function parsePercent(text: string): number {
  return Number(text.replace("%", "").trim()) || 0;
}

/** The rows of one `docker stats` reading, reduced to the roles the suite watches. */
export function readingOf(rows: readonly StatsRow[]): ResourcePeak[] {
  const found: ResourcePeak[] = [];
  for (const row of rows) {
    const role = roleOfContainer(row.Name ?? "");
    if (role === null) continue;
    found.push({
      role,
      memoryBytes: parseMemUsage(row.MemUsage ?? ""),
      cpuPercent: parsePercent(row.CPUPerc ?? ""),
      samples: 1,
    });
  }
  return found;
}

/**
 * Folds a reading into the peaks so far, keeping the maximum of each number independently.
 *
 * Independently on purpose: the memory peak and the CPU peak of one service rarely land in the same
 * sample, and pairing them would report whichever number happened to share a tick with the other.
 */
export function mergePeaks(
  into: readonly ResourcePeak[],
  reading: readonly ResourcePeak[],
): ResourcePeak[] {
  const by = new Map(into.map((each) => [each.role, { ...each }]));
  for (const each of reading) {
    const known = by.get(each.role);
    if (known === undefined) {
      by.set(each.role, { ...each });
      continue;
    }
    known.memoryBytes = Math.max(known.memoryBytes, each.memoryBytes);
    known.cpuPercent = Math.max(known.cpuPercent, each.cpuPercent);
    known.samples += 1;
  }
  return ROLES.filter((role) => by.has(role)).map((role) => by.get(role) as ResourcePeak);
}

/**
 * `jcmd 1 VM.metaspace basic`, reduced to the two numbers a band is stated against.
 *
 * The `used.` suffix is what selects the usage total: the same report carries a second `Both:` line
 * under `Virtual space:`, which is what was reserved rather than what is committed.
 */
export function parseMetaspace(report: string): Metaspace | null {
  const used = /Both:\s+([\d.]+\s*[A-Za-z]+)\s+used\./.exec(report);
  const max = /MaxMetaspaceSize:\s+([\d.]+\s*[A-Za-z]+)/.exec(report);
  if (!used || !max) return null;
  return { usedBytes: parseSize(used[1]), maxBytes: parseSize(max[1]) };
}

// ---------------------------------------------------------------------------
// The bands
// ---------------------------------------------------------------------------

/** What one service is allowed to reach before the run says so. */
export interface Band {
  memoryBytes: number;
  cpuPercent: number;
  /** The share of `MaxMetaspaceSize` the JVM may commit. */
  metaspaceShare: number;
}

const MIB = 1024 ** 2;

/**
 * The stated bands, and the readings they were stated from.
 *
 * Measured over a full 307-test run on this stack — peak memory, peak CPU, Metaspace share:
 * engine 1079 MiB, 324%, 83%; runtime-catalog 691 MiB, 524%, 58%; sessions-management 406 MiB, 5%,
 * 35%; testing-service 37 MiB, 2%, no JVM. Total 2214 MiB and 855% CPU. Each band sits well above
 * its reading, because the check exists to catch a service that leaked or spun rather than one that
 * moved.
 *
 * CPU is a percentage of **one** core, so 900 is nine of the twelve this stack is allocated.
 *
 * The Metaspace share is the tightest of the three deliberately: every Metaspace defect this product
 * has had was that share growing.
 */
export const BANDS: Record<ServiceRole, Band> = {
  engine: { memoryBytes: 3072 * MIB, cpuPercent: 900, metaspaceShare: 0.95 },
  "runtime-catalog": { memoryBytes: 2048 * MIB, cpuPercent: 900, metaspaceShare: 0.95 },
  "sessions-management": { memoryBytes: 1536 * MIB, cpuPercent: 500, metaspaceShare: 0.95 },
  "testing-service": { memoryBytes: 512 * MIB, cpuPercent: 400, metaspaceShare: 0.95 },
};

/** Every peak that left its band, named the way the failure reads. */
export function outsideBand(
  peaks: readonly ResourcePeak[],
  bands: Record<ServiceRole, Band> = BANDS,
): string[] {
  const over: string[] = [];
  for (const peak of peaks) {
    const band = bands[peak.role];
    if (band === undefined) continue;
    if (peak.memoryBytes > band.memoryBytes) {
      over.push(
        `${peak.role} peaked at ${mib(peak.memoryBytes)} MiB, above its ${mib(band.memoryBytes)} MiB band`,
      );
    }
    if (peak.cpuPercent > band.cpuPercent) {
      over.push(
        `${peak.role} peaked at ${peak.cpuPercent.toFixed(0)}% CPU, above its ${band.cpuPercent}% band`,
      );
    }
  }
  return over;
}

/** Every JVM whose committed Metaspace left its share of the ceiling. */
export function metaspaceOutsideBand(
  envelopes: readonly ProcessEnvelope[],
  bands: Record<ServiceRole, Band> = BANDS,
): string[] {
  const over: string[] = [];
  for (const envelope of envelopes) {
    const band = bands[envelope.role];
    if (band === undefined || envelope.metaspace === null) continue;
    const { usedBytes, maxBytes } = envelope.metaspace;
    if (maxBytes === 0) continue;
    const share = usedBytes / maxBytes;
    if (share > band.metaspaceShare) {
      over.push(
        `${envelope.role} committed ${(share * 100).toFixed(0)}% of its ${mib(maxBytes)} MiB ` +
          `Metaspace ceiling, above its ${(band.metaspaceShare * 100).toFixed(0)}% band`,
      );
    }
  }
  return over;
}

function mib(bytes: number): string {
  return (bytes / MIB).toFixed(0);
}

/** The peaks in the words the run prints them, with the total the cost table quotes. */
export function formatPeaks(peaks: readonly ResourcePeak[]): string {
  const lines = peaks.map(
    (each) =>
      `[peak] ${each.role}: ${mib(each.memoryBytes)} MiB, ${each.cpuPercent.toFixed(0)}% CPU ` +
      `over ${each.samples} samples`,
  );
  const memory = peaks.reduce((sum, each) => sum + each.memoryBytes, 0);
  const cpu = peaks.reduce((sum, each) => sum + each.cpuPercent, 0);
  lines.push(`[peak] total: ${mib(memory)} MiB, ${cpu.toFixed(0)}% CPU`);
  return lines.join("\n");
}

// ---------------------------------------------------------------------------
// The sampler
// ---------------------------------------------------------------------------

/** The record, or `null` for none. A half-written file reads as none, which costs one baseline. */
export function readRunResources(file: string = RESOURCES_FILE): RunResources | null {
  return readStateFile<RunResources>(file);
}

/**
 * Replaces the record through `writeStateFile`'s staging file and rename.
 *
 * The record holds the only handle on a sampler that is already running. Truncated in place, a kill
 * during the write would leave `readRunResources` answering `null`, no later run could stop that
 * sampler, and the next run would start a second one against the same samples file.
 */
export function writeRunResources(resources: RunResources, file: string = RESOURCES_FILE): void {
  writeStateFile(file, resources);
}

/**
 * The peaks, folded out of the samples the loop appended.
 *
 * The fold happens on read rather than in the sampler, which is what lets the sampler be a shell
 * loop with no knowledge of any of this: it appends `docker stats` rows and nothing else.
 */
export function foldSamples(lines: readonly string[]): ResourcePeak[] {
  let peaks: ResourcePeak[] = [];
  for (const line of lines) {
    if (line.trim().length === 0) continue;
    let row: StatsRow;
    try {
      row = JSON.parse(line) as StatsRow;
    } catch {
      // The last line of a file being appended to is routinely half-written. Skipping it costs one
      // sample out of hundreds; failing on it would make the check flaky for no reading gained.
      continue;
    }
    peaks = mergePeaks(peaks, readingOf([row]));
  }
  return peaks;
}

export function readPeaks(file: string = SAMPLES_FILE): ResourcePeak[] {
  if (!fs.existsSync(file)) return [];
  return foldSamples(fs.readFileSync(file, "utf-8").split("\n"));
}

/** One `docker stats` reading of the watched containers, as JSON rows. */
function dockerStatsSample(): string {
  const containers = ROLES.map((role) => CONTAINER[role]).join(" ");
  return `docker stats --no-stream --format '{{json .}}' ${containers}`;
}

/**
 * Starts the sampling loop, as a detached shell loop appending `docker stats` rows to a file.
 *
 * `sample` is the shell command of one reading. The Kubernetes observer passes `kubectl top`, and
 * folds its rows with a parser of its own.
 *
 * A separate process rather than a timer, and that is measured rather than defensive: an interval
 * started in `globalSetup` never fires, because Playwright runs that hook in a process which exits
 * as soon as it returns — the peaks file stayed empty, which is exactly the failure this check
 * exists to prevent, a number nobody is measuring.
 *
 * A shell loop rather than a Node child for a second measured reason: `node
 * --experimental-strip-types` cannot resolve a `.js` specifier that points at a `.ts` file, so a
 * child importing this module fails to start at all. The loop therefore knows nothing but the
 * container names, and every reading is folded on the way out by `readPeaks`.
 *
 * It stops itself after `MAX_SAMPLE_MS`, so a run that never reaches its teardown — a Ctrl-C on a
 * suite that was misbehaving — leaves nothing reading the stack forever, and a later run stops
 * whatever the last one left before starting its own.
 */
export function startResourceSampler(
  run: string,
  baseline: readonly ProcessEnvelope[],
  sample: string = dockerStatsSample(),
): number | null {
  // No owner: whatever sampler is on this host belongs to a run that is over, and a run that is
  // not over holds the stack anyway.
  stopResourceSampler();
  fs.mkdirSync(path.dirname(SAMPLES_FILE), { recursive: true });
  fs.writeFileSync(SAMPLES_FILE, "");

  const seconds = Math.max(1, Math.round(SAMPLE_INTERVAL_MS / 1000));
  const lifetime = Math.round(MAX_SAMPLE_MS / 1000);
  // `$1` rather than the path inlined into the script: the samples file is caller-supplied through
  // `E2E_SAMPLES_FILE`, and a quote in it would end the quoting and hand the rest to `sh`.
  const child = spawn(
    "sh",
    [
      "-c",
      `end=$(( $(date +%s) + ${lifetime} )); ` +
        `while [ "$(date +%s)" -lt "$end" ]; do ` +
        `${sample} >> "$1" 2>/dev/null; ` +
        `sleep ${seconds}; done`,
      "sh",
      SAMPLES_FILE,
    ],
    { detached: true, stdio: "ignore" },
  );
  child.unref();

  // Nothing may await between the spawn above and the write below. Both are synchronous, so no
  // signal handler and no timer runs between them: a Ctrl-C is queued until the tick ends, and the
  // record naming the pid is on disk by then. An `await` here would open the one window in which a
  // sampler is running and nothing on disk can stop it.
  const startTicks = child.pid === undefined ? null : processStartTicks(child.pid);
  writeRunResources({
    startedAt: new Date().toISOString(),
    run,
    baseline: [...baseline],
    ...(child.pid === undefined ? {} : { samplerPid: child.pid }),
    ...(startTicks === null ? {} : { samplerStartedAtTicks: startTicks }),
  });
  return child.pid ?? null;
}

/**
 * When a pid was started, in clock ticks since boot, or `null` where that cannot be read.
 *
 * Field 22 of `/proc/{pid}/stat`, which is the one thing that separates a live pid from a recycled
 * one. The parse starts after the last `)` because field 2 is the executable name and may itself
 * hold spaces and parentheses.
 *
 * `null` on a host with no `procfs`, and on a pid that is already gone. Neither is a failure: the
 * caller reads `null` as "nothing to verify against" and as "nothing to signal" respectively.
 */
function processStartTicks(pid: number): number | null {
  let stat: string;
  try {
    stat = fs.readFileSync(`/proc/${pid}/stat`, "utf-8");
  } catch {
    return null;
  }
  // The slice drops fields 1 and 2, so field 22 is the twentieth of what is left.
  const ticks = Number(stat.slice(stat.lastIndexOf(")") + 2).split(" ")[19]);
  return Number.isFinite(ticks) ? ticks : null;
}

/**
 * Stops the sampler the file names, this run's or an interrupted run's.
 *
 * `owner` is the run token of the caller, and the sampler is left alone when the file names a
 * different run. Without it a run that started no sampler still stops one.
 *
 * The pid comes out of a file the previous run wrote, so it is verified before it is signalled:
 * after a reboot or a pid wrap it names an unrelated process, and `process.kill(-pid)` would take
 * that process's whole group down. The recorded start time is what tells the two apart. Where no
 * start time was recordable — a host without `procfs` — the pid is signalled unverified, which is
 * the behavior a sampler that nothing else stops needs.
 */
export function stopResourceSampler(file: string = RESOURCES_FILE, owner?: string): boolean {
  const resources = readRunResources(file);
  if (!resources?.samplerPid) return false;
  // No owner given means "stop whatever is there", which is what a starting run wants: the sampler
  // it finds belongs to a run that has finished or been killed. A caller that names an owner is
  // ending its own run and may only stop its own sampler.
  if (owner !== undefined && resources.run !== undefined && resources.run !== owner) return false;
  const recorded = resources.samplerStartedAtTicks;
  const running = recorded === undefined ? null : processStartTicks(resources.samplerPid);
  if (recorded !== undefined && running !== recorded) {
    console.warn(
      `[peak] sampler pid ${resources.samplerPid} is gone or belongs to another process now, ` +
        `so it was not signalled`,
    );
  } else {
    try {
      // The negative pid is the process **group**: the loop is `sh` with a `docker` child, and
      // killing only the shell leaves the reading it was in the middle of behind.
      process.kill(-resources.samplerPid);
    } catch {
      // Already gone, which is the normal case for a file a previous run left behind.
    }
  }
  const { samplerPid: _pid, samplerStartedAtTicks: _ticks, ...rest } = resources;
  writeRunResources(rest, file);
  return true;
}

// ---------------------------------------------------------------------------
// The envelope, on Docker Compose
// ---------------------------------------------------------------------------

/** The envelope of one role, read from its container and the JVM inside it. */
export async function composeProcessEnvelope(role: ServiceRole): Promise<ProcessEnvelope> {
  const stdout = await capture("docker", [
    "inspect",
    "-f",
    "{{.State.StartedAt}}|{{.RestartCount}}",
    CONTAINER[role],
  ]);
  const [startedAt, restarts] = stdout.trim().split("|");
  // The Go service has no JVM, and `jcmd` is absent rather than silent there, so a failure to read
  // Metaspace is reported as "no Metaspace" rather than as a broken service.
  const metaspace = await capture("docker", ["exec", CONTAINER[role], "jcmd", "1", "VM.metaspace", "basic"])
    .then(parseMetaspace)
    .catch(() => null);
  return { role, startedAt, restarts: Number(restarts) || 0, metaspace };
}

/** The envelope of every watched role, for the baseline the run header carries. */
export async function composeBaseline(): Promise<ProcessEnvelope[]> {
  const envelopes: ProcessEnvelope[] = [];
  for (const role of ROLES) envelopes.push(await composeProcessEnvelope(role));
  return envelopes;
}
