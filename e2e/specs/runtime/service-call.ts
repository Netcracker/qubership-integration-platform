/**
 * What the service-call specs share: the body a mock answers, and the seeded chain of one axis value.
 *
 * Not a spec: Playwright's default `testMatch` does not collect it.
 */
import { readCorpusState, seedChain, type SeedChain } from "../../support/corpus.js";
import { axisFixtureName } from "../../fixtures/axis-generator.js";
import type { SchemaValue } from "../../registry/discriminators.js";

/** What an http mock answers. */
export const MOCKED_BODY = '{"mocked":"http"}';

/** The seeded chain of one `service-call` axis value, the name of its service call, and the call's id. */
export interface AxisChain {
  chain: SeedChain;
  branch: string;
  callerId: string;
}

/** The seeded chain of one axis value; a mock is keyed on `callerId`. */
export function axisChain(axisPath: string, value: SchemaValue): AxisChain {
  const chain = seedChain(readCorpusState(), axisFixtureName({ family: "service-call", axisPath, value }));
  const branch = `${axisPath}=${value}`;
  return { chain, branch, callerId: chain.elements[branch] };
}
