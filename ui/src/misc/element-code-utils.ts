import type { editor } from "monaco-editor";
import type { Element } from "../api/apiTypes";

export type ElementBlockRange = {
  startLine: number;
  endLine: number;
};

/**
 * Block end is the line before the next top-level `- id:` marker or the
 * last line of the document. Line numbers are 1-based, inclusive.
 */
export const findElementBlockRanges = (
  model: editor.ITextModel,
  ids: readonly string[],
): Map<string, ElementBlockRange> => {
  const result = new Map<string, ElementBlockRange>();
  if (ids.length === 0) return result;

  const targets = new Set(ids);
  const matches = model.findMatches(
    `^- id: ["']?([^"'\\r\\n]+)["']?\\s*$`,
    true,
    true,
    true,
    null,
    true,
  );

  const markers: { line: number; id: string }[] = [];
  for (const match of matches) {
    const id = match.matches?.[1];
    if (id === undefined) continue;
    markers.push({ line: match.range.startLineNumber, id });
  }
  if (markers.length === 0) return result;

  const lastLine = model.getLineCount();
  for (let i = 0; i < markers.length; i++) {
    const { line, id } = markers[i];
    if (!targets.has(id)) continue;
    const nextLine = markers[i + 1]?.line ?? lastLine + 1;
    result.set(id, { startLine: line, endLine: nextLine - 1 });
  }

  return result;
};

/** Cache key for code-view refetch: reflects id set, order, and modifiedWhen. */
export const buildElementsSignature = (elements: readonly Element[]): string =>
  elements.map((e) => `${e.id}:${e.modifiedWhen ?? ""}`).join("|");
