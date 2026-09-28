/**
 * A JSON state file one process writes and a later project or a later run reads back: the corpus
 * files, the run manifest, the resource record, the override record, and the UI server's pid file.
 */
import fs from "node:fs";
import path from "node:path";

/** Writes `data` through a temporary file and a rename, atomic on one filesystem. */
export function writeStateFile<T>(file: string, data: T): void {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  const staging = `${file}.tmp`;
  fs.writeFileSync(staging, `${JSON.stringify(data, null, 2)}\n`);
  fs.renameSync(staging, file);
}

/**
 * `file`, or `null` for one that is absent or unreadable.
 *
 * A truncated file is treated as no file: it is residue of its own — a `kill -9` mid-write past the
 * staging-file rename `writeStateFile` relies on — and refusing to parse it would throw a raw JSON
 * error instead of the caller's own message naming the actual cause.
 */
export function readStateFile<T>(file: string): T | null {
  if (!fs.existsSync(file)) return null;
  try {
    return JSON.parse(fs.readFileSync(file, "utf-8")) as T;
  } catch {
    return null;
  }
}
