// The single resolver every service-file site routes through. Two things it must never do: let one
// extension shadow another, which silently drops a service from a list or attributes a file to the
// wrong service; and read a type off anything but `$schema`, which is the only place the current
// format states one. The real `fileExtensions` module is used throughout, so the app-name
// resolution behind an unqualified call is under test as well.

let mockWorkspaceFolders:
  | { uri: { path: string; fsPath: string } }[]
  | undefined;

jest.mock(
  "vscode",
  () => ({
    __esModule: true,
    workspace: {
      get workspaceFolders() {
        return mockWorkspaceFolders;
      },
    },
  }),
  { virtual: true },
);

const mockConfigService = {
  isConfigLoaded: jest.fn(),
  getAllConfigs: jest.fn(),
};

jest.mock("../../../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: {
    getInstance: jest.fn(() => mockConfigService),
  },
}));

import {
  allServiceExtensions,
  isAnyServiceFile,
  isCurrentFormatServiceName,
  isPlainServiceType,
  isServiceFileOfAnyKind,
  plainServiceExtensions,
  resolveServiceType,
  serviceExtensionForType,
  serviceFileNameForType,
  serviceIdFromFileName,
  serviceSchemaUrlForType,
  serviceTypeFromSchema,
  ServiceExtensions,
  ServiceSchemaUrls,
} from "../../../src/web/response/file/serviceFileType";
import { IntegrationSystemType } from "../../../src/web/api-services/servicesTypes";
import { URN_SCHEMA_URLS } from "../../helpers/mocks";
import {
  buildDefaultExtensions,
  setDefaultAppName,
} from "../../../src/web/response/file/fileExtensions";

const qip: ServiceExtensions = buildDefaultExtensions("qip");
const acme: ServiceExtensions = buildDefaultExtensions("acme");

beforeEach(() => {
  setDefaultAppName("qip");
  mockWorkspaceFolders = [
    { uri: { path: "/workspace", fsPath: "/workspace" } },
  ];
  mockConfigService.isConfigLoaded.mockReturnValue(false);
  mockConfigService.getAllConfigs.mockReturnValue([]);
});

describe("serviceTypeFromSchema", () => {
  const configured: ServiceSchemaUrls = URN_SCHEMA_URLS;

  it.each([
    ["urn:external", IntegrationSystemType.EXTERNAL],
    ["urn:internal", IntegrationSystemType.INTERNAL],
    ["urn:implemented", IntegrationSystemType.IMPLEMENTED],
    ["urn:context", IntegrationSystemType.CONTEXT],
    ["urn:mcp", IntegrationSystemType.MCP],
  ])("reads the type the configured url %s states", (url, type) => {
    expect(serviceTypeFromSchema(url, configured)).toBe(type);
  });

  // The second layer: a document written by an installation whose schemaUrls differ from this
  // project's still types, because the schema's own file name is the same everywhere.
  it.each([
    [
      "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
      IntegrationSystemType.EXTERNAL,
    ],
    [
      "https://schemas.acme.internal/qip/internal-service.schema.yaml",
      IntegrationSystemType.INTERNAL,
    ],
    [
      "http://qubership.org/schemas/product/qip/implemented-service",
      IntegrationSystemType.IMPLEMENTED,
    ],
    ["context-service.schema.json", IntegrationSystemType.CONTEXT],
  ])("reads %s by the schema file name alone", (url, type) => {
    expect(serviceTypeFromSchema(url, configured)).toBe(type);
  });

  it("prefers the configured url over the file-name layer", () => {
    const swapped: ServiceSchemaUrls = {
      ...configured,
      internalService:
        "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
    };

    expect(
      serviceTypeFromSchema(
        "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
        swapped,
      ),
    ).toBe(IntegrationSystemType.INTERNAL);
  });

  it.each([
    undefined,
    "",
    "urn:service",
    "http://qubership.org/schemas/product/qip/service.schema.yaml",
    "http://qubership.org/schemas/product/qip/chain.schema.yaml",
    "https://schemas.acme.internal/qip/svc-ext.schema.yaml",
  ])("states no type for %p", (url) => {
    expect(serviceTypeFromSchema(url, configured)).toBeUndefined();
  });

  // A fragment or query is not part of the schema's file name: a JSON pointer must not read as a
  // stem, and a versioned url must still match its own.
  it.each([
    [
      "http://qubership.org/schemas/service.schema.yaml#/defs/external-service",
      undefined,
    ],
    [
      "http://qubership.org/schemas/external-service.schema.yaml?v=1.2",
      IntegrationSystemType.EXTERNAL,
    ],
    [
      "http://qubership.org/schemas/external-service#legacy",
      IntegrationSystemType.EXTERNAL,
    ],
  ])("reads %s past its fragment and query", (url, type) => {
    expect(serviceTypeFromSchema(url, configured)).toBe(type);
  });

  // YAML hands whatever the document holds; a broken `$schema` reads as untyped, never a throw.
  it.each([[{ nested: "map" }], [42], [null]])(
    "reads the non-string $schema %p as untyped",
    (value) => {
      expect(
        serviceTypeFromSchema(value as unknown as string, configured),
      ).toBeUndefined();
    },
  );
});

// Comparing the whole extension is what keeps the names apart. A prefix scan over the bare
// postfixes would let the shorter one win, which is the shadowing plan 1 spent five review rounds
// closing. The names no longer state a type, but which *kind* of document a file holds still
// decides which scan claims it and which id a read recovers.
describe("one extension must not shadow another", () => {
  it("never reads a context service as a plain service", () => {
    const name = "ctx-1.context-service.qip.yaml";

    expect(isAnyServiceFile(name)).toBe(false);
    expect(isServiceFileOfAnyKind(name)).toBe(true);
  });

  it("never reads a per-type name as the plain one", () => {
    for (const name of [
      "svc-1.external-service.qip.yaml",
      "svc-1.internal-service.qip.yaml",
      "svc-1.implemented-service.qip.yaml",
      "mcp-1.mcp-service.qip.yaml",
      "ctx-1.context-service.qip.yaml",
    ]) {
      expect(name.endsWith(qip.service)).toBe(false);
      expect(isServiceFileOfAnyKind(name)).toBe(true);
      expect(isCurrentFormatServiceName(name)).toBe(
        name.includes("context-service") || name.includes("mcp-service"),
      );
    }
  });

  // Autodiscovery mints service ids from Kubernetes service names, so `service-orders` is real.
  it("reads the id of a service whose id carries a postfix of its own", () => {
    expect(serviceIdFromFileName("service-orders.service.qip.yaml")).toBe(
      "service-orders",
    );
    expect(
      serviceIdFromFileName("context-service-orders.context-service.qip.yaml"),
    ).toBe("context-service-orders");
    expect(serviceIdFromFileName("mcp-service.mcp-service.qip.yaml")).toBe(
      "mcp-service",
    );
  });

  it("matches when the app name itself carries a postfix", () => {
    const extensions = buildDefaultExtensions("mcp-service");

    expect(isAnyServiceFile("svc-1.service.mcp-service.yaml", extensions)).toBe(
      true,
    );
    expect(
      isServiceFileOfAnyKind("svc-1.mcp-service.mcp-service.yaml", extensions),
    ).toBe(true);
  });

  it("does not read a service of another app as one of this app", () => {
    expect(isAnyServiceFile("svc-1.service.acme.yaml", qip)).toBe(false);
    expect(isAnyServiceFile("svc-1.external-service.acme.yaml", qip)).toBe(
      false,
    );
  });

  // The match is anchored at the end of the name, so an editor backup or a merge artefact
  // beside a real file is not a second copy of that service.
  it.each([
    "svc-1.external-service.qip.yaml.orig",
    "svc-1.service.qip.yaml.bak",
    "svc-1.external-service.qip.yaml.rej",
  ])("ignores %s, which only carries an extension in the middle", (name) => {
    expect(isAnyServiceFile(name, qip)).toBe(false);
    expect(isServiceFileOfAnyKind(name, qip)).toBe(false);
  });
});

describe("isAnyServiceFile", () => {
  it.each([
    "svc-1.service.qip.yaml",
    "svc-1.external-service.qip.yaml",
    "svc-1.internal-service.qip.yaml",
    "svc-1.implemented-service.qip.yaml",
  ])("accepts the plain service file %s", (name) => {
    expect(isAnyServiceFile(name)).toBe(true);
  });

  it.each([
    "ctx-1.context-service.qip.yaml",
    "mcp-1.mcp-service.qip.yaml",
    "chain-1.chain.qip.yaml",
    "api-1.api.qip.yaml",
    "grp-1.api-group.qip.yaml",
    "notes.md",
  ])("rejects %s", (name) => {
    expect(isAnyServiceFile(name)).toBe(false);
  });

  it("accepts a full uri path", () => {
    expect(
      isAnyServiceFile({
        path: "/workspace/svc-1/svc-1.implemented-service.qip.yaml",
      }),
    ).toBe(true);
  });
});

describe("serviceExtensionForType", () => {
  it.each([
    [IntegrationSystemType.EXTERNAL, ".service.qip.yaml"],
    [IntegrationSystemType.INTERNAL, ".service.qip.yaml"],
    [IntegrationSystemType.IMPLEMENTED, ".service.qip.yaml"],
    [IntegrationSystemType.CONTEXT, ".context-service.qip.yaml"],
    [IntegrationSystemType.MCP, ".mcp-service.qip.yaml"],
  ])("writes a %s service under %s", (type, extension) => {
    expect(serviceExtensionForType(type, qip)).toBe(extension);
  });

  it("substitutes the app name of the extensions it is handed", () => {
    expect(serviceExtensionForType(IntegrationSystemType.INTERNAL, acme)).toBe(
      ".service.acme.yaml",
    );
  });

  // `toString` and friends are inherited by every object literal. Reading the type map with `in`
  // let them through, and the name a file was then written under was `svc-1undefined`, with the
  // original deleted right after it.
  it.each([
    undefined,
    "",
    "SOMETHING_ELSE",
    "toString",
    "constructor",
    "valueOf",
    "hasOwnProperty",
    "__proto__",
  ])("falls back to the legacy extension for the type %p", (type) => {
    expect(serviceExtensionForType(type, qip)).toBe(".service.qip.yaml");
  });

  // The name a write emits states the kind of document, not the type. Every one of them has to be
  // a current name, or the next read lists the file as a leftover of a format nobody writes.
  it("writes every type under a current name", () => {
    for (const type of Object.values(IntegrationSystemType)) {
      const name = `svc-1${serviceExtensionForType(type, qip)}`;

      expect(isCurrentFormatServiceName(name, qip)).toBe(true);
      expect(isServiceFileOfAnyKind(name, qip)).toBe(true);
    }
  });
});

// One precedence rule for every read site: `$schema` wins, the body is the pre-#553 fallback.
// Inverting it would file a converted service under whatever type its stale body still claims.
describe("resolveServiceType", () => {
  const defaults: ServiceSchemaUrls = URN_SCHEMA_URLS;

  it("takes the type from $schema when the document states one", () => {
    expect(
      resolveServiceType(
        "svc-1.service.qip.yaml",
        {
          $schema: "urn:internal",
          content: { integrationSystemType: "EXTERNAL" },
        },
        defaults,
      ),
    ).toBe(IntegrationSystemType.INTERNAL);
  });

  it("falls back to the body for a pre-#553 document", () => {
    expect(
      resolveServiceType(
        "svc-1.service.qip.yaml",
        {
          $schema: "urn:service",
          content: { integrationSystemType: "IMPLEMENTED" },
        },
        defaults,
      ),
    ).toBe(IntegrationSystemType.IMPLEMENTED);
  });

  // A rehosted-and-renamed schema states nothing through either layer, so the body still decides —
  // hardening an unknown `$schema` into a refusal would break importing exactly that document.
  it("falls back to the body when $schema matches nothing", () => {
    expect(
      resolveServiceType(
        "svc-1.service.qip.yaml",
        {
          $schema: "https://x/renamed.schema.yaml",
          content: { integrationSystemType: "EXTERNAL" },
        },
        defaults,
      ),
    ).toBe(IntegrationSystemType.EXTERNAL);
  });

  // The name is not a source. A file still wearing a per-type name states its type in `$schema`
  // like any other, and one that does not states none.
  it("reads no type off a per-type file name", () => {
    expect(
      resolveServiceType(
        "svc-1.internal-service.qip.yaml",
        { content: {} },
        defaults,
      ),
    ).toBeUndefined();
  });

  it.each([[{ content: {} }], [{}], [undefined]])(
    "reads a document with no type in either place as untyped",
    (service) => {
      expect(
        resolveServiceType("svc-1.service.qip.yaml", service as any, defaults),
      ).toBeUndefined();
    },
  );

  it("reads a body type it does not recognize as untyped", () => {
    expect(
      resolveServiceType(
        "svc-1.service.qip.yaml",
        { content: { integrationSystemType: "NONSENSE" } },
        defaults,
      ),
    ).toBeUndefined();
  });

  it("uses the schema urls it is handed instead of resolving them", () => {
    const elsewhere: ServiceSchemaUrls = {
      ...defaults,
      externalService: "urn:x",
    };

    expect(
      resolveServiceType(
        "svc-1.service.qip.yaml",
        { $schema: "urn:x" },
        elsewhere,
      ),
    ).toBe(IntegrationSystemType.EXTERNAL);
    expect(
      resolveServiceType(
        "svc-1.service.qip.yaml",
        { $schema: "urn:x" },
        defaults,
      ),
    ).toBeUndefined();
  });
});

describe("plainServiceExtensions", () => {
  it("lists the current extension ahead of the per-type ones", () => {
    expect(plainServiceExtensions(qip)).toEqual([
      ".service.qip.yaml",
      ".external-service.qip.yaml",
      ".internal-service.qip.yaml",
      ".implemented-service.qip.yaml",
    ]);
  });

  it("covers exactly what isAnyServiceFile accepts", () => {
    for (const extension of plainServiceExtensions(qip)) {
      expect(isAnyServiceFile(`svc-1${extension}`)).toBe(true);
    }
    expect(plainServiceExtensions(qip)).not.toContain(qip.contextService);
    expect(plainServiceExtensions(qip)).not.toContain(qip.mcpService);
  });
});

describe("allServiceExtensions", () => {
  it("lists every service extension, current names ahead of the per-type ones", () => {
    expect(allServiceExtensions(qip)).toEqual([
      ".service.qip.yaml",
      ".context-service.qip.yaml",
      ".mcp-service.qip.yaml",
      ".external-service.qip.yaml",
      ".internal-service.qip.yaml",
      ".implemented-service.qip.yaml",
    ]);
  });
});

// The name a conversion writes. Only the extension moves: the base name is the id the service
// folder is also named after, and the backend finds a converted dotted-id service through that
// folder alone.
describe("serviceFileNameForType", () => {
  it.each([
    IntegrationSystemType.EXTERNAL,
    IntegrationSystemType.INTERNAL,
    IntegrationSystemType.IMPLEMENTED,
  ])("converts a per-type file of type %s back to the plain name", (type) => {
    expect(
      serviceFileNameForType("svc-1.internal-service.qip.yaml", type, qip),
    ).toBe("svc-1.service.qip.yaml");
  });

  // The backend claims a context or MCP document by exact URI plus its own name, so a promoted
  // file is one it refuses — and the plain-service read paths would stop finding the id.
  it.each([IntegrationSystemType.CONTEXT, IntegrationSystemType.MCP])(
    "never renames a plain file into the %s family",
    (type) => {
      expect(serviceFileNameForType("svc-1.service.qip.yaml", type, qip)).toBe(
        "svc-1.service.qip.yaml",
      );
    },
  );

  it.each([
    [IntegrationSystemType.CONTEXT, "svc-1.context-service.qip.yaml"],
    [IntegrationSystemType.MCP, "svc-1.mcp-service.qip.yaml"],
  ])("writes a %s document back where it is", (type, name) => {
    expect(serviceFileNameForType(name, type, qip)).toBe(name);
  });

  it("never renames a per-type file across families", () => {
    expect(
      serviceFileNameForType(
        "svc-1.external-service.qip.yaml",
        IntegrationSystemType.CONTEXT,
        qip,
      ),
    ).toBe("svc-1.external-service.qip.yaml");
  });

  // The backend reads the id up to the first dot, so `a.b.service.qip.yaml` states the id `a`.
  // Renaming such a file states a different id again, so it is left exactly where it is.
  it.each([
    "/services/a.b/a.b.service.qip.yaml",
    "a.b.internal-service.qip.yaml",
  ])("leaves the dotted-id name %s where it is", (name) => {
    expect(
      serviceFileNameForType(name, IntegrationSystemType.EXTERNAL, qip),
    ).toBe(name.split("/").pop());
  });

  it("returns the same name when the file is already the current one", () => {
    expect(
      serviceFileNameForType(
        "svc-1.service.qip.yaml",
        IntegrationSystemType.INTERNAL,
        qip,
      ),
    ).toBe("svc-1.service.qip.yaml");
  });

  // A file that resolves no type is left exactly where it is. Renaming it would rest on a guess,
  // and for a context or MCP document carrying no `$schema` the guess deletes the only file the
  // backend reads as that kind.
  it.each([
    "svc-1.service.qip.yaml",
    "svc-1.external-service.qip.yaml",
    "svc-1.context-service.qip.yaml",
  ])("leaves %s alone when no type is stated", (name) => {
    expect(serviceFileNameForType(name, "", qip)).toBe(name);
    expect(serviceFileNameForType(name, undefined, qip)).toBe(name);
  });

  it("leaves a file that is not a service file alone", () => {
    expect(
      serviceFileNameForType("notes.md", IntegrationSystemType.EXTERNAL, qip),
    ).toBe("notes.md");
  });

  it("uses the extensions it is handed rather than the default app name", () => {
    expect(
      serviceFileNameForType(
        "svc-1.external-service.acme.yaml",
        IntegrationSystemType.EXTERNAL,
        acme,
      ),
    ).toBe("svc-1.service.acme.yaml");
  });
});

describe("serviceSchemaUrlForType", () => {
  const schemaUrls: ServiceSchemaUrls = URN_SCHEMA_URLS;

  it.each([
    [IntegrationSystemType.EXTERNAL, "urn:external"],
    [IntegrationSystemType.INTERNAL, "urn:internal"],
    [IntegrationSystemType.IMPLEMENTED, "urn:implemented"],
    [IntegrationSystemType.CONTEXT, "urn:context"],
    [IntegrationSystemType.MCP, "urn:mcp"],
  ])("pairs %s with its own schema", (type, expected) => {
    expect(serviceSchemaUrlForType(type, schemaUrls)).toBe(expected);
  });

  it("falls back to the legacy schema for an unstated type", () => {
    expect(serviceSchemaUrlForType(undefined, schemaUrls)).toBe("urn:service");
    expect(serviceSchemaUrlForType("PARTNER", schemaUrls)).toBe("urn:service");
  });

  it.each(["toString", "constructor", "valueOf", "__proto__"])(
    "does not read the inherited key %p as a type",
    (type) => {
      expect(serviceSchemaUrlForType(type, schemaUrls)).toBe("urn:service");
      expect(serviceFileNameForType("svc-1.service.qip.yaml", type, qip)).toBe(
        "svc-1.service.qip.yaml",
      );
    },
  );
});

// Which of two files a half-converted service is listed from. A per-type name is a leftover of
// #553, so the plain one wins even though both are discovered.
describe("isCurrentFormatServiceName", () => {
  it.each([
    "svc-1.service.qip.yaml",
    "a.b.service.qip.yaml",
    "svc-1.context-service.qip.yaml",
    "svc-1.mcp-service.qip.yaml",
    "service-orders.service.qip.yaml",
  ])("accepts the current name %s", (name) => {
    expect(isCurrentFormatServiceName(name, qip)).toBe(true);
  });

  it.each([
    "svc-1.external-service.qip.yaml",
    "svc-1.internal-service.qip.yaml",
    "svc-1.implemented-service.qip.yaml",
    "svc-1.chain.qip.yaml",
    "notes.md",
  ])("refuses %s", (name) => {
    expect(isCurrentFormatServiceName(name, qip)).toBe(false);
  });
});

// What a read holding no id recovers a deleted path by. A dotted id keeps the legacy name, so the
// whole name up to the extension is the id — not the first segment the backend reads a postfix from.
describe("serviceIdFromFileName", () => {
  it.each([
    ["svc-1.external-service.qip.yaml", "svc-1"],
    ["svc-1.service.qip.yaml", "svc-1"],
    ["ctx-1.context-service.qip.yaml", "ctx-1"],
    ["mcp-1.mcp-service.qip.yaml", "mcp-1"],
    ["a.b.service.qip.yaml", "a.b"],
  ])("reads the id %s states", (name, id) => {
    expect(serviceIdFromFileName(name, qip)).toBe(id);
  });

  it("reads the id from a full uri path", () => {
    expect(
      serviceIdFromFileName(
        { path: "/workspace/svc-1/svc-1.internal-service.qip.yaml" },
        qip,
      ),
    ).toBe("svc-1");
  });

  it.each([
    "svc-1.chain.qip.yaml",
    "notes.md",
    ".service.qip.yaml",
    "svc-1.external-service.acme.yaml",
  ])("reads no id off %s", (name) => {
    expect(serviceIdFromFileName(name, qip)).toBeUndefined();
  });
});

describe("isPlainServiceType", () => {
  it.each([
    IntegrationSystemType.EXTERNAL,
    IntegrationSystemType.INTERNAL,
    IntegrationSystemType.IMPLEMENTED,
  ])("accepts %s", (type) => {
    expect(isPlainServiceType(type)).toBe(true);
  });

  // These two are separate kinds of document, not plain-service types, and a plain service whose
  // body claims one must not be renamed into that family.
  it.each([
    IntegrationSystemType.CONTEXT,
    IntegrationSystemType.MCP,
    "",
    undefined,
    "toString",
  ])("refuses %p", (type) => {
    expect(isPlainServiceType(type as string | undefined)).toBe(false);
  });
});

// Declaration order settles which file wins when a service has several; which extension a name
// carries has to be the longest match. A project is free to configure names that nest.
describe("nested configured extensions", () => {
  const nested: ServiceExtensions = {
    ...qip,
    service: ".svc.yaml",
    externalService: ".ext.svc.yaml",
    internalService: ".internal.ext.svc.yaml",
    implementedService: ".impl.svc.yaml",
  };

  it("reads the id off the longest matching extension, not the first declared", () => {
    expect(serviceIdFromFileName("svc-1.internal.ext.svc.yaml", nested)).toBe(
      "svc-1",
    );
    expect(serviceIdFromFileName("svc-1.ext.svc.yaml", nested)).toBe("svc-1");
  });

  it("strips the longest matching extension when it renames a file", () => {
    expect(
      serviceFileNameForType(
        "svc-1.internal.ext.svc.yaml",
        IntegrationSystemType.IMPLEMENTED,
        nested,
      ),
    ).toBe("svc-1.svc.yaml");
  });

  // A per-type name that happens to end with the current extension is still the per-type name.
  // Reading it as current would let directory order, not precedence, pick a half-converted
  // service's file.
  it("classifies a nested per-type name by its longest match, not the current suffix", () => {
    expect(isCurrentFormatServiceName("svc-1.ext.svc.yaml", nested)).toBe(
      false,
    );
    expect(isCurrentFormatServiceName("svc-1.svc.yaml", nested)).toBe(true);
  });
});
