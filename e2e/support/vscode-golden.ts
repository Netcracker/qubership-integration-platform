import { readFileSync } from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const GOLDEN_DIR = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../fixtures/vscode/goldens");
const UUID = /\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\b/g;

/** The text of a golden file under `fixtures/vscode/goldens/`. */
export function readGolden(name: string): string {
  return readFileSync(path.join(GOLDEN_DIR, name), "utf8");
}

/**
 * Replaces every UUID that `text` holds and the fixture file did not with `<new-id-N>`, numbered by
 * first appearance, so a golden can pin a file that carries ids the extension generated.
 */
export function normalizeIds(text: string, original: string): string {
  const known = new Set(original.match(UUID) ?? []);
  const generated = new Map<string, string>();
  return text.replace(UUID, (id) => {
    if (known.has(id)) return id;
    if (!generated.has(id)) generated.set(id, `<new-id-${generated.size + 1}>`);
    return generated.get(id)!;
  });
}
