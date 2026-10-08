/**
 * Reads the element schemas and reports the discriminator axes each one declares.
 *
 * The source of truth is `schemas/src/main/resources/conf-model/element/`, not `schemas/assets/`.
 * `assets/` is a build output — `schemas/.gitignore` lists it, nothing tracks it, and it holds
 * whatever the last `npm -w @netcracker/qip-schemas run build` produced. A gap detector reading it
 * would go stale the moment somebody adds an axis and does not rebuild.
 *
 * The cost of reading source is that `$ref`s are still `$ref`s, so this module resolves them the
 * same way `schemas/src/main/scripts/schemaResolver.ts` does, stubs included.
 */
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";
import $RefParser from "@apidevtools/json-schema-ref-parser";
import yaml from "js-yaml";

const HERE = path.dirname(fileURLToPath(import.meta.url));

/** The tree every `SCHEMA_BASE_URL` id resolves into, as `<id path>.schema.yaml`. */
export const QIP_MODEL_DIR = path.resolve(
  HERE,
  "../../schemas/src/main/resources/conf-model",
);

/** The canonical element schemas. A caller may point the extractor somewhere else. */
export const ELEMENT_SCHEMA_DIR = path.join(QIP_MODEL_DIR, "element");

/**
 * The prefix every schema `$id` in `QIP_MODEL_DIR` carries; the rest of the id is the file path
 * without `.schema.yaml`.
 */
export const SCHEMA_BASE_URL = "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/";

export type SchemaValue = string | number | boolean | null;

/**
 * One axis of one element. `axisPath` is the key — `service-call` declares four distinct axes whose
 * property name is `type`, so the bare name collapses them into one meaningless blob.
 */
export interface Discriminator {
  element: string;
  axisPath: string;
  axis: string;
  values: SchemaValue[];
}

/** Property names that look like axes but describe export serialisation, not behaviour. */
const EXPORT_DIRECTIVES = new Set([
  "exportFileExtension",
  "propertiesToExportInSeparateFile",
]);

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

// ---------------------------------------------------------------------------
// Resolution
// ---------------------------------------------------------------------------

function confModelResolver(modelDir: string) {
  return {
    order: 2,
    canRead: (file: { url: string }) => file.url.startsWith(SCHEMA_BASE_URL),
    read(file: { url: string }) {
      const absPath = path.resolve(
        modelDir,
        `${file.url.slice(SCHEMA_BASE_URL.length)}.schema.yaml`,
      );
      // `ELEMENT_SCHEMA_DIR` is overridable through `E2E_ELEMENT_SCHEMA_DIR`, which is how the
      // mutation seam works, so the tree being resolved is not always the tracked one. A `$ref`
      // that climbs out of it reads a file the extractor was never pointed at.
      const root = path.resolve(modelDir);
      if (absPath !== root && !absPath.startsWith(root + path.sep)) {
        throw new Error(`${file.url} resolves to ${absPath}, outside the model tree ${root}`);
      }
      const content = fs.readFileSync(absPath, "utf-8");
      return absPath.endsWith(".yaml") ? yaml.load(content) : content;
    },
  };
}

const ignoreSchemaResolver = {
  order: 0,
  canRead: (file: { url: string }) =>
    file.url === "http://json-schema.org/draft-07/schema",
  read: (file: { url: string }) => file.url,
};

/**
 * The mapper data model is stubbed out, exactly as the schemas build stubs it. Resolving it for
 * real pulls roughly fifteen `const`s (`string`, `number`, `allOf`, `constant`, …) out of the
 * mapper's own type model into every element that references a mapper, and invents axes the
 * platform does not have.
 */
const ignoreMapperResolver = {
  order: 1,
  canRead: (file: { url: string }) =>
    file.url ===
    `${SCHEMA_BASE_URL}element/properties/mapper-description`,
  read: () => ({ type: "object" }),
};

/** Element schema files, in the order and with the exclusion `SchemaResolver` uses. */
export function collectElementSchemaFiles(dir = ELEMENT_SCHEMA_DIR): string[] {
  return fs
    .readdirSync(dir, { withFileTypes: true })
    .filter(
      (entry) =>
        entry.isFile() &&
        entry.name !== "element.schema.yaml" &&
        entry.name.endsWith(".yaml"),
    )
    .map((entry) => entry.name)
    .sort();
}

/** `condition.schema.yaml` → `condition`, the name the library and the registry both use. */
export function elementNameOf(file: string): string {
  return file.replace(/\.(schema\.)?yaml$/, "");
}

/** One element schema with every `$ref` resolved. `resolvedElementSchemas` is its one caller. */
async function resolveElementSchema(
  file: string,
  dir = ELEMENT_SCHEMA_DIR,
  modelDir = QIP_MODEL_DIR,
): Promise<unknown> {
  const raw = yaml.load(fs.readFileSync(path.join(dir, file), "utf-8"));
  return await $RefParser.dereference(raw as object, {
    resolve: {
      ignoreSchema: ignoreSchemaResolver,
      ignoreMapperResolver,
      confModel: confModelResolver(modelDir),
      file: true,
      http: false,
    },
    dereference: {
      // `children` is the container recursion: an element holds elements. Resolving it never
      // terminates, and the schemas build excludes the same path.
      excludedPathMatcher: (p: string) => p.includes("/properties/children/items"),
      onCircular: () => {},
    },
  });
}

/** Every element schema in `dir`, resolved, keyed by element name. */
export async function loadElementSchemas(
  dir = ELEMENT_SCHEMA_DIR,
  modelDir = QIP_MODEL_DIR,
): Promise<Map<string, unknown>> {
  const resolved = new Map<string, unknown>();
  for (const file of collectElementSchemaFiles(dir)) {
    resolved.set(elementNameOf(file), await resolveElementSchema(file, dir, modelDir));
  }
  return resolved;
}

// ---------------------------------------------------------------------------
// Extraction
// ---------------------------------------------------------------------------

interface Site {
  axisPath: string;
  axis: string;
  values: SchemaValue[];
  underIf: boolean;
}

/**
 * The values a property schema discriminates on, or `[]` when it discriminates on nothing.
 *
 * A branch set with no `const` anywhere — `{type: integer}` against `{type: string, pattern: …}` —
 * is a value union rather than an axis, and `specs/runtime/placeholder.spec.ts` covers it once for
 * the whole platform. A branch set whose branches carry a `const` is an axis even when the branches also
 * carry `title` and `description`, which every real example does: reading clause 3 as "bare
 * `const`s" silently truncates twenty values across thirteen axes.
 */
function axisValues(schema: unknown): SchemaValue[] {
  if (!isPlainObject(schema)) return [];
  if ("const" in schema) return [schema.const as SchemaValue];
  if (Array.isArray(schema.enum)) return schema.enum as SchemaValue[];

  for (const keyword of ["oneOf", "anyOf"] as const) {
    const branches = schema[keyword];
    if (!Array.isArray(branches)) continue;
    const values: SchemaValue[] = [];
    for (const branch of branches) {
      // `{not: {}}` never validates. Its one site in the tree — service-call's
      // ExchangeTransformation — means "the property is X if present", so it carries no value.
      if (!isPlainObject(branch)) continue;
      if ("const" in branch) values.push(branch.const as SchemaValue);
      else if (Array.isArray(branch.enum)) values.push(...(branch.enum as SchemaValue[]));
    }
    if (values.length > 0) return values;
  }
  return [];
}

/**
 * Walks a resolved schema collecting axis sites.
 *
 * `names` is the normalised path: property names and `items`, with `if`, `then`, `else` and the
 * branch indices of `allOf`, `oneOf` and `anyOf` folded out. Two sites that normalise to the same
 * key are one axis — `integrationOperationProtocolType` is declared at six sites and is one axis.
 *
 * `definitions` is never walked, at any depth. Every real use of a definition is inlined by the
 * time this runs, so walking one only re-counts sites under a key named after the definition:
 * `correlationIdPosition` reappears that way on four elements.
 */
function walk(
  node: unknown,
  names: string[],
  underIf: boolean,
  out: Site[],
  ancestors: Set<object>,
): void {
  if (Array.isArray(node)) {
    for (const item of node) walk(item, names, underIf, out, ancestors);
    return;
  }
  if (!isPlainObject(node)) return;
  if (ancestors.has(node)) return;
  ancestors.add(node);

  for (const [key, value] of Object.entries(node)) {
    if (key === "definitions" || key === "not") continue;

    if (key === "properties" && isPlainObject(value)) {
      for (const [propName, propSchema] of Object.entries(value)) {
        // The element's own `properties` container is a wrapper, not a step in the axis path.
        const childNames =
          names.length === 0 && propName === "properties"
            ? names
            : [...names, propName];
        const values = axisValues(propSchema);
        if (values.length > 0 && childNames.length > 0) {
          out.push({
            axisPath: childNames.join("/"),
            axis: childNames[childNames.length - 1],
            values,
            underIf,
          });
        }
        walk(propSchema, childNames, underIf, out, ancestors);
      }
      continue;
    }

    if (key === "items") {
      walk(value, [...names, "items"], underIf, out, ancestors);
      continue;
    }

    // `if` is a condition, so a `const` under it is a discriminator value rather than a fixed
    // property. `then` and `else` are read alike: dropping `else` loses the four-value
    // securityProtocol and the 42-value saslMechanism axes on both kafka elements.
    walk(value, names, key === "if" ? true : underIf, out, ancestors);
  }

  ancestors.delete(node);
}

/**
 * The axes one element declares.
 *
 * `element` is taken from the schema's `$id` when the caller does not supply it, so a fixture
 * schema outside the tracked tree still reports under a name.
 */
export function extractDiscriminators(
  schema: unknown,
  element?: string,
): Discriminator[] {
  const root = isPlainObject(schema) ? schema : {};
  const name =
    element ??
    (typeof root.$id === "string"
      ? elementNameOf(path.basename(root.$id))
      : "unknown");

  const sites: Site[] = [];
  walk(root, [], false, sites, new Set());

  const merged = new Map<string, Site>();
  for (const site of sites) {
    // The family tag: every element declares `properties.type.const` equal to its own name. All
    // 71 of them, and none of them is a discriminator.
    if (site.axisPath === "type") continue;
    if (EXPORT_DIRECTIVES.has(site.axis)) continue;

    const existing = merged.get(site.axisPath);
    if (existing) {
      existing.underIf ||= site.underIf;
      for (const value of site.values) {
        if (!existing.values.includes(value)) existing.values.push(value);
      }
    } else {
      merged.set(site.axisPath, { ...site, values: [...new Set(site.values)] });
    }
  }

  return [...merged.values()]
    .map((site) => ({
      element: name,
      axisPath: site.axisPath,
      axis: site.axis,
      values: completeBoolean(site),
    }))
    .sort((a, b) => a.axisPath.localeCompare(b.axisPath));
}

/**
 * A boolean axis is usually declared one-sided, as `const: true` under an `if`. Both values count.
 *
 * The expansion is confined to `if` sites on purpose: `checkpoint` declares
 * `externalRoute: {const: false}` in a plain `properties` block, which pins the property rather
 * than branching on it, and is a one-value axis.
 */
function completeBoolean(site: Site): SchemaValue[] {
  if (!site.underIf) return site.values;
  if (site.values.length !== 1 || typeof site.values[0] !== "boolean") {
    return site.values;
  }
  return [site.values[0], !site.values[0]];
}

/** Every axis of every element under `dir`. */
export async function extractAllDiscriminators(
  dir = ELEMENT_SCHEMA_DIR,
  modelDir = QIP_MODEL_DIR,
): Promise<Discriminator[]> {
  const schemas = await loadElementSchemas(dir, modelDir);
  return [...schemas].flatMap(([element, schema]) =>
    extractDiscriminators(schema, element),
  );
}
