import path from "node:path";
import type { Env, ProcessEnvelope, ResourcePeak } from "./index.js";
import { ROLES } from "./containers.js";
import { readRunResources, RESOURCES_FILE } from "./resources.js";
import { stateFileName } from "./target.js";
import { readStateFile, writeStateFile } from "../support/state-file.js";

const SNAPSHOT_FILE = path.join(path.dirname(RESOURCES_FILE), stateFileName(".e2e-process-snapshot.json"));

export type Reading<T> = { value: T } | { error: string };

export interface ProcessSnapshot {
  run: string;
  baseline: ProcessEnvelope[];
  envelopes: Reading<ProcessEnvelope[]>;
  peaks: Reading<ResourcePeak[]>;
}

async function capture<T>(read: () => Promise<T>): Promise<Reading<T>> {
  try {
    return { value: await read() };
  } catch (cause) {
    return { error: cause instanceof Error ? cause.message : String(cause) };
  }
}

/** Saves observations and read errors before any intentional restart. Assertions run in teardown. */
export async function saveProcessSnapshot(
  env: Pick<Env, "processEnvelope" | "resourcePeaks">,
  run: string,
  file: string = SNAPSHOT_FILE,
  baseline: ProcessEnvelope[] = readRunResources()?.baseline ?? [],
): Promise<void> {
  const envelopes = await capture(async () => {
    const found: ProcessEnvelope[] = [];
    for (const role of ROLES) found.push(await env.processEnvelope(role));
    return found;
  });
  const peaks = await capture(() => env.resourcePeaks());
  writeStateFile<ProcessSnapshot>(file, { run, baseline, envelopes, peaks });
}

export function readProcessSnapshot(run: string, file: string = SNAPSHOT_FILE): ProcessSnapshot {
  const snapshot = readStateFile<ProcessSnapshot>(file);
  if (snapshot?.run !== run) {
    throw new Error(`No process snapshot for run ${run}; run the process-snapshot project first.`);
  }
  return snapshot;
}

export function capturedValue<T>(reading: Reading<T>): T {
  if ("error" in reading) throw new Error(reading.error);
  return reading.value;
}
