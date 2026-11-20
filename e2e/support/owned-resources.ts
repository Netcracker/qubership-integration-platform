import { randomUUID } from "node:crypto";
import { mkdirSync, rmSync } from "node:fs";
import path from "node:path";
import { externalSettings } from "../env/external-settings.js";
import { inAuthPhase } from "../env/external-auth.js";
import { writeStateFile } from "./state-file.js";

export interface OwnedResource {
  kind: string;
  id: string;
}

export function recordResource(resource: OwnedResource): string | undefined {
  const settings = externalSettings();
  if (!settings) return;
  mkdirSync(settings.stateDir, { recursive: true });
  const journal = path.join(settings.stateDir, `${resource.kind}-${randomUUID()}.json`);
  writeStateFile(journal, { run: settings.run, ...resource });
  return journal;
}

export function forgetResource(journal: string | undefined): void {
  if (journal) rmSync(journal, { force: true });
}

export async function ownResource<T>(resource: OwnedResource, use: () => Promise<T>, remove: () => Promise<void>): Promise<T> {
  let journal: string | undefined;
  const cleanup = async () => {
    try {
      await inAuthPhase("cleanup", remove);
      forgetResource(journal);
    } catch (cause) {
      if (!externalSettings()) throw cause;
      throw new Error(`Cleanup failed for ${resource.kind} ${resource.id}.${journal ? ` See ${journal}.` : ""}`, { cause });
    }
  };

  let result: T;
  try {
    journal = recordResource(resource);
    result = await use();
  } catch (error) {
    try {
      await cleanup();
    } catch (cause) {
      throw new AggregateError([error, cause], error instanceof Error ? error.message : String(error), { cause });
    }
    throw error;
  }
  await cleanup();
  return result;
}
