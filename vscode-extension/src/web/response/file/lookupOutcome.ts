// The contract every file lookup in this extension obeys, and the one place it may be bent.
//
// A lookup by id has three outcomes, not two:
//
//   1. a match: the file, answered as a `Uri`.
//   2. an absence: nothing carries the id and every candidate was readable, reported as a plain
//      `Error` or a typed not-found.
//   3. an unreadable file: no match, but at least one candidate could not be parsed, so its name
//      cannot be ruled out. `collectFiles` records those files, `findFile` reports them as
//      `UnreadableFileError`, and every layer above carries that error on.
//
// Narrowing the third outcome, by answering with some other file or by reporting a plain miss, is
// what hands a lookup to a stale sibling; the next write then puts that sibling's superseded body
// over the file nobody could read. So it happens in exactly one rule, `refuseUnreadableSibling`
// below: a lookup may answer with another name only when that name cannot be the unreadable file's
// sibling. `findServiceFileById` may additionally report a total miss as a miss, because with
// nothing resolved there is no sibling for a write to land beside; it names the unreadable files
// among its causes.
//
// `resolveFirstCandidate` is how a multi-candidate lookup runs. Its `onUnreadable` handler is not
// optional on purpose: the failure this module exists to stop is a `catch` that continues to a
// lower-precedence name, and the type system now makes a caller say what that means.

import type { Uri } from "vscode";
import { UnreadableFileError } from "../fileFilteringUtils";
import { extractFilename } from "./fileExtensions";

/** What the candidate names reported, in scan order. */
export type LookupFailures = {
  readonly causes: readonly unknown[];
  readonly unreadable: readonly Uri[];
};

/**
 * A file the lookup would have answered with may be the sibling of one the scan could not read, so
 * the lookup refuses instead. See `refuseUnreadableSibling` for the scope of that refusal.
 */
export class UnreadableSiblingError extends Error {
  constructor(
    readonly entityId: string,
    readonly fileUri: Uri,
    noun = "",
  ) {
    super(
      `Cannot resolve ${noun}${entityId}: ${fileUri.path} could not be read.` +
        " Fix or delete that file — until then a save would overwrite it with another file's content.",
    );
    this.name = "UnreadableSiblingError";
  }
}

/**
 * Runs one lookup per candidate name and answers with the first match.
 *
 * `onUnreadable` runs before a match is answered whenever an earlier candidate left a file the scan
 * could not read. `onNoMatch` builds the failure for a run that matched nothing; `noMatchError`
 * is the default body for it, and reports an unreadable file over a plain miss.
 */
export async function resolveFirstCandidate<C, T>(
  candidates: readonly C[],
  attempt: (candidate: C) => Promise<T>,
  handlers: {
    onUnreadable: (unreadable: readonly Uri[], resolved: T) => void;
    onNoMatch: (failures: LookupFailures) => Error;
  },
): Promise<T> {
  const causes: unknown[] = [];
  const unreadable: Uri[] = [];

  for (const candidate of candidates) {
    let resolved: T;
    try {
      resolved = await attempt(candidate);
    } catch (error) {
      causes.push(error);
      if (error instanceof UnreadableFileError) {
        unreadable.push(...error.files);
      }
      continue;
    }
    if (unreadable.length > 0) {
      handlers.onUnreadable(unreadable, resolved);
    }
    return resolved;
  }

  throw handlers.onNoMatch({ causes, unreadable });
}

/**
 * The failure to report when no candidate matched. A file the scan could not read outranks a plain
 * miss: that name still cannot be ruled out, and saying "not found" is what sends a caller looking
 * somewhere else. `absent` builds the miss for a run in which every candidate was readable.
 */
export function noMatchError(
  failures: LookupFailures,
  absent: () => Error,
): Error {
  const unreadable = failures.causes.find(
    (cause) => cause instanceof UnreadableFileError,
  );
  return unreadable instanceof Error ? unreadable : absent();
}

function directoryOf(filePath: string): string {
  return filePath.slice(0, filePath.lastIndexOf("/"));
}

/** The name with its entity extension stripped, or nothing when no extension matches. */
function baseOf(
  fileUri: Uri,
  extensions: readonly string[],
): string | undefined {
  const name = extractFilename(fileUri);
  const extension = [...extensions]
    .sort((a, b) => b.length - a.length)
    .find((candidate) => name.endsWith(candidate));
  return extension ? name.slice(0, -extension.length) || undefined : undefined;
}

/**
 * Whether two names can be the two files of one entity. A conversion changes the extension alone,
 * so a pair shares a folder and the name its extension is stripped from — and that is also the only
 * pair a write can destroy, because a write recomputes the target name in the folder of the file
 * the lookup resolved.
 */
export function mayBeSameEntity(
  candidate: Uri,
  resolved: Uri,
  extensions: readonly string[],
): boolean {
  const candidateBase = baseOf(candidate, extensions);
  const resolvedBase = baseOf(resolved, extensions);
  return (
    candidateBase !== undefined &&
    candidateBase === resolvedBase &&
    directoryOf(candidate.path) === directoryOf(resolved.path)
  );
}

/**
 * The single rule that decides whether a lookup may answer while a file it could not read is still
 * outstanding. It may, unless that file could be the sibling of the one it resolved — same folder,
 * same name under another extension, which is what a half-finished conversion leaves behind and the
 * only pair a write can overwrite.
 *
 * Refusing for *any* unreadable file would guarantee the same invariant, and one broken file would
 * then make every entity not stored under that name unresolvable — a one-file problem turned into a
 * workspace-wide outage.
 */
export function refuseUnreadableSibling(
  entityId: string,
  resolved: Uri,
  unreadable: readonly Uri[],
  extensions: readonly string[],
  makeError: (entityId: string, fileUri: Uri) => Error = (id, fileUri) =>
    new UnreadableSiblingError(id, fileUri),
): void {
  const sibling = unreadable.find((candidate) =>
    mayBeSameEntity(candidate, resolved, extensions),
  );
  if (sibling) {
    throw makeError(entityId, sibling);
  }
}
