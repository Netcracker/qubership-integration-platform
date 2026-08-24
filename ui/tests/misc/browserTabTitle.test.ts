import {
  extractChainId,
  getServicesListTabTitle,
  getStaticBrowserTabTitle,
  hasOpenApiOperationId,
  normalizeServicesHash,
  parseServiceRoute,
  resolveOperationTabTitle,
  OPERATION_INFO_TAB_TITLE,
} from "../../src/misc/browserTabTitle";

describe("browserTabTitle", () => {
  describe("getStaticBrowserTabTitle", () => {
    it("maps main and module list routes", () => {
      expect(getStaticBrowserTabTitle("/", "")).toBe("Chains");
      expect(getStaticBrowserTabTitle("/chains", "")).toBe("Chains");
      expect(getStaticBrowserTabTitle("/doc/overview", "")).toBe("Helper");
      expect(getStaticBrowserTabTitle("/admintools", "")).toBe("Admin Tools");
      expect(getStaticBrowserTabTitle("/devtools", "")).toBe("Dev Tools");
    });

    it("maps services list tabs from hash", () => {
      expect(getStaticBrowserTabTitle("/services", "external")).toBe(
        "External Services",
      );
      expect(getStaticBrowserTabTitle("/services", "internal")).toBe(
        "Inner Services",
      );
      expect(getStaticBrowserTabTitle("/services", "implemented")).toBe(
        "Implemented Services",
      );
    });

    it("maps admin and dev tool sections", () => {
      expect(getStaticBrowserTabTitle("/admintools/domains", "")).toBe(
        "Domains",
      );
      expect(getStaticBrowserTabTitle("/admintools/variables/common", "")).toBe(
        "Common Variables",
      );
      expect(getStaticBrowserTabTitle("/admintools/access-control", "")).toBe(
        "Roles",
      );
      expect(getStaticBrowserTabTitle("/devtools/maas/kafka", "")).toBe(
        "Kafka",
      );
      expect(
        getStaticBrowserTabTitle("/devtools/diagnostic/validations", ""),
      ).toBe("Diagnostic");
    });

    it("returns null for entity routes resolved asynchronously", () => {
      expect(
        getStaticBrowserTabTitle("/chains/chain-1/snapshots", ""),
      ).toBeNull();
      expect(
        getStaticBrowserTabTitle(
          "/services/systems/svc-1/parameters",
          "",
        ),
      ).toBeNull();
    });
  });

  describe("parseServiceRoute", () => {
    it("ignores inner service tabs in the path shape", () => {
      const parameters = parseServiceRoute(
        "/services/systems/svc-1/parameters",
      );
      const environments = parseServiceRoute(
        "/services/systems/svc-1/environments",
      );
      expect(parameters).toEqual({
        variant: "systems",
        systemId: "svc-1",
      });
      expect(environments).toEqual({
        variant: "systems",
        systemId: "svc-1",
      });
    });

    it("parses nested specification entities", () => {
      expect(
        parseServiceRoute(
          "/services/systems/svc-1/specificationGroups/grp-1/specifications/spec-1/operations/op-1",
        ),
      ).toEqual({
        variant: "systems",
        systemId: "svc-1",
        groupId: "grp-1",
        specId: "spec-1",
        operationId: "op-1",
      });
    });
  });

  describe("extractChainId", () => {
    it("extracts chain id and skips diff view", () => {
      expect(extractChainId("/chains/my-chain/graph")).toBe("my-chain");
      expect(extractChainId("/chains/diff")).toBeNull();
    });
  });

  describe("operation title rules", () => {
    it("uses Operation info when OpenAPI operationId is missing", () => {
      expect(
        resolveOperationTabTitle("List pets", { method: "get" }),
      ).toBe(OPERATION_INFO_TAB_TITLE);
      expect(hasOpenApiOperationId({ operationId: "listPets" })).toBe(true);
      expect(
        resolveOperationTabTitle("List pets", {
          operationId: "listPets",
        }),
      ).toBe("List pets");
    });
  });

  describe("normalizeServicesHash", () => {
    it("defaults to external", () => {
      expect(normalizeServicesHash("")).toBe("external");
      expect(getServicesListTabTitle("#internal")).toBe("Inner Services");
    });
  });
});
