export type SearchField = string | number | boolean | null | undefined;

export function normalizeSearchTerm(term: string): string {
  return term.trim().toLowerCase();
}

export function makeSearchHaystack(fields: SearchField[]): string {
  return fields
    .filter((value) => value != null && value !== "")
    .map(String)
    .join(" ")
    .toLowerCase();
}

export function matchesByFields(term: string, fields: SearchField[]): boolean {
  const normalizedTerm = normalizeSearchTerm(term);
  if (!normalizedTerm) return true;
  return makeSearchHaystack(fields).includes(normalizedTerm);
}
