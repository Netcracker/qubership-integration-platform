import { PLACEHOLDER } from "../../misc/format-utils.ts";

/**
 * Response status and delay as text. `formatOptional` would hide a delay of
 * zero, which is the value a mock is created with.
 */
export function formatMockNumber(value: number | null | undefined): string {
  return typeof value === "number" ? String(value) : PLACEHOLDER;
}

/**
 * The range the service answers within. Outside it the status line is not one an
 * HTTP client can read, so the save is refused.
 */
const MIN_RESPONSE_STATUS = 100;
const MAX_RESPONSE_STATUS = 599;

/**
 * Whether the mock can answer with the status. Zero is a mock that never named
 * one: it answers 200, and the service stores it as it stands.
 */
export function isAnswerableResponseStatus(status: number): boolean {
  return (
    status === 0 ||
    (Number.isInteger(status) &&
      status >= MIN_RESPONSE_STATUS &&
      status <= MAX_RESPONSE_STATUS)
  );
}

/** Message naming what is wrong with a response status, or undefined when it holds. */
export function getResponseStatusError(status: number): string | undefined {
  return isAnswerableResponseStatus(status)
    ? undefined
    : `A response status is a whole number between ${MIN_RESPONSE_STATUS} and ${MAX_RESPONSE_STATUS}.`;
}
