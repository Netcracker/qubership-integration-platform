function isAllowedInId(c: string): boolean {
  return /[_0-9a-zA-Z]/.test(c);
}

/**
 * Turns a participant identifier into a token that both PlantUML and Mermaid
 * accept as an actor name. Participant identifiers often carry the display name
 * itself, so they may hold spaces, colons, or slashes, none of which the diagram
 * grammars allow.
 */
export function toDiagramIdentifier(text: string): string {
  return text
    .split("")
    .map((c) => (isAllowedInId(c) ? c : `_${c.charCodeAt(0)}_`))
    .join("");
}
