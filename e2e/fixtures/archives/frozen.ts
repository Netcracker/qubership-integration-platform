/**
 * The frozen legacy corpus: one document per migration floor, and the facts it is checked against.
 *
 * Compatibility is versioned by what a document *declares*, not by a release date. A chain document
 * carries `migrations: "[100, 101]"` — the list of migrations already applied to it — and the
 * importer applies whatever the current list holds that the document does not
 * (`FileMigrationService.migrate`). So one document per distinct declared list is one archive per
 * compatibility floor, and freezing it is the only way to keep asserting that an old export still
 * imports. Regenerating the corpus from today's exporter would turn it into a copy of today's
 * exporter and prove nothing.
 *
 * **Where these come from.** `schemas/src/test/resources/samples/`, where 44 documents declare a
 * `migrations:` envelope and they are the only ones in the repository that do. Measured over the
 * 41 chain samples: `[100, 101]` 37x, `[100, 101, 108]` 2x, `[100, 101, 102, 103]` 1x, and one
 * document carrying `[1..30, 100..105]`. `[100, 101, 102]` appears on **no chain document**; it
 * comes from a context-service and an MCP sample.
 *
 * **The samples are not archives.** They are schema-validation fixtures, so a zipped sample imports
 * nothing. Each floor is stored here as a fixture directory in the shape
 * `fixtures/templating.ts` already assembles: one document, zipped into
 * `chains/<id>/<id>.chain.cip.yaml` or `services/<id>/<id>.service.cip.yaml` at import time. A
 * directory of text rather than a committed `.zip` on purpose: a checksum guard whose subject is a
 * binary blob puts nothing in front of a reviewer, and the rule is that changing a frozen
 * file means changing its checksum **in the same commit**, for a person to look at.
 *
 * **What was changed from the sample, and why.** Only the fields that name things on a shared
 * stack: the document's own `name`, and the folder or group it lands in. Both carry `{{RUN}}`, so
 * the teardown sweep in `support/fixtures.ts` finds them by the run token and a crashed run leaves
 * nothing behind. Ids are **not** templated — a chain id must be a UUID, and it is the id that
 * makes a re-import an update rather than a create. Nothing under `content.elements` is touched,
 * because that is the half the migrations rewrite.
 *
 * **Prerequisites.** Two kinds of sample cannot be frozen as they stand: several declare a
 * `propertiesFilename` pointing at a `.groovy` or `.json` file that exists nowhere under
 * `samples/`, and several reference an `integrationSystemId` — `chain/service-call.yaml` names one
 * that has a sample under `samples/service/`, `chain/async-api-trigger.yaml` names one that has
 * none. The documents chosen below need neither, so each archive stands alone.
 *
 * **Three independent lists.** Chain migrations run `V100`-`V108`, service migrations `V100`-`V102`,
 * MCP migrations `V100` only, all under
 * `integration-build-pipeline/src/main/java/.../io/readers/migrations/`. "The current list" is
 * therefore ambiguous, and each document kind needs its own floors, which is why
 * `currentMigrationVersions` takes the kind.
 */
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "js-yaml";
import type { DocumentKind } from "../templating.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** One directory per floor, each holding a single document. */
export const FROZEN_ARCHIVE_DIR = path.join(HERE, "frozen");

/** The manifest the guard compares the tree against. */
export const CHECKSUM_FILE = path.join(FROZEN_ARCHIVE_DIR, "CHECKSUMS");

/** The samples every frozen document was cut from, and the floor distribution is measured over. */
export const SAMPLE_DIR = path.resolve(HERE, "../../../schemas/src/test/resources/samples");

/** Where the three migration lists live, as classes named `V<version>…ImportFileMigration.java`. */
export const MIGRATION_SOURCE_DIR = path.resolve(
  HERE,
  "../../../integration-build-pipeline/src/main/java/org/qubership/integration/platform/io/readers/migrations",
);

/**
 * A document kind's migration list, as the sources spell it.
 *
 * `system` rather than `service` because that is the package name; the document kind the exporter
 * writes is `service`, and `MIGRATION_PACKAGES` is the translation.
 */
const MIGRATION_PACKAGES = {
  chain: "chain",
  service: "system",
  mcp: "mcp",
} as const;

export type MigrationFamily = keyof typeof MIGRATION_PACKAGES;

export interface FrozenArchive {
  /** The directory under `frozen/`, and the name a spec reports. */
  name: string;
  /** How the archive is assembled and which import endpoint reads it. */
  kind: DocumentKind;
  /** The sample this was cut from, relative to `schemas/src/test/resources/samples/`. */
  source: string;
  /** The `migrations:` list the document declares: its compatibility floor. */
  floor: number[];
  /**
   * Whether the current platform can import it at all.
   *
   * `false` is not a gap: `FileMigrationService.migrate` refuses a document declaring a version the
   * current list does not hold, because that document came from a newer platform. The one archive
   * marked `false` is the frozen proof that the refusal still happens and still says so.
   */
  supported: boolean;
}

/**
 * The corpus, one entry per distinct floor the samples carry.
 *
 * `chain-100-101-108` has a single candidate rather than the two the distribution counts, because
 * `chain/group__SHOULD_FAIL.yaml` is a deliberately invalid fixture and is excluded. The
 * distribution and the exclusion are therefore not independent facts: if `chain/group.yaml` ever
 * became unusable too, that floor would need a hand-built document.
 */
export const FROZEN_ARCHIVES: FrozenArchive[] = [
  {
    // The oldest floor available, and the majority of the corpus it was cut from. Compatibility is
    // versioned by the migrations a document declares, and by that measure 37 of the 41 chain
    // samples sit here.
    name: "chain-100-101",
    kind: "chain",
    source: "chain/header-modification.yaml",
    floor: [100, 101],
    supported: true,
  },
  {
    name: "chain-100-101-102-103",
    kind: "chain",
    source: "chain/context-storage.yaml",
    floor: [100, 101, 102, 103],
    supported: true,
  },
  {
    // Not `group__SHOULD_FAIL.yaml`, the other document at this floor.
    name: "chain-100-101-108",
    kind: "chain",
    source: "chain/group.yaml",
    floor: [100, 101, 108],
    supported: true,
  },
  {
    // The **broadest** declared list, and therefore the *highest* applied floor rather than the
    // oldest — a document that has already been through everything the platform can do to it. It
    // is also the only sample the current platform refuses: `[1..30]` are versions no chain
    // migration in the tree declares, so the importer reads it as an export from a newer version.
    name: "chain-1-30-100-105",
    kind: "chain",
    source: "chain/deprecated-routing-elemets.yaml",
    floor: [...Array.from({ length: 30 }, (_, index) => index + 1), 100, 101, 102, 103, 104, 105],
    supported: false,
  },
  {
    // Services are their own list and their own import endpoint, so a chain floor says nothing
    // about them. One sample, one floor.
    name: "service-100-101",
    kind: "service",
    source: "service/service-2d73a4b6-6499-47d5-864f-0c0f86654d90.yaml",
    floor: [100, 101],
    supported: true,
  },
];

/** Every tracked file under `frozen/`, relative and POSIX, `CHECKSUMS` itself excluded. */
export function frozenFiles(dir = FROZEN_ARCHIVE_DIR): string[] {
  return fs
    .readdirSync(dir, { withFileTypes: true, recursive: true })
    .filter((entry) => entry.isFile())
    .map((entry) => path.relative(dir, path.join(entry.parentPath, entry.name)))
    .map((rel) => rel.split(path.sep).join("/"))
    .filter((rel) => rel !== "CHECKSUMS")
    .sort();
}

/** The digest of one frozen file, over its bytes rather than its parsed form. */
function digestOf(rel: string, dir = FROZEN_ARCHIVE_DIR): string {
  return crypto.createHash("sha256").update(fs.readFileSync(path.join(dir, rel))).digest("hex");
}

/** What the tree says it is right now: the reading the manifest is compared against. */
export function computeChecksums(dir = FROZEN_ARCHIVE_DIR): Map<string, string> {
  return new Map(frozenFiles(dir).map((rel) => [rel, digestOf(rel, dir)]));
}

/** The manifest as committed. Format is `<sha256>  <path>`, the shape `sha256sum` writes. */
export function readChecksums(file = CHECKSUM_FILE): Map<string, string> {
  const entries = new Map<string, string>();
  for (const line of fs.readFileSync(file, "utf-8").split("\n")) {
    const trimmed = line.trim();
    if (trimmed.length === 0 || trimmed.startsWith("#")) continue;
    const match = /^([0-9a-f]{64})\s+(.+)$/.exec(trimmed);
    if (!match) throw new Error(`${file}: line is not "<sha256>  <path>": ${JSON.stringify(line)}`);
    entries.set(match[2], match[1]);
  }
  return entries;
}

/**
 * The manifest against the tree, as the problems found. Empty means the two agree.
 *
 * Both directions, because only one of the two is caught by comparing digests: a file added without
 * an entry is as much a hole in the guard as an entry whose file is gone.
 *
 * It is a function rather than a few assertions inside the spec so that the mutation case can run
 * the same comparison the guard runs. A comparison written inline is one the mutation case cannot
 * invoke, and what it proves instead is that a hand-written digest differs from a real one — which
 * stays true after the guard is deleted.
 */
export function checksumProblems(
  manifest: Map<string, string>,
  actual: Map<string, string>,
): string[] {
  const problems: string[] = [];

  for (const [rel, hash] of actual) {
    const declared = manifest.get(rel);
    if (declared === undefined) {
      problems.push(`${rel}: is in the tree with no entry in the manifest`);
    } else if (declared !== hash) {
      problems.push(`${rel}: changed without its checksum changing in the same commit`);
    }
  }

  for (const rel of manifest.keys()) {
    if (!actual.has(rel)) problems.push(`${rel}: is in the manifest with no file behind it`);
  }

  return problems.sort();
}

/** Renders the manifest from the tree. `npm run frozen-checksums` writes it; a reviewer reads it. */
export function renderChecksums(dir = FROZEN_ARCHIVE_DIR): string {
  return `${[...computeChecksums(dir)].map(([rel, hash]) => `${hash}  ${rel}`).join("\n")}\n`;
}

/** The one document a frozen archive directory holds, as text. */
export function frozenDocumentPath(archive: FrozenArchive, dir = FROZEN_ARCHIVE_DIR): string {
  const inside = fs.readdirSync(path.join(dir, archive.name)).filter((name) => name.endsWith(".yaml"));
  if (inside.length !== 1) {
    throw new Error(`frozen ${archive.name}: expected one document, found ${inside.length}`);
  }
  return path.join(dir, archive.name, inside[0]);
}

/**
 * The `migrations:` list a document declares, as numbers.
 *
 * The envelope is a **string** holding a bracketed list, not a YAML sequence, and one sample wraps
 * it across two lines. Parsing goes through the YAML loader for that reason rather than over the
 * raw text.
 */
export function declaredFloor(document: unknown): number[] {
  const content = (document as { content?: { migrations?: unknown } })?.content;
  const declared = content?.migrations;
  if (typeof declared !== "string") return [];
  return declared
    .replace(/[[\]]/g, "")
    .split(",")
    .map((each) => each.trim())
    .filter((each) => each.length > 0)
    .map(Number);
}

/** The floor a document on disk declares. */
export function floorOfFile(file: string): number[] {
  return declaredFloor(yaml.load(fs.readFileSync(file, "utf-8")));
}

/** A floor as a comparable key, so a set of floors can be compared without deep equality. */
export function floorKey(floor: number[]): string {
  return `[${[...floor].sort((a, b) => a - b).join(", ")}]`;
}

/**
 * The migration versions the platform currently ships for one document kind, read off the class
 * names rather than off a running stack, so the check costs no stack time.
 */
export function currentMigrationVersions(
  family: MigrationFamily,
  dir = MIGRATION_SOURCE_DIR,
): number[] {
  return fs
    .readdirSync(path.join(dir, MIGRATION_PACKAGES[family]))
    .map((name) => /^V(\d+)\w*ImportFileMigration\.java$/.exec(name)?.[1])
    .filter((version): version is string => version !== undefined)
    .map(Number)
    .sort((a, b) => a - b);
}

/**
 * Every floor the sample corpus declares, keyed by floor and counted.
 *
 * `__SHOULD_FAIL` documents are excluded: they are deliberately invalid fixtures, and one of them
 * is a document at the `[100, 101, 108]` floor.
 */
export function sampleFloors(subdirectory: string, dir = SAMPLE_DIR): Map<string, number> {
  const counts = new Map<string, number>();
  const root = path.join(dir, subdirectory);
  for (const entry of fs.readdirSync(root, { withFileTypes: true })) {
    if (!entry.isFile() || !entry.name.endsWith(".yaml")) continue;
    if (entry.name.includes("__SHOULD_FAIL")) continue;
    const floor = floorOfFile(path.join(root, entry.name));
    if (floor.length === 0) continue;
    const key = floorKey(floor);
    counts.set(key, (counts.get(key) ?? 0) + 1);
  }
  return counts;
}
