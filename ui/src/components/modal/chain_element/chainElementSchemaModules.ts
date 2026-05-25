import { schemasByType } from "@netcracker/qip-schemas";

/** Wraps the schemas-package re-export so tests can `jest.mock` this file. */
export function getSchemaModules(): Readonly<Record<string, string>> {
  // eslint-disable-next-line @typescript-eslint/no-unsafe-return
  return schemasByType;
}
