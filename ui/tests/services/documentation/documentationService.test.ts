/**
 * @jest-environment jsdom
 */

// Mock lunr-init (must be before service import)
jest.mock("../../../src/lunr-init", () => ({ lunr: {} }));

// Create a minimal elasticlunr mock with real Index-like behavior
const createMockIndex = () => {
  const docs: Record<string, any> = {};
  const fields: string[] = [];
  return {
    setRef: jest.fn(),
    addField: (f: string) => fields.push(f),
    saveDocument: jest.fn(),
    addDoc: (doc: any) => {
      docs[String(doc.id ?? doc.ref)] = doc;
    },
    search: jest.fn(() => []),
    documentStore: {
      getDoc: (ref: string) => docs[ref] || null,
      docs,
    },
    toJSON: () => ({}),
  };
};

const mockElasticlunr: any = Object.assign(
  (setup?: (this: any) => void) => {
    const idx = createMockIndex();
    if (setup) setup.call(idx);
    return idx;
  },
  {
    Index: {
      load: (dump: any) => {
        const idx = createMockIndex();
        if (dump?._docs) {
          Object.entries(dump._docs).forEach(([k, v]) => {
            idx.documentStore.docs[k] = v;
          });
        }
        idx.search = jest.fn(() =>
          Object.keys(idx.documentStore.docs).map((ref) => ({ ref, score: 1 })),
        );
        return idx;
      },
    },
    stemmer: (w: string) => w.toLowerCase(),
  },
);

jest.mock("elasticlunr", () => ({
  __esModule: true,
  default: mockElasticlunr,
}));

import { DocumentationService } from "../../../src/services/documentation/documentationService";

// Mock appConfig to avoid import.meta.env issues in Jest
jest.mock("../../../src/appConfig", () => ({
  getConfig: jest.fn(() => ({
    documentationBaseUrl: "/doc",
  })),
  onConfigChange: jest.fn(() => () => {}),
}));

describe("DocumentationService - Resource Loading", () => {
  let service: DocumentationService;
  let mockFetch: jest.Mock;

  beforeEach(() => {
    mockFetch = jest.fn();
    service = new DocumentationService(mockFetch, jest.fn());
  });

  test("loadPaths fetches and caches paths.json", async () => {
    const mockPaths = ["00__Overview/overview.md", "01__Chains/chains.md"];
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: () => Promise.resolve(mockPaths),
    });

    const result = await service.loadPaths();
    expect(result).toEqual(mockPaths);
    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringContaining("paths.json"),
    );

    // Second call uses cache
    await service.loadPaths();
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });

  test("loadNames fetches and caches names.json", async () => {
    const mockNames = [["Overview"], ["Chains"]];
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: () => Promise.resolve(mockNames),
    });

    const result = await service.loadNames();
    expect(result).toEqual(mockNames);
    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringContaining("names.json"),
    );
  });

  test("loadTOC fetches and caches toc.json", async () => {
    const mockTOC = { title: "", children: [] };
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: () => Promise.resolve(mockTOC),
    });

    const result = await service.loadTOC();
    expect(result).toEqual(mockTOC);
    expect(mockFetch).toHaveBeenCalledWith(expect.stringContaining("toc.json"));
  });

  test("loadResource throws on non-ok response", async () => {
    mockFetch.mockResolvedValue({
      ok: false,
      statusText: "Not Found",
      headers: new Headers(),
    });

    await expect(service.loadPaths()).rejects.toThrow("Failed to load");
  });

  test("loadResource throws on HTML response (SPA fallback)", async () => {
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "text/html" }),
      text: () => Promise.resolve("<!DOCTYPE html><html></html>"),
    });

    await expect(service.loadPaths()).rejects.toThrow(
      "received HTML instead of JSON",
    );
  });

  test("loadSearchIndex fetches and loads search index", async () => {
    const indexDump = {
      _docs: { "0": { id: 0, title: "Test", body: "Content" } },
    };
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: () => Promise.resolve(indexDump),
    });

    const index = await service.loadSearchIndex();
    expect(index).toBeDefined();
    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringContaining("search-index.json"),
    );

    // Cache hit
    await service.loadSearchIndex();
    expect(mockFetch).toHaveBeenCalledTimes(1);
  });

  test("search returns scored results", async () => {
    const indexDump = {
      _docs: {
        "0": { id: 0, title: "HTTP Trigger", body: "Handles HTTP requests" },
      },
    };
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: () => Promise.resolve(indexDump),
    });

    const results = await service.search("HTTP");
    expect(results.length).toBeGreaterThan(0);
    expect(results[0]).toHaveProperty("ref");
    expect(results[0]).toHaveProperty("score");
  });

  test("getSearchDetailSegments returns empty for missing doc", async () => {
    const indexDump = {
      _docs: { "0": { id: 0, title: "Test", body: "Content" } },
    };
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: () => Promise.resolve(indexDump),
    });

    const segments = await service.getSearchDetailSegments(999, "test");
    expect(segments).toEqual([]);
  });

  test("getSearchDetailSegments returns segments for existing doc", async () => {
    const indexDump = {
      _docs: {
        "0": {
          id: 0,
          title: "HTTP Trigger",
          body: "The HTTP trigger handles requests.\n\nIt supports GET and POST.",
        },
      },
    };
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: () => Promise.resolve(indexDump),
    });

    const segments = await service.getSearchDetailSegments(0, "HTTP");
    expect(Array.isArray(segments)).toBe(true);
  });

  test("getSearchDetail returns HTML strings", async () => {
    const indexDump = {
      _docs: {
        "0": {
          id: 0,
          title: "Test",
          body: "Some test content.\n\nMore test data.",
        },
      },
    };
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: () => Promise.resolve(indexDump),
    });

    const details = await service.getSearchDetail(0, "test");
    expect(Array.isArray(details)).toBe(true);
  });

  test("loadContextMapping fetches context-doc-mapping.json", async () => {
    const mockRules = [{ pattern: "^/chains", doc: "/doc/chains" }];
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: () => Promise.resolve(mockRules),
    });

    const result = await service.loadContextMapping();
    expect(result).toEqual(mockRules);
    expect(mockFetch).toHaveBeenCalledWith(
      expect.stringContaining("context-doc-mapping.json"),
    );
  });

  test("resetCaches allows re-fetching after clear", async () => {
    const mockPaths = ["00__Overview/overview.md"];
    mockFetch.mockResolvedValue({
      ok: true,
      headers: new Headers({ "content-type": "application/json" }),
      json: () => Promise.resolve(mockPaths),
    });

    await service.loadPaths();
    expect(mockFetch).toHaveBeenCalledTimes(1);

    service.resetCaches();

    await service.loadPaths();
    expect(mockFetch).toHaveBeenCalledTimes(2);
  });
});

describe("DocumentationService - Element Type Mapping", () => {
  let service: DocumentationService;
  let mockFetch: jest.Mock;
  let mockWindowOpen: jest.Mock;

  beforeEach(() => {
    // Mock fetch
    mockFetch = jest.fn();
    // Mock window.open
    mockWindowOpen = jest.fn();

    service = new DocumentationService(mockFetch, mockWindowOpen);
    // Clear cache between tests
    service.elementTypeMappingCache = null;
  });

  describe("buildElementTypeMapping", () => {
    test("extracts element types from file names", async () => {
      // Mock loadPaths
      const mockPaths = [
        "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/4__HTTP_Sender/http_sender.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/mapper.md",
      ];

      jest.spyOn(service, "loadPaths").mockResolvedValue(mockPaths);

      // Access private method for testing
      const mapping = await service.buildElementTypeMapping();

      expect(mapping["http-trigger"]).toBeDefined();
      expect(mapping["http-sender"]).toBeDefined();
      expect(mapping["mapper"]).toBeDefined();

      expect(mapping["http-trigger"]).toContain("http_trigger");
      expect(mapping["http-sender"]).toContain("http_sender");
      expect(mapping["mapper"]).toContain("mapper");
    });

    test("extracts element types from folder names", async () => {
      const mockPaths = [
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/5__Condition/condition.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/2__Files/2__File_Read/file_read.md",
      ];

      jest.spyOn(service, "loadPaths").mockResolvedValue(mockPaths);

      const mapping = await service.buildElementTypeMapping();

      // Both from file name and folder name
      expect(mapping["condition"]).toBeDefined();
      expect(mapping["file-read"]).toBeDefined();
    });

    test("ignores paths not in QIP_Elements_Library", async () => {
      const mockPaths = [
        "00__Overview/overview.md",
        "01__Chains/chains.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
      ];

      jest.spyOn(service, "loadPaths").mockResolvedValue(mockPaths);

      const mapping = await service.buildElementTypeMapping();

      expect(mapping["overview"]).toBeUndefined();
      expect(mapping["chains"]).toBeUndefined();
      expect(mapping["http-trigger"]).toBeDefined();
    });

    test("handles all standard element types", async () => {
      const mockPaths = [
        // Routing
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/1__Circuit_Breaker/circuit_breaker.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/2__Reuse/reuse.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/3__Log_Record/log_record.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/4__Split/split.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/5__Condition/condition.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/7__Reuse_Reference/reuse_reference.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/8__Loop/loop.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/9__Try-Catch-Finally/try-catch-finally.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/10__Split_Async/split_async.md",
        // Files
        "01__Chains/1__Graph/1__QIP_Elements_Library/2__Files/1__SFTP_Download/sftp_download.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/2__Files/2__File_Read/file_read.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/2__Files/3__File_Write/file_write.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/2__Files/4__SFTP_Upload/sftp_upload.md",
        // Transformation
        "01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/1__Script/script.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/2__Mapper/mapper.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/3__Headers_Modification/headers_modification.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/5__Transformation/4__XSLT/xslt.md",
        // Triggers
        "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/5__Scheduler/scheduler.md",
        // Senders
        "01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/4__HTTP_Sender/http_sender.md",
      ];

      jest.spyOn(service, "loadPaths").mockResolvedValue(mockPaths);

      const mapping = await service.buildElementTypeMapping();

      // Verify all standard types are present
      expect(mapping["circuit-breaker"]).toBeDefined();
      expect(mapping["reuse"]).toBeDefined();
      expect(mapping["log-record"]).toBeDefined();
      expect(mapping["split"]).toBeDefined();
      expect(mapping["condition"]).toBeDefined();
      expect(mapping["chain-call"]).toBeDefined();
      expect(mapping["reuse-reference"]).toBeDefined();
      expect(mapping["loop"]).toBeDefined();
      expect(mapping["try-catch-finally"]).toBeDefined();
      expect(mapping["split-async"]).toBeDefined();
      expect(mapping["file-read"]).toBeDefined();
      expect(mapping["file-write"]).toBeDefined();
      expect(mapping["script"]).toBeDefined();
      expect(mapping["mapper"]).toBeDefined();
      expect(mapping["headers-modification"]).toBeDefined();
      expect(mapping["http-trigger"]).toBeDefined();
      expect(mapping["scheduler"]).toBeDefined();
      expect(mapping["http-sender"]).toBeDefined();
    });
  });

  describe("getElementTypeAliases", () => {
    test("returns aliases for condition element", () => {
      const baseMapping = {
        condition: "/doc/.../condition",
      };

      const aliases = service.getElementTypeAliases(baseMapping);

      expect(aliases["else"]).toBe("/doc/.../condition");
      expect(aliases["if"]).toBe("/doc/.../condition");
    });

    test("returns aliases for try-catch-finally element", () => {
      const baseMapping = {
        "try-catch-finally": "/doc/.../try-catch-finally",
      };

      const aliases = service.getElementTypeAliases(baseMapping);

      expect(aliases["try"]).toBe("/doc/.../try-catch-finally");
      expect(aliases["catch"]).toBe("/doc/.../try-catch-finally");
      expect(aliases["finally"]).toBe("/doc/.../try-catch-finally");
    });

    test("returns aliases for headers modification", () => {
      const baseMapping = {
        "headers-modification": "/doc/.../headers_modification",
      };

      const aliases = service.getElementTypeAliases(baseMapping);

      expect(aliases["header-modification"]).toBe(
        "/doc/.../headers_modification",
      );
    });

    test("returns aliases for asyncapi trigger", () => {
      const baseMapping = {
        "asyncapi-trigger": "/doc/.../asyncapi_trigger",
      };

      const aliases = service.getElementTypeAliases(baseMapping);

      expect(aliases["async-api-trigger"]).toBe("/doc/.../asyncapi_trigger");
    });

    test("returns aliases for scheduler", () => {
      const baseMapping = {
        scheduler: "/doc/.../scheduler",
      };

      const aliases = service.getElementTypeAliases(baseMapping);

      expect(aliases["quartz"]).toBe("/doc/.../scheduler");
      expect(aliases["quartz-scheduler"]).toBe("/doc/.../scheduler");
    });

    test("returns empty object when base mapping is empty", () => {
      const baseMapping = {};

      const aliases = service.getElementTypeAliases(baseMapping);

      expect(Object.keys(aliases).length).toBe(0);
    });
  });

  describe("mapPathByElementType", () => {
    test("finds standard elements", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        ]);

      const path = await service.mapPathByElementType("http-trigger");

      expect(path).toContain("http_trigger");
      expect(path).not.toContain("not-found");
    });

    test("finds aliased elements - condition", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/5__Condition/condition.md",
        ]);

      const pathElse = await service.mapPathByElementType("else");
      const pathIf = await service.mapPathByElementType("if");

      expect(pathElse).toContain("condition");
      expect(pathIf).toContain("condition");
    });

    test("finds aliased elements - try-catch-finally", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/9__Try-Catch-Finally/try-catch-finally.md",
        ]);

      const pathTry = await service.mapPathByElementType("try");
      const pathCatch = await service.mapPathByElementType("catch");
      const pathFinally = await service.mapPathByElementType("finally");

      expect(pathTry).toContain("try-catch-finally");
      expect(pathCatch).toContain("try-catch-finally");
      expect(pathFinally).toContain("try-catch-finally");
    });

    test("finds aliased elements - scheduler", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/5__Scheduler/scheduler.md",
        ]);

      const pathQuartz = await service.mapPathByElementType("quartz");

      expect(pathQuartz).toContain("scheduler");
    });

    test("returns not-found for unknown elements", async () => {
      jest.spyOn(service, "loadPaths").mockResolvedValue([]);

      const path = await service.mapPathByElementType("non-existent-element");

      expect(path).toContain("not-found");
    });

    test("uses cache on subsequent calls", async () => {
      const loadPathsSpy = jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        ]);

      await service.mapPathByElementType("http-trigger");
      await service.mapPathByElementType("http-trigger");

      // loadPaths should be called only once due to caching
      expect(loadPathsSpy).toHaveBeenCalledTimes(1);
    });
  });

  describe("loadElementMapping", () => {
    test("returns DocumentMappingRule array", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        ]);

      const rules = await service.loadElementMapping();

      expect(Array.isArray(rules)).toBe(true);
      expect(rules.length).toBeGreaterThan(0);
      expect(rules[0]).toHaveProperty("pattern");
      expect(rules[0]).toHaveProperty("doc");
    });

    test("generates exact match patterns", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        ]);

      const rules = await service.loadElementMapping();
      const httpTriggerRule = rules.find((r) => r.doc.includes("http_trigger"));

      expect(httpTriggerRule).toBeDefined();
      expect(httpTriggerRule?.pattern).toMatch(/^\^.*\$$/); // Pattern should start with ^ and end with $
    });
  });

  describe("resetCaches", () => {
    test("clears all cached promises", async () => {
      const loadPathsSpy = jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        ]);

      // Populate caches
      await service.buildElementTypeMapping();
      expect(loadPathsSpy).toHaveBeenCalledTimes(1);

      // Reset and call again — should re-fetch
      service.resetCaches();
      await service.buildElementTypeMapping();
      expect(loadPathsSpy).toHaveBeenCalledTimes(2);
    });

    test("clears elementTypeMappingCache", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        ]);

      await service.buildElementTypeMapping();
      expect(service.elementTypeMappingCache).not.toBeNull();

      service.resetCaches();
      expect(service.elementTypeMappingCache).toBeNull();
    });
  });

  describe("openChainElementDocumentation", () => {
    test("opens documentation for valid element type", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        ]);

      await service.openChainElementDocumentation("http-trigger");

      expect(mockWindowOpen).toHaveBeenCalledWith(
        expect.stringContaining("http_trigger"),
        "_blank",
      );
    });

    test("falls back to home for invalid element type", async () => {
      jest.spyOn(service, "loadPaths").mockResolvedValue([]);

      await service.openChainElementDocumentation("non-existent");

      expect(mockWindowOpen).toHaveBeenCalledWith(
        expect.stringContaining("not-found"),
        "_blank",
      );
    });

    test("calls onError callback on failure", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockRejectedValue(new Error("Network error"));

      const onErrorMock = jest.fn();

      await service.openChainElementDocumentation("http-trigger", onErrorMock);

      expect(onErrorMock).toHaveBeenCalledWith(expect.any(Error));
      expect(mockWindowOpen).toHaveBeenCalledWith(
        expect.stringContaining("/doc"),
        "_blank",
      );
    });
  });

  describe("openContextDocumentation", () => {
    test("opens mapped documentation based on current path", () => {
      const mockRules = [
        { pattern: "^/chains", doc: "/doc/01__Chains/chains" },
      ];
      mockFetch.mockResolvedValue({
        ok: true,
        headers: new Headers({ "content-type": "application/json" }),
        json: () => Promise.resolve(mockRules),
      });

      Object.defineProperty(window, "location", {
        value: { pathname: "/chains", hash: "" },
        writable: true,
      });

      service.openContextDocumentation();

      return new Promise<void>((resolve) => {
        setTimeout(() => {
          expect(mockWindowOpen).toHaveBeenCalledWith(
            expect.stringContaining("01__Chains"),
            "_blank",
          );
          resolve();
        }, 50);
      });
    });

    test("calls onError and falls back on failure", () => {
      mockFetch.mockRejectedValue(new Error("Network fail"));

      Object.defineProperty(window, "location", {
        value: { pathname: "/test", hash: "" },
        writable: true,
      });

      const onErrorMock = jest.fn();
      service.openContextDocumentation(onErrorMock);

      return new Promise<void>((resolve) => {
        setTimeout(() => {
          expect(onErrorMock).toHaveBeenCalledWith(expect.any(Error));
          expect(mockWindowOpen).toHaveBeenCalledWith("/doc", "_blank");
          resolve();
        }, 50);
      });
    });
  });

  describe("mapElementToDoc", () => {
    test("returns doc path for known element", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        ]);

      const result = await service.mapElementToDoc("http-trigger");
      expect(result).toContain("http_trigger");
    });

    test("returns null for unknown element", async () => {
      jest.spyOn(service, "loadPaths").mockResolvedValue([]);

      const result = await service.mapElementToDoc("unknown");
      expect(result).toBeNull();
    });
  });

  describe("mapContextToDoc", () => {
    test("returns doc path for matching context", async () => {
      const mockRules = [
        { pattern: "^/chains", doc: "/doc/01__Chains/chains" },
      ];
      mockFetch.mockResolvedValue({
        ok: true,
        headers: new Headers({ "content-type": "application/json" }),
        json: () => Promise.resolve(mockRules),
      });

      const result = await service.mapContextToDoc("/chains");
      expect(result).toBe("/doc/01__Chains/chains");
    });

    test("returns null for non-matching context", async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        headers: new Headers({ "content-type": "application/json" }),
        json: () => Promise.resolve([]),
      });

      const result = await service.mapContextToDoc("/unknown");
      expect(result).toBeNull();
    });
  });

  describe("getDefaultDocumentPath", () => {
    test("returns first path when available", async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        headers: new Headers({ "content-type": "application/json" }),
        json: () =>
          Promise.resolve(["00__Overview/overview.md", "01__Chains/chains.md"]),
      });

      const result = await service.getDefaultDocumentPath();
      expect(result).toBe("00__Overview/overview.md");
    });

    test("returns empty string when no paths", async () => {
      mockFetch.mockResolvedValue({
        ok: true,
        headers: new Headers({ "content-type": "application/json" }),
        json: () => Promise.resolve([]),
      });

      const result = await service.getDefaultDocumentPath();
      expect(result).toBe("");
    });
  });

  describe("openPage", () => {
    test("opens URL in new tab", () => {
      service.openPage("/doc/test");
      expect(mockWindowOpen).toHaveBeenCalledWith("/doc/test", "_blank");
    });
  });
});
