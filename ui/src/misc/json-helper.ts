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
