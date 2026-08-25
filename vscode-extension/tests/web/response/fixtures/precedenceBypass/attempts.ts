// Fixtures, not production code. Each case below states a scan order `namePrecedence.ts` did not
// declare, and the `@ts-expect-error` above it is the assertion: this file compiles only while
// every one of them is rejected. The guard compiles it and fails on an unused directive, which is
// what a bypass that started working looks like.

import { fileApi } from "../../../../../src/web/response/file/fileApiProvider";
import { getExtensionsForFile } from "../../../../../src/web/response/file/fileExtensions";
import {
  noMatchError,
  resolveFirstCandidate,
} from "../../../../../src/web/response/file/lookupOutcome";
import {
  candidateExtensions,
  currentExtension,
  DeclaredNameSet,
  PairedNames,
  SERVICE_NAMES,
} from "../../../../../src/web/response/file/namePrecedence";

const ext = getExtensionsForFile();

// A name set of one's own, with the two generations the wrong way round. A name set only comes
// from `NAME_SETS`, so this never reaches `candidateExtensions`.
// @ts-expect-error a hand-made name set is not a declared one
const reversed: DeclaredNameSet<"api" | "specification"> = {
  current: ["specification"],
  legacy: ["api"],
};

export const reversedOrder = candidateExtensions(reversed, ext);

// A "pair" naming the same extension on both sides, which states no rename at all.
// @ts-expect-error the legacy name of a pair cannot be its current name
export const samePair: PairedNames<"api", "api"> = {
  current: ["api"],
  legacy: ["api"],
};

// A service has five current names, so it is no pair and has no single current extension.
// @ts-expect-error a service picks its name by type, not by precedence
export const serviceCurrent = currentExtension(SERVICE_NAMES, ext);

/** An order written out by hand, which is the shape the original bug had. */
export async function literalOrder(modelId: string): Promise<unknown> {
  return await resolveFirstCandidate(
    // @ts-expect-error a lookup runs a declared order and nothing else
    [ext.specification, ext.api],
    (extension) => fileApi.findFileById(modelId, extension),
    {
      onUnreadable: () => undefined,
      onNoMatch: (failures) => noMatchError(failures, () => new Error("miss")),
    },
  );
}

/** The same order assembled so that no array literal states it — what the source rule cannot see. */
export async function assembledOrder(modelId: string): Promise<unknown> {
  const order = [ext.specification];
  order.push(ext.api);

  return await resolveFirstCandidate(
    // @ts-expect-error assembling the array elsewhere does not make it a declared order
    order,
    (extension) => fileApi.findFileById(modelId, extension),
    {
      onUnreadable: () => undefined,
      onNoMatch: (failures) => noMatchError(failures, () => new Error("miss")),
    },
  );
}

/** A declared order run backwards: transforming one yields a plain array, which no lookup takes. */
export async function reversedDeclaredOrder(modelId: string): Promise<unknown> {
  const order = [...candidateExtensions(reversed, ext)].reverse();

  return await resolveFirstCandidate(
    // @ts-expect-error a copy of a declared order is not one
    order,
    (extension) => fileApi.findFileById(modelId, extension),
    {
      onUnreadable: () => undefined,
      onNoMatch: (failures) => noMatchError(failures, () => new Error("miss")),
    },
  );
}
