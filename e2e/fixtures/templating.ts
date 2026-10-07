/**
 * Fixture trees on disk, rendered with the run token and assembled into the archive the importer
 * expects.
 *
 * Two runs against one stack collide on anything the platform treats as unique — an HTTP trigger
 * `contextPath` is a Camel route, and a second route on the same path is a deployment failure
 * rather than a test failure. So a fixture is a template: it carries `{{RUN}}` wherever a value has
 * to be unique per run, and nothing reads a fixture off disk without substituting.
 *
 * The archive layout is measured rather than assumed. `ExportService.zipChainFiles` writes every
 * entry under `chains/`, `getChainDirectory` is the chain id, and an export off the local stack
 * confirms `chains/<id>/<id>.chain.cip.yaml`. Services use `services/` and `.service.cip.yaml` the
 * same way, which `concurrent-service-import.spec.ts` already asserts against a live export.
 */
import crypto from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import yaml from "js-yaml";
import { UUID_TEXT } from "../support/absent.js";
import { zipOf } from "../support/zip.js";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** The one placeholder. A fixture carrying any other `{{…}}` is a typo, and assembly says so. */
export const RUN_PLACEHOLDER = "{{RUN}}";

const PLACEHOLDER_PATTERN = /\{\{\s*[A-Za-z0-9_]+\s*\}\}/g;

/** `e2e/fixtures/chains/` — one directory per fixture chain. */
export const CHAIN_FIXTURE_DIR = path.join(HERE, "chains");

/**
 * `e2e/fixtures/axes/` — the axis generator's output, one directory per generated chain.
 *
 * Gitignored and rewritten whole by `writeAxisFixtures` before the seed assembles, so it never
 * holds a chain the declarations no longer name.
 */
export const AXIS_FIXTURE_DIR = path.join(HERE, "axes");

/**
 * `e2e/fixtures/script/` — the Script corpus, one chain document per **file**.
 *
 * A flat file per chain rather than a directory per chain, because none of them carries a
 * companion — the script travels inline, and the separate `.groovy` file is something the
 * **exporter** produces, never something a fixture ships.
 *
 * Part of the corpus, and seeded by nothing of its own. The trace assertions these chains exist
 * for need `POST /v1/chains/{id}/properties/logging` between the import and the deploy — measured,
 * at the `OFF` default ten invocations across four chains recorded zero sessions — and that step
 * is already `seedCorpus`'s. A second import path would either repeat it or forget it. A fixture
 * whose spec creates and destroys it is named in `SPEC_OWNED_FIXTURES` instead, and the seed then
 * leaves it alone.
 */
export const SCRIPT_FIXTURE_DIR = path.join(HERE, "script");

/**
 * `e2e/fixtures/brokers/` — one directory per broker fixture chain, imported only by the
 * `brokers-seed` project, never by the shared corpus.
 *
 * Defined here, not in `support/brokers.ts`, so `TRACKED_FIXTURE_DIRS` below can include it without
 * an import cycle back into the module that already imports this one.
 */
export const BROKERS_FIXTURE_DIR = path.join(HERE, "brokers");

/** Every directory the seed's corpus is assembled from. A fixture name is unique across them. */
export const CORPUS_FIXTURE_DIRS: readonly string[] = [
  CHAIN_FIXTURE_DIR,
  AXIS_FIXTURE_DIR,
  SCRIPT_FIXTURE_DIR,
];

/**
 * The fixture directories this repository tracks, which is what the `schema` project validates.
 *
 * `AXIS_FIXTURE_DIR` is generated and gitignored, so it is absent in a fresh clone and in CI; the
 * rest are always there, and a fixture that does not validate breaks the seed minutes into a run.
 * `BROKERS_FIXTURE_DIR` is outside `CORPUS_FIXTURE_DIRS` because the shared corpus never imports
 * it, but it is tracked and rendered the same way every other fixture is, so it validates here too.
 *
 * The corpus half is derived from `CORPUS_FIXTURE_DIRS` rather than spelled out again: a second
 * literal list is a directory somebody adds to one of them, and the validation it silently drops
 * out of fails minutes into a stack run instead of in the `schema` project.
 */
export const TRACKED_FIXTURE_DIRS: readonly string[] = [
  ...CORPUS_FIXTURE_DIRS.filter((dir) => dir !== AXIS_FIXTURE_DIR),
  BROKERS_FIXTURE_DIR,
];

/**
 * Fixtures the seed never imports, because the spec that owns one creates and destroys it.
 *
 * An import over a live id is an update, so a fixture the seed also imported would have its chain
 * deleted mid-run by its own spec, and the corpus teardown would then fail over a chain nobody
 * broke.
 *
 * The three `script-failures-*` chains are here for a second reason: they cannot deploy. Each one
 * carries a script that does not compile, and the seed gates on **every** corpus chain reaching a
 * live route, so one of them in the corpus would fail the seed and with it every runtime spec in
 * the suite.
 *
 * The three `missing-*` chains under `fixtures/brokers/` are here for the same second reason, one
 * level up: `support/brokers.ts`'s `kafkaTopicsIn`/`rabbitmqTopologyIn` read topology straight off
 * whatever fixture the brokers seed's own corpus scan finds, and **create** it before the seed
 * deploys — so a topic or queue these three chains name would exist by the time
 * `specs/brokers/missing-target.spec.ts` ran, and the "nobody ever declared this" premise the spec
 * exists to test would be false before its first assertion.
 */
export const SPEC_OWNED_FIXTURES: ReadonlySet<string> = new Set([
  "script-inline.yaml",
  "script-failures-syntax.yaml",
  "script-failures-unresolved.yaml",
  "script-failures-nested-syntax.yaml",
  "missing-kafka-topic.yaml",
  "missing-rabbitmq-queue.yaml",
  "missing-rabbitmq-exchange.yaml",
]);

/**
 * The fixtures the micro copy of the corpus holds: the ones the `runtime-micro` files read through
 * `seedChain`, and no other.
 *
 * Every micro chain loads into one Integration, and the micro engine stops the pod when one chain
 * fails to load, so a chain no case reads could fail the whole project and back no assertion.
 * `specs/schema/corpus-copies.spec.ts` compares this list with what those files read.
 */
export const MICRO_FIXTURES: readonly string[] = [
  "chain-call",
  "chain-callee",
  "checkpoint",
  "choice",
  "circuit-breaker-count-based",
  "circuit-breaker-time-based",
  "condition",
  "condition-branches",
  "context-propagation",
  "file-read",
  "file-write",
  "http-echo",
  "http-trigger-correlationIdPosition-body",
  "http-trigger-handleChainFailureAction-chain-call",
  "http-trigger-handleChainFailureAction-default",
  "http-trigger-handleChainFailureAction-mapper-2",
  "http-trigger-handleChainFailureAction-script",
  "http-trigger-handleValidationAction-default",
  "http-trigger-handleValidationAction-mapper-2",
  "http-trigger-handleValidationAction-script",
  "http-trigger-idempotency-actionOnDuplicate-execute-subchain",
  "http-trigger-idempotency-actionOnDuplicate-ignore",
  "http-trigger-idempotency-actionOnDuplicate-throw-exception",
  "http-trigger-idempotency-enabled-false",
  "http-trigger-receiveCorrelationId-true",
  "loop",
  "mapper",
  "masking",
  "reuse",
  "script",
  "script-exchange-body.yaml",
  "script-exchange-headers.yaml",
  "script-exchange-null-body.yaml",
  "script-exchange-properties.yaml",
  "script-in-container-loop.yaml",
  "script-in-container-split.yaml",
  "script-libraries-datetime.yaml",
  "script-libraries-jdk.yaml",
  "script-libraries-json.yaml",
  "script-libraries-jsr223.yaml",
  "script-libraries-nio.yaml",
  "script-libraries-sql.yaml",
  "script-libraries-xml.yaml",
  "split",
  "split-async",
  "try-catch-finally",
  "try-catch-finally-branches",
  "xslt",
];

/**
 * What the micro copy puts before an `http-trigger` path. No leading slash: the templating and the
 * catalog refuse a second chain on the same `contextPath`, and `api-spec-export.spec.ts` builds
 * `/${contextPath}`.
 */
export const MICRO_PATH_PREFIX = "micro/";

const UUID_PATTERN = new RegExp(`\\b${UUID_TEXT}\\b`, "gi");

/** The id a chain or an element takes in the micro copy, derived from its id in the fixture. */
function microId(id: string): string {
  const hex = crypto.createHash("sha256").update(`micro/${id.toLowerCase()}`).digest("hex");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-4${hex.slice(13, 16)}-8${hex.slice(17, 20)}-${hex.slice(20, 32)}`;
}

/** The `contextPath` of every `http-trigger` in a chain document, at any depth. */
export function httpTriggerPaths(document: Record<string, unknown>): string[] {
  const found: string[] = [];
  const pending = [...(((document.content as { elements?: unknown[] })?.elements) ?? [])];
  while (pending.length > 0) {
    const element = pending.shift() as {
      type?: unknown;
      properties?: { contextPath?: unknown };
      children?: unknown[];
    };
    const contextPath = element.properties?.contextPath;
    if (element.type === "http-trigger" && typeof contextPath === "string") found.push(contextPath);
    pending.push(...(element.children ?? []));
  }
  return found;
}

/**
 * One rendered fixture as the micro copy holds it.
 *
 * Every UUID in the tree is replaced with `microId` of itself, as text, so each reference kind
 * follows at once: dependencies, the `elementId`-style properties, and the ids inside checkpoint
 * `contextPath` values. Only an `http-trigger` path gets `MICRO_PATH_PREFIX`; a checkpoint path is
 * unique already, because it carries the remapped ids.
 */
export function microCopy(name: string, tree: RenderedTree): RenderedTree {
  const copy: RenderedTree = new Map();
  for (const [rel, content] of tree) copy.set(rel, content.replace(UUID_PATTERN, (id) => microId(id)));

  const { document } = readFixtureDocument(name, copy);
  const file = [...copy.keys()].find((rel) => !rel.includes("/") && rel.endsWith(".yaml"))!;
  let source = copy.get(file)!;
  for (const contextPath of httpTriggerPaths(document)) {
    const line = `contextPath: "${contextPath}"`;
    const count = source.split(line).length - 1;
    if (count !== 1) {
      throw new Error(`fixture ${name}: expected one ${line} to prefix, found ${count}`);
    }
    source = source.replace(line, `contextPath: "${MICRO_PATH_PREFIX}${contextPath}"`);
  }
  copy.set(file, source);
  return copy;
}

/** `e2e/fixtures/specifications/` — the API documents the specification specs import. */
export const SPECIFICATION_FIXTURE_DIR = path.join(HERE, "specifications");

/**
 * `e2e/fixtures/sessions/recorded-session.json` — one session of the `http-echo` chain, as the
 * engine recorded it.
 *
 * A path rather than a reader, because the two callers want different things from it: the schema
 * spec reads it once and asserts over the document, and the sessions spec re-identifies every step
 * before importing it. Naming the file here is what keeps the two from computing it apart.
 */
export const RECORDED_SESSION_FIXTURE = path.join(HERE, "sessions", "recorded-session.json");

/** What each specification extension is uploaded as. The catalog picks its parser off the name. */
const SPECIFICATION_MEDIA_TYPES: Record<string, string> = {
  ".yaml": "application/yaml",
  ".graphql": "application/graphql",
};

/**
 * One specification fixture, in the shape Playwright's `multipart` takes.
 *
 * The catalog reads the uploaded file's **name** as well as its bytes — the parser is picked from
 * the extension — so the name travels with the buffer rather than being invented at the call site.
 * The media type follows the same extension: it is not what the catalog reads, but a `.graphql`
 * document declared `application/yaml` misleads the next person to open the call.
 */
export function readSpecificationFixture(file: string): {
  name: string;
  mimeType: string;
  buffer: Buffer;
} {
  const extension = path.extname(file).toLowerCase();
  return {
    name: file,
    mimeType: SPECIFICATION_MEDIA_TYPES[extension] ?? "application/yaml",
    buffer: fs.readFileSync(path.join(SPECIFICATION_FIXTURE_DIR, file)),
  };
}

/** A fixture tree rendered into memory: archive-relative path → file contents. */
export type RenderedTree = Map<string, string>;

/** The document kinds a fixture directory can hold, and how each is addressed in an archive. */
const DOCUMENT_KINDS = {
  chain: { parentDir: "chains", postfix: ".chain.cip.yaml" },
  service: { parentDir: "services", postfix: ".service.cip.yaml" },
} as const;

export type DocumentKind = keyof typeof DOCUMENT_KINDS;

/**
 * Substitutes the run token. Deliberately a plain replace over the raw text rather than a parse:
 * a fixture holds a Groovy script or an XSLT as readily as it holds YAML, and those are not
 * documents this suite has any business re-serializing.
 */
export function substitute(content: string, run: string): string {
  return content.split(RUN_PLACEHOLDER).join(run);
}

/** Every file under `dir`, recursively, keyed by its path relative to `dir`, in POSIX form. */
function readFixtureTree(dir: string): Map<string, string> {
  const files = new Map<string, string>();
  for (const entry of fs.readdirSync(dir, { withFileTypes: true, recursive: true })) {
    if (!entry.isFile()) continue;
    const abs = path.join(entry.parentPath, entry.name);
    files.set(path.relative(dir, abs).split(path.sep).join("/"), fs.readFileSync(abs, "utf-8"));
  }
  return files;
}

/** The same tree with the run token substituted. Nothing else changes. */
export function renderFixtureTree(dir: string, run: string): RenderedTree {
  const rendered: RenderedTree = new Map();
  for (const [rel, content] of readFixtureTree(dir)) {
    rendered.set(rel, substitute(content, run));
  }
  return rendered;
}

/**
 * The fixture names a directory holds, in either layout it may use: a directory per chain under
 * `fixtures/chains/`, a file per chain under `fixtures/script/`.
 *
 * The name is what is on disk, extension included, because it is also how the seed and every spec
 * address the chain the fixture becomes.
 */
export function chainFixtureNames(dir: string): string[] {
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .filter((entry) => entry.isDirectory() || (entry.isFile() && entry.name.endsWith(".yaml")))
    .map((entry) => entry.name)
    .sort();
}

/**
 * One fixture rendered, whichever layout its directory uses.
 *
 * A file fixture renders to a one-entry tree, so validation, assembly and the corpus all read one
 * shape and none of them has to ask which layout a fixture came from.
 */
export function renderFixture(dir: string, name: string, run: string): RenderedTree {
  const at = path.join(dir, name);
  if (fs.statSync(at).isDirectory()) return renderFixtureTree(at, run);
  return new Map([[name, substitute(fs.readFileSync(at, "utf-8"), run)]]);
}

/**
 * The fixture directories of the whole corpus, name → directory.
 *
 * A directory that does not exist contributes nothing: `fixtures/axes/` is absent until the first
 * seed writes it. A name found in two directories is refused, because the seed and every spec
 * address a chain by its fixture name alone, and a `SPEC_OWNED_FIXTURES` name is left out, because
 * the spec that owns it imports and deletes it itself.
 */
export function corpusFixtures(dirs: readonly string[] = CORPUS_FIXTURE_DIRS): Map<string, string> {
  const seen = new Map<string, string>();
  const found = new Map<string, string>();
  for (const dir of dirs) {
    if (!fs.existsSync(dir)) continue;
    for (const name of chainFixtureNames(dir)) {
      // The duplicate check runs over **every** name, spec-owned included: a spec addresses its own
      // fixture by name alone too, so a name in two directories is the same ambiguity there. Only
      // the skip below is about the seed.
      const already = seen.get(name);
      if (already !== undefined) {
        throw new Error(`fixture ${name} exists in both ${already} and ${dir}`);
      }
      seen.set(name, dir);
      if (!SPEC_OWNED_FIXTURES.has(name)) found.set(name, dir);
    }
  }
  return new Map([...found].sort(([a], [b]) => a.localeCompare(b)));
}

/** Every fixture name the seed imports, across all corpus directories. */
export function corpusFixtureNames(dirs: readonly string[] = CORPUS_FIXTURE_DIRS): string[] {
  return [...corpusFixtures(dirs).keys()];
}

/** The one document a fixture directory describes, plus whatever files travel beside it. */
export interface FixtureDocument {
  kind: DocumentKind;
  /** The document's own `id`, after substitution. It names the archive directory. */
  id: string;
  document: Record<string, unknown>;
  /** The rendered YAML of the document, byte for byte what goes into the archive. */
  source: string;
  /** Everything else in the tree, keyed by its path relative to the fixture directory. */
  companions: Map<string, string>;
}

function documentKindOf(schemaUrl: unknown, where: string): DocumentKind {
  if (typeof schemaUrl !== "string") {
    throw new Error(`${where}: no $schema, so the document kind is unknown`);
  }
  // The exporter writes `.../conf-model/chain`, and the frozen documents keep the old
  // `.../qip/chain.schema.yaml`. Both name the same kind.
  const tail = schemaUrl.split("/").pop()!.replace(/\.schema\.yaml$/, "");
  if (tail in DOCUMENT_KINDS) return tail as DocumentKind;
  throw new Error(`${where}: $schema "${schemaUrl}" names no document kind this suite assembles`);
}

/** Reads a rendered fixture tree as one document plus its companions. */
export function readFixtureDocument(name: string, tree: RenderedTree): FixtureDocument {
  const documents = [...tree.keys()].filter((rel) => !rel.includes("/") && rel.endsWith(".yaml"));
  if (documents.length !== 1) {
    throw new Error(
      `fixture ${name}: expected exactly one top-level YAML document, found ${documents.length}`,
    );
  }
  const source = tree.get(documents[0])!;
  const document = yaml.load(source) as Record<string, unknown>;
  const kind = documentKindOf(document?.$schema, `fixture ${name}`);
  const id = document?.id;
  if (typeof id !== "string" || id.length === 0) {
    throw new Error(`fixture ${name}: the document declares no id, and the id names its directory`);
  }

  const companions = new Map([...tree].filter(([rel]) => rel !== documents[0]));
  return { kind, id, document, source, companions };
}

/**
 * Refuses a tree that still carries a placeholder, naming the entry and the placeholder.
 *
 * This is the guard, not a convenience: an unsubstituted `{{RUN}}` reaches the platform as a
 * literal, the import succeeds, and the chain deploys on a context path nobody can call. The
 * failure surfaces later as a runtime spec timing out against a route that was never there.
 */
export function assertSubstituted(entries: Iterable<[string, string]>): void {
  const leftovers: string[] = [];
  for (const [rel, content] of entries) {
    for (const match of content.matchAll(PLACEHOLDER_PATTERN)) {
      leftovers.push(`${rel}: ${match[0]}`);
    }
  }
  if (leftovers.length > 0) {
    throw new Error(`unsubstituted placeholders in the assembled archive: ${leftovers.join(", ")}`);
  }
}

/**
 * The archive entries one fixture contributes.
 *
 * Entries land under `<parentDir>/<id>/`, with the document itself named after the id and
 * everything else keeping its path inside the fixture directory.
 */
function archiveEntries(fixture: FixtureDocument, into = new Map<string, string>()): Map<string, string> {
  const { parentDir, postfix } = DOCUMENT_KINDS[fixture.kind];
  const base = `${parentDir}/${fixture.id}`;

  into.set(`${base}/${fixture.id}${postfix}`, fixture.source);
  for (const [rel, content] of fixture.companions) into.set(`${base}/${rel}`, content);
  return into;
}

/** Zips a set of entries, refusing an unsubstituted placeholder first. */
async function zipEntries(entries: Map<string, string>): Promise<Buffer> {
  assertSubstituted(entries);
  return await zipOf(entries);
}

/** Every `contextPath` the document names, at any depth: an HTTP trigger's route on the stack. */
function contextPaths(document: unknown): string[] {
  const found: string[] = [];
  const visit = (node: unknown): void => {
    if (Array.isArray(node)) {
      for (const each of node) visit(each);
      return;
    }
    if (node === null || typeof node !== "object") return;
    for (const [key, value] of Object.entries(node)) {
      if (key === "contextPath" && typeof value === "string" && value.length > 0) found.push(value);
      else visit(value);
    }
  };
  visit(document);
  return found;
}

/** Turns one rendered fixture tree into the ZIP the importer accepts. */
export async function assembleArchive(name: string, tree: RenderedTree): Promise<Buffer> {
  return await zipEntries(archiveEntries(readFixtureDocument(name, tree)));
}

/**
 * One single-file fixture, rendered as its document.
 *
 * The document is returned rather than only its archive, because a spec has to delete the chain
 * before it imports it — an import over a live id is an update — and the id is in the fixture.
 *
 * `dir` is required here and on `assembleFixture`, which the same call site pairs this with: a
 * default on either one reads as that call addressing a different directory from its partner.
 */
export function readDocumentFixture(file: string, run: string, dir: string): FixtureDocument {
  return readFixtureDocument(file, renderFixture(dir, file, run));
}

/** Renders a fixture and assembles it in one step — what a spec actually calls. */
export async function assembleFixture(name: string, run: string, dir: string): Promise<Buffer> {
  return await assembleArchive(name, renderFixture(dir, name, run));
}

/**
 * Several fixture directories in one archive, which is how the seed pays the import once.
 *
 * The importer reads a directory per document, so the archives are independent of each other and
 * merging them is a concatenation of entries. Two collisions are refused here rather than
 * discovered later, and both are silent at the point they happen:
 *
 * - the same **id** collapses two documents into one archive entry, and the corpus is short a
 *   chain nobody asked about;
 * - the same **contextPath** imports cleanly and fails at deploy, because a route is a route and
 *   the second one loses. The failure lands on whichever chain deploys second, which is not the
 *   fixture that has to change.
 */
export async function assembleCorpus(
  names: readonly string[],
  run: string,
  dirs: readonly string[] = CORPUS_FIXTURE_DIRS,
): Promise<{ archive: Buffer; documents: FixtureDocument[] }> {
  const located = corpusFixtures(dirs);
  return await assembleTrees(
    names.map((name) => {
      const dir = located.get(name);
      if (dir === undefined) throw new Error(`no corpus directory holds fixture ${name}`);
      return [name, renderFixture(dir, name, run)];
    }),
  );
}

/** `assembleCorpus` over fixtures already rendered, in the order given. */
export async function assembleTrees(
  trees: ReadonlyArray<readonly [string, RenderedTree]>,
): Promise<{ archive: Buffer; documents: FixtureDocument[] }> {
  const documents: FixtureDocument[] = [];
  const entries = new Map<string, string>();
  const byId = new Map<string, string>();
  const byContextPath = new Map<string, string>();

  for (const [name, tree] of trees) {
    const fixture = readFixtureDocument(name, tree);
    const clash = byId.get(fixture.id);
    if (clash !== undefined) {
      throw new Error(`fixtures ${clash} and ${name} declare the same id ${fixture.id}`);
    }
    byId.set(fixture.id, name);

    for (const contextPath of contextPaths(fixture.document)) {
      const owner = byContextPath.get(contextPath);
      if (owner !== undefined && owner !== name) {
        throw new Error(
          `fixtures ${owner} and ${name} declare the same contextPath ${contextPath}`,
        );
      }
      byContextPath.set(contextPath, name);
    }

    documents.push(fixture);
    archiveEntries(fixture, entries);
  }

  return { archive: await zipEntries(entries), documents };
}
