export function parseJson<T>(text: string, guard?: (v: unknown) => v is T): T {
  const result = JSON.parse(text) as unknown;
  if (guard) {
    if (guard(result)) {
      return result;
    } else {
      throw new TypeError("Object type mismatch");
    }
  }
  return result as T;
}

export function parseJsonOrDefault<T>(text: string, defaultValue: T): T {
  try {
    return JSON.parse(text) as T;
  } catch (error) {
    console.error(`Unable to parse JSON: ${text}`, error);
    return defaultValue;
  }
}
