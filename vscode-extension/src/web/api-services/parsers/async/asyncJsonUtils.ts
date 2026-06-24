/** Shared helpers for the AsyncAPI parser/resolver/normalizer modules. */

export function isObject(value: unknown): value is Record<string, any> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

export function deepClone<T>(value: T): T {
  if (value === undefined || value === null) {
    return value;
  }
  return JSON.parse(JSON.stringify(value));
}
