import { PLACEHOLDER } from "../../misc/format-utils.ts";

/**
 * Response status and delay as text. `formatOptional` would hide a delay of
 * zero, which is the value a mock is created with.
 */
export function formatMockNumber(value: number | null | undefined): string {
  return typeof value === "number" ? String(value) : PLACEHOLDER;
}
