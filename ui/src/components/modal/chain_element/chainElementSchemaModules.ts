import { schemasByType, type SchemaType } from "@netcracker/qip-schemas";

/** Wraps the schemas-package re-export so tests can `jest.mock` this file. */
export function getSchemaModules(): Readonly<Record<SchemaType, string>> {
  return schemasByType;
}
