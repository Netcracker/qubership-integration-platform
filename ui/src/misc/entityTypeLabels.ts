import { EntityType } from "../api/apiTypes.ts";
import { capitalize, formatSnakeCased } from "./format-utils.ts";

// `capitalize` lowercases everything after the first letter, so an acronym renders as "Api group" without an
// override. Values whose default formatting reads wrong are listed here.
const ENTITY_TYPE_LABELS: Partial<Record<EntityType, string>> = {
  [EntityType.API_GROUP]: "API group",
};

export function formatEntityType(value: EntityType): string {
  return ENTITY_TYPE_LABELS[value] ?? formatSnakeCased(capitalize(value));
}
