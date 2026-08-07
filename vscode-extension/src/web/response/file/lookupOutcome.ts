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
// `resolveFirstCandidate` is how a multi-candidate lookup runs, and `resolveScannedEntities` is how
// a scan of a folder listing that may hold both names of one entity runs. Neither takes an optional
// `onUnreadable` handler: the failure this module exists to stop is a `catch` that continues to a
// lower-precedence name, and the type system makes a caller say what that means.
//
// The two differ in one place, and only in what they can say. `resolveFirstCandidate` runs the
// candidates of one id, so it can report a file it could not read the moment nothing matched. A
// scan runs a folder listing: an entity whose only file is unreadable has no id to be listed under,
// because the id is in the file. So the scan drops it from the map and hands the unreadable files
// back with it, and a caller looking one id up turns that into `scanMissRefusal` — the miss stays a
// miss, and it names the files that could not be ruled out.

import type { Uri } from "vscode";
import { UnreadableFileError } from "../fileFilteringUtils";
import { extractFilename } from "./fileExtensions";
import type { CandidateOrder } from "./namePrecedence";

/** What the candidate names reported, in scan order. */
export type LookupFailures = {
  readonly causes: readonly unknown[];
  readonly unreadable: readonly Uri[];
};

/**
 * The third outcome, reported. Both shapes of it are this type, so an accessor that answers `null`
 * for a plain miss can rethrow the outcome without knowing which shape it holds.
 */
export class UnreadableOutcomeError extends Error {}

/**
 * A file the lookup would have answered with may be the sibling of one the scan could not read, so
 * the lookup refuses instead. See `refuseUnreadableSibling` for the scope of that refusal.
 */
export class UnreadableSiblingError extends UnreadableOutcomeError {
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
 * The order is a `CandidateOrder`, which only `namePrecedence.ts` produces: which of two names
 * outranks the other is stated once there, and a lookup cannot run an order written anywhere else.
 *
 * `onUnreadable` runs before a match is answered whenever an earlier candidate left a file the scan
 * could not read. `onNoMatch` builds the failure for a run that matched nothing; `noMatchError`
 * is the default body for it, and reports an unreadable file over a plain miss.
 */
export async function resolveFirstCandidate<T>(
  candidates: CandidateOrder,
  attempt: (candidate: string) => Promise<T>,
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

/**
 * Nothing the scan read carried the id, and it left files it could not read. What those files hold
 * is what would say whether one of them is the entity, so reporting a plain miss is the third
 * outcome collapsing: the caller goes on as if the entity were absent, and the file to fix is never
 * named.
 */
export class UnreadableCandidateError extends UnreadableOutcomeError {
  constructor(
    readonly entityId: string,
    readonly files: readonly Uri[],
    noun = "",
  ) {
    super(
      `Cannot resolve ${noun}${entityId}: no file carries that id, and ` +
        `${files.map((fileUri) => fileUri.path).join(", ")} could not be read.` +
        " Fix or delete those files — until then the id cannot be treated as absent.",
    );
    this.name = "UnreadableCandidateError";
  }
}

/**
 * What a folder scan has to report instead of a miss, or nothing when it read everything it listed.
 * A file the scan could not read outranks a plain miss for the reason `noMatchError` prefers it one
 * layer up: the id lives in the file, so a file nobody could read is a name that cannot be ruled
 * out. A caller that answers `null` or an empty list for a plain miss throws this first.
 *
 * What this accepts: one broken file in a service folder reports every miss in that folder as
 * unreadable rather than absent. That is a message, not a redirection — nothing is answered from
 * another file either way — and the alternative is trusting a file name to state the id inside it.
 */
export function scanMissRefusal(
  entityId: string,
  unreadable: readonly Uri[],
  noun = "",
): UnreadableCandidateError | undefined {
  return unreadable.length > 0
    ? new UnreadableCandidateError(entityId, unreadable, noun)
    : undefined;
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
 * Whether two uris address one place. A path alone does not say so: the same text under another
 * scheme or authority is another file entirely — `git:` names a revision of it, and a remote
 * authority names it on another machine — and a write lands in exactly one of those spaces.
 */
function sameFileSpace(candidate: Uri, resolved: Uri): boolean {
  return (
    candidate.scheme === resolved.scheme &&
    candidate.authority === resolved.authority &&
    candidate.query === resolved.query
  );
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
    directoryOf(candidate.path) === directoryOf(resolved.path) &&
    sameFileSpace(candidate, resolved)
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

/** A candidate file of the scan, parsed. */
export type ParsedFile<T> = {
  readonly fileName: string;
  readonly fileUri: Uri;
  readonly parsed: T;
};

/** The file an entity id resolves to, and the same-id files the precedence rule outranked. */
export type ResolvedEntity<T> = ParsedFile<T> & {
  readonly duplicates: readonly ParsedFile<T>[];
};

/** What a folder scan resolved: one entry per entity id, and the files it could not read. */
export type ScannedEntities<T> = {
  readonly byId: Map<string, ResolvedEntity<T>>;
  readonly unreadable: readonly Uri[];
};

/**
 * Runs a scan of a folder listing that may hold both names of one entity, and answers with one file
 * per entity id: the one under the current name.
 *
 * This is the listing counterpart of `resolveFirstCandidate`, and it exists for the same reason. A
 * scan that skipped the file it could not parse answered every one of those ids from the sibling
 * that lost the precedence race — the `.specification.` file behind an unreadable `.api.` one, the
 * `.specification-group.` file behind an unreadable `.api-group.` one — which is the pair a re-save
 * overwrites. So `onUnreadable` is required here too: it runs once per resolved entity, with the
 * files the scan could not read, and `refuseUnreadableSibling` is what a caller states in it.
 *
 * The files it could not read come back with the map, because an entity whose only file is one of
 * them is in neither: its id was in that file. A caller looking one id up reports them through
 * `scanMissRefusal` rather than calling the id absent.
 */
export async function resolveScannedEntities<T>(
  candidates: readonly Uri[],
  parse: (fileUri: Uri) => Promise<T>,
  handlers: {
    idOf: (parsed: T) => string | undefined;
    prefers: (fileUri: Uri) => boolean;
    onUnreadable: (
      entityId: string,
      resolved: Uri,
      unreadable: readonly Uri[],
    ) => void;
  },
): Promise<ScannedEntities<T>> {
  const unreadable: Uri[] = [];
  const byId = new Map<string, ParsedFile<T>[]>();

  for (const fileUri of candidates) {
    let parsed: T;
    try {
      parsed = await parse(fileUri);
    } catch (error) {
      console.error(`Failed to parse ${fileUri.path}`, error);
      unreadable.push(fileUri);
      continue;
    }
    const entityId = handlers.idOf(parsed);
    if (!entityId) {
      continue;
    }
    const files = byId.get(entityId) ?? [];
    files.push({ fileName: extractFilename(fileUri), fileUri, parsed });
    byId.set(entityId, files);
  }

  const resolved: ScannedEntities<T> = { byId: new Map(), unreadable };
  for (const [entityId, files] of byId) {
    const preferred = files.find((file) => handlers.prefers(file.fileUri));
    const winner = preferred ?? files[0];
    if (unreadable.length > 0) {
      handlers.onUnreadable(entityId, winner.fileUri, unreadable);
    }
    resolved.byId.set(entityId, {
      ...winner,
      duplicates: files.filter((file) => file !== winner),
    });
  }

  return resolved;
}
