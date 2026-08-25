// This extension measured against the shared naming corpus in `schemas/src/test/resources/naming`.
//
// Every other test of the file names here is single sided: it compares this extension against
// itself, so the whole suite stays green while the extension and runtime-catalog drift apart.
// Measured — renaming one postfix constant in the backend reddens 21 cases there and none here.
// What makes this file different is that the expected names are computed from the corpus rule, and
// the backend computes its own from that same rule.
//
// The corpus is not an oracle of this extension's behaviour, and it is not regenerable from it. A
// red case is a question about which side broke the rule, never an invitation to edit the corpus.

import {
  currentFormatName,
  loadNameCorpus,
  perTypeName,
} from "./nameCorpusSupport";
import { buildDefaultExtensions } from "../../../src/web/response/file/fileExtensions";
import {
  isServiceFileOfAnyKind,
  serviceFileNameForType,
  serviceIdFromFileName,
  serviceTypeFromSchema,
  ServiceSchemaUrls,
} from "../../../src/web/response/file/serviceFileType";
import { IntegrationSystemType } from "../../../src/web/api-services/servicesTypes";
import { EXTENSION_KEY_BY_TYPE } from "../../../src/web/response/file/namePrecedence";
import { DEFAULT_SCHEMA_URLS } from "../../../src/web/services/ProjectConfigService";
import { URN_SCHEMA_URLS } from "../../helpers/mocks";

const corpus = loadNameCorpus();

const PLAIN_KINDS = corpus.alphabet.plainKinds;

/** Every id the current format can state. A dotted id is the refusal case, asserted on its own. */
const nameableIds = corpus.alphabet.serviceIds.filter(
  (id) => !id.includes("."),
);
const dottedIds = corpus.alphabet.serviceIds.filter((id) => id.includes("."));

type Triple = { id: string; appName: string; kind: string };

function triples(kinds: string[]): Triple[] {
  return nameableIds.flatMap((id) =>
    corpus.alphabet.appNames.flatMap((appName) =>
      kinds.map((kind) => ({ id, appName, kind })),
    ),
  );
}

describe("service file names, measured against the shared corpus", () => {
  it("the corpus carries cases to measure", () => {
    expect(nameableIds.length).toBeGreaterThan(0);
    expect(dottedIds.length).toBeGreaterThan(0);
    expect(corpus.classify.length).toBeGreaterThan(0);
    expect(new Set(corpus.classify.map((c) => c.name)).size).toBe(
      corpus.classify.length,
    );
  });

  // --- invariant 1: the write path produces what the rule says ---------------------------------
  //
  // [decision] Generation is asserted for the three plain kinds only. `isPlainServiceType` gates
  // the production call site (`serviceFileWrite.writeServiceInCurrentFormat`), so driving a context
  // or MCP type through `serviceFileNameForType` would exercise a mode production never reaches.
  // Reading, below, is not gated and covers all five.

  it.each(triples(PLAIN_KINDS))(
    "writes $kind '$id' under app '$appName' the way the rule says",
    ({ id, appName, kind }) => {
      const extensions = buildDefaultExtensions(appName);
      const seed = perTypeName(corpus, id, kind, appName);

      // The seed has to parse first. An unchanged name is also the answer for a name that was never
      // a service name, so without this a refusal assertion passes for the wrong reason.
      expect(serviceIdFromFileName(seed, extensions)).toBe(id);

      expect(
        serviceFileNameForType(seed, kind as IntegrationSystemType, extensions),
      ).toBe(currentFormatName(corpus, id, kind, appName));
    },
  );

  // --- invariant 2: a produced name reads back as the same id ----------------------------------
  //
  // The name states the kind of document and no longer the type, so what reads back off it is the
  // id. The type is invariant 2b, off `$schema`.

  it.each(triples(corpus.alphabet.kinds))(
    "reads the id of $kind '$id' under app '$appName' back off its own name",
    ({ id, appName, kind }) => {
      const extensions = buildDefaultExtensions(appName);
      const name = currentFormatName(corpus, id, kind, appName);

      expect(serviceIdFromFileName(name, extensions)).toBe(id);
      expect(isServiceFileOfAnyKind(name, extensions)).toBe(true);
    },
  );

  it.each(triples(PLAIN_KINDS))(
    "reads the id of the per-type name of '$id' under app '$appName' back",
    ({ id, appName, kind }) => {
      const extensions = buildDefaultExtensions(appName);
      const name = perTypeName(corpus, id, kind, appName);

      expect(serviceIdFromFileName(name, extensions)).toBe(id);
      expect(isServiceFileOfAnyKind(name, extensions)).toBe(true);
    },
  );

  // --- invariant 2b: the $schema states what the name no longer does ----------------------------

  it.each(PLAIN_KINDS)("types a %s document by its $schema", (kind) => {
    const configured: ServiceSchemaUrls = URN_SCHEMA_URLS;
    const stem = corpus.types.schemaFileStems[kind];

    // The file-name layer, which is what carries a document between two differently configured
    // installations, and the only part the two implementations can drift apart on unseen.
    expect(
      serviceTypeFromSchema(
        `https://elsewhere.example/${stem}.schema.yaml`,
        configured,
      ),
    ).toBe(kind);
    // The default URI both sides ship with has to read back through that same layer.
    expect(
      serviceTypeFromSchema(corpus.types.defaultSchemaUris[kind], configured),
    ).toBe(kind);
  });

  // The exact per-side outcome, not merely "not a plain kind": this side resolves CONTEXT and MCP
  // where Java resolves nothing, and the corpus records that divergence. A change that reads these
  // as untyped un-anchors the write path that keeps a context document on its own name.
  it.each(corpus.types.statingNoPlainType)(
    "reads $uri as the outcome the corpus records",
    ({ uri, ts }) => {
      const resolved = serviceTypeFromSchema(uri, URN_SCHEMA_URLS);

      expect(resolved ?? "no-type").toBe(ts);
    },
  );

  it("stems the two typeless kinds the way the corpus declares", () => {
    for (const [kind, stem] of Object.entries(
      corpus.types.extensionOnlySchemaFileStems,
    )) {
      expect(
        serviceTypeFromSchema(
          `https://elsewhere.example/${stem}.schema.yaml`,
          URN_SCHEMA_URLS,
        ),
      ).toBe(kind);
    }
  });

  // The shipped defaults are bound to the corpus on this side too — Java pins its own through
  // `schemaUri(type)`, and an inconsistent corpus edit has to redden both harnesses, not one.
  it("ships the default schema urls the corpus records", () => {
    for (const kind of PLAIN_KINDS) {
      expect(
        DEFAULT_SCHEMA_URLS[
          EXTENSION_KEY_BY_TYPE[kind as IntegrationSystemType]
        ],
      ).toBe(corpus.types.defaultSchemaUris[kind]);
    }
  });

  // --- invariant 3: names collide exactly where the rule says they do ---------------------------

  it("only the three plain kinds share a current-format name", () => {
    const byName = new Map<string, string>();
    for (const { id, appName, kind } of triples(corpus.alphabet.kinds)) {
      const name = currentFormatName(corpus, id, kind, appName);
      const key = `${PLAIN_KINDS.includes(kind) ? "PLAIN" : kind}|${id}|${appName}`;
      const clash = byName.get(name);
      expect([name, clash ?? key]).toEqual([name, key]);
      byName.set(name, key);
    }
  });

  // --- invariant 4: a refusal, in this side's spelling -------------------------------------------
  //
  // The backend throws; here the input name comes back unchanged, which is documented behaviour.

  it.each(
    dottedIds.flatMap((id) =>
      corpus.alphabet.appNames.flatMap((appName) =>
        PLAIN_KINDS.map((kind) => ({ id, appName, kind })),
      ),
    ),
  )(
    "refuses to build a $kind name for the dotted id '$id' under app '$appName'",
    ({ id, appName, kind }) => {
      const extensions = buildDefaultExtensions(appName);
      const seed = perTypeName(corpus, id, kind, appName);

      // Again: prove the seed is a service name, or "unchanged" means nothing.
      expect(serviceIdFromFileName(seed, extensions)).toBe(id);

      expect(
        serviceFileNameForType(seed, kind as IntegrationSystemType, extensions),
      ).toBe(seed);
    },
  );

  // --- invariant 5: classification, per side, disagreements declared ----------------------------

  it.each(corpus.classify)(
    "classifies $name the way the corpus records it for this side",
    (entry) => {
      expect(typeof entry.appName).toBe("string");
      expect(entry.reason ?? entry.divergence).toBeDefined();

      const extensions = buildDefaultExtensions(entry.appName);
      const actual = isServiceFileOfAnyKind(entry.fileName, extensions)
        ? "service"
        : "not-a-service";

      expect(actual).toBe(entry.ts);

      if (entry.ts !== entry.java) {
        expect(entry.divergence).toBeDefined();
      }
    },
  );
});
