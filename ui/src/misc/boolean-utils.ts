// The catalog stores element properties without checking their types, so a flag can arrive as "true" or "false".
export function parseBooleanFlag(
  value: unknown,
  defaultValue = false,
): boolean {
  if (value === undefined || value === null) {
    return defaultValue;
  }
  return typeof value === "string"
    ? value.toLowerCase() === "true"
    : value === true;
}
