import { DocumentationService } from "../../../src/services/documentation/documentationService";

// Mock appConfig to avoid import.meta.env issues in Jest
jest.mock("../../../src/appConfig", () => ({
  getConfig: jest.fn(() => ({
    documentationRouteBase: "/doc",
    documentationAssetsBaseUrl: "/doc",
  })),
}));

describe("DocumentationService - Element Type Mapping", () => {
  let service: DocumentationService;

  beforeEach(() => {
    service = new DocumentationService();
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

  describe("openChainElementDocumentation", () => {
    test("opens documentation for valid element type", async () => {
      jest
        .spyOn(service, "loadPaths")
        .mockResolvedValue([
          "01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md",
        ]);

      const openPageSpy = jest.spyOn(service, "openPage").mockImplementation();

      await service.openChainElementDocumentation("http-trigger");

      expect(openPageSpy).toHaveBeenCalledWith(
        expect.stringContaining("http_trigger"),
      );
    });

    test("falls back to home for invalid element type", async () => {
      jest.spyOn(service, "loadPaths").mockResolvedValue([]);

      const openPageSpy = jest.spyOn(service, "openPage").mockImplementation();

      await service.openChainElementDocumentation("non-existent");

      expect(openPageSpy).toHaveBeenCalledWith(
        expect.stringContaining("not-found"),
      );
    });
  });
});
