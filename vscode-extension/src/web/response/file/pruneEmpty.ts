// Mirrors the backend's export inclusion, and the schemas need it: `protocol`
// is optional but declared as an enum with no empty member, so a blank one
// fails validation where an absent key passes. A blank string, an empty list,
// or an empty object carries no information as an entity field either, and
// writing one back on every save produces diff noise the exported files do not
// have.
//
// The backend splits this in two: value inclusion NON_EMPTY for entity fields,
// content inclusion NON_NULL for what sits inside a free-form map. Keep both
// halves — inside such a map a blank string is a value the caller chose, and
// EnvironmentDefaultProperties seeds several of them for kafka and amqp.

/** Keys whose value is a free-form map rather than a set of entity fields. */
const FREE_FORM_KEYS: ReadonlySet<string> = new Set(["properties"]);

function isPlainObject(value: unknown): value is Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    return false;
  }
  const prototype = Object.getPrototypeOf(value);
  return prototype === Object.prototype || prototype === null;
}

/** `false` and `0` are values, so only blanks and empty containers drop out. */
function isPresent(value: unknown): boolean {
  if (value === null || value === undefined || value === "") {
    return false;
  }
  if (Array.isArray(value)) {
    return value.length > 0;
  }
  if (isPlainObject(value)) {
    return Object.keys(value).length > 0;
  }
  return true;
}

/** Content inclusion: only a null or missing entry drops out. */
function pruneNulls<T>(value: T): T {
  if (Array.isArray(value)) {
    return value
      .map(pruneNulls)
      .filter((entry) => entry !== null && entry !== undefined) as unknown as T;
  }
  if (isPlainObject(value)) {
    const result: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value)) {
      if (entry !== null && entry !== undefined) {
        result[key] = pruneNulls(entry);
      }
    }
    return result as unknown as T;
  }
  return value;
}

export function pruneEmpty<T>(value: T): T {
  if (Array.isArray(value)) {
    return value.map(pruneEmpty).filter(isPresent) as unknown as T;
  }
  if (isPlainObject(value)) {
    const result: Record<string, unknown> = {};
    for (const [key, entry] of Object.entries(value)) {
      const pruned = FREE_FORM_KEYS.has(key)
        ? pruneNulls(entry)
        : pruneEmpty(entry);
      // The map itself is still an entity field, so an empty one drops out.
      if (isPresent(pruned)) {
        result[key] = pruned;
      }
    }
    return result as unknown as T;
  }
  return value;
}

/**
 * Prunes a top-level entity, keeping `content` even when everything inside it
 * dropped out — the schemas require the key.
 */
export function pruneEntity<T>(entity: T): T {
  const pruned = pruneEmpty(entity);
  if (
    isPlainObject(entity) &&
    "content" in entity &&
    isPlainObject(pruned) &&
    !("content" in pruned)
  ) {
    (pruned as Record<string, unknown>).content = {};
  }
  return pruned;
}
