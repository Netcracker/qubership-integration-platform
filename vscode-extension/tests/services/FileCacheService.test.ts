// Covers the `api` arm added across FileCacheService's three dispatchers
// (setFileUri / getFileUri / invalidateByUri) plus clearAll and the direct
// apiCache accessors. Without a matching arm an `.api.<app>.yaml` lookup would
// never cache and every read would fall back to a full workspace scan.

const getConfig = jest.fn();

jest.mock("../../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: { getConfig: (...args: unknown[]) => getConfig(...args) },
}));

import { FileCacheService } from "../../src/web/services/FileCacheService";

const EXTENSIONS = {
  service: ".service.qip.yaml",
  externalService: ".external-service.qip.yaml",
  internalService: ".internal-service.qip.yaml",
  implementedService: ".implemented-service.qip.yaml",
  contextService: ".context-service.qip.yaml",
  mcpService: ".mcp-service.qip.yaml",
  chain: ".chain.qip.yaml",
  specificationGroup: ".specification-group.qip.yaml",
  apiGroup: ".api-group.qip.yaml",
  specification: ".specification.qip.yaml",
  api: ".api.qip.yaml",
};

const API_EXT = EXTENSIONS.api;
const SPEC_EXT = EXTENSIONS.specification;
const SPEC_GROUP_EXT = EXTENSIONS.specificationGroup;
const API_GROUP_EXT = EXTENSIONS.apiGroup;

function uri(path: string): any {
  return { path, toString: () => path };
}

const cache = FileCacheService.getInstance();

beforeEach(() => {
  getConfig.mockReturnValue({ extensions: EXTENSIONS });
  cache.clearAll();
});

describe("FileCacheService api arm", () => {
  test("setFileUri / getFileUri round-trip through the api cache", () => {
    const apiUri = uri("/svc/model-1.api.qip.yaml");
    cache.setFileUri("api-1", API_EXT, apiUri);

    expect(cache.getFileUri("api-1", API_EXT)).toBe(apiUri);
    // The direct accessor sees the same entry.
    expect(cache.getApiUri("api-1")).toBe(apiUri);
  });

  test("invalidateByUri drops the api entry so the next read misses", () => {
    const apiUri = uri("/svc/model-1.api.qip.yaml");
    cache.setFileUri("api-1", API_EXT, apiUri);
    expect(cache.getFileUri("api-1", API_EXT)).toBe(apiUri);

    cache.invalidateByUri(apiUri);

    expect(cache.getFileUri("api-1", API_EXT)).toBeNull();
    expect(cache.getApiUri("api-1")).toBeNull();
  });

  test("an api uri is not mistaken for a specification uri", () => {
    const apiUri = uri("/svc/model-1.api.qip.yaml");
    cache.setFileUri("api-1", API_EXT, apiUri);

    // The specification dispatcher arm holds nothing for this id.
    expect(cache.getFileUri("api-1", SPEC_EXT)).toBeNull();
    // Invalidating by a specification uri leaves the api entry intact.
    cache.invalidateByUri(uri("/svc/model-9.specification.qip.yaml"));
    expect(cache.getFileUri("api-1", API_EXT)).toBe(apiUri);
  });

  test("direct apiCache accessors set, get, and invalidate", () => {
    const apiUri = uri("/svc/model-2.api.qip.yaml");
    cache.setApiUri("api-2", apiUri);
    expect(cache.getApiUri("api-2")).toBe(apiUri);

    cache.invalidateApi("api-2");
    expect(cache.getApiUri("api-2")).toBeNull();
  });

  test("clearAll empties the api cache", () => {
    cache.setApiUri("api-3", uri("/svc/model-3.api.qip.yaml"));
    expect(cache.getApiUri("api-3")).not.toBeNull();

    cache.clearAll();

    expect(cache.getApiUri("api-3")).toBeNull();
  });

  test("clearApiCache empties only the api cache", () => {
    cache.setApiUri("api-4", uri("/svc/model-4.api.qip.yaml"));
    cache.setFileUri("spec-4", SPEC_EXT, uri("/svc/model-4.specification.qip.yaml"));

    cache.clearApiCache();

    expect(cache.getApiUri("api-4")).toBeNull();
    expect(cache.getFileUri("spec-4", SPEC_EXT)).not.toBeNull();
  });
});

// The renamed apiGroup extension must share the specificationGroup cache: without both arms in
// setFileUri/getFileUri/invalidateByUri, a group written under the new extension never caches and
// every lookup falls back to a full workspace scan.
describe("FileCacheService apiGroup shares the specificationGroup cache", () => {
  test("setFileUri under the new extension is readable back under the same extension", () => {
    const groupUri = uri("/svc/group-1.api-group.qip.yaml");
    cache.setFileUri("group-1", API_GROUP_EXT, groupUri);

    expect(cache.getFileUri("group-1", API_GROUP_EXT)).toBe(groupUri);
    expect(cache.getSpecificationGroupUri("group-1")).toBe(groupUri);
  });

  test("a group cached under the old extension is also found by id through the direct accessor", () => {
    const groupUri = uri("/svc/group-2.specification-group.qip.yaml");
    cache.setFileUri("group-2", SPEC_GROUP_EXT, groupUri);

    expect(cache.getSpecificationGroupUri("group-2")).toBe(groupUri);
  });

  test("invalidateByUri drops a group cached under the new extension", () => {
    const groupUri = uri("/svc/group-3.api-group.qip.yaml");
    cache.setFileUri("group-3", API_GROUP_EXT, groupUri);

    cache.invalidateByUri(groupUri);

    expect(cache.getSpecificationGroupUri("group-3")).toBeNull();
  });

  test("clearSpecificationGroupCache empties entries cached under either extension", () => {
    cache.setFileUri("group-4", SPEC_GROUP_EXT, uri("/svc/group-4.specification-group.qip.yaml"));
    cache.setFileUri("group-5", API_GROUP_EXT, uri("/svc/group-5.api-group.qip.yaml"));

    cache.clearSpecificationGroupCache();

    expect(cache.getSpecificationGroupUri("group-4")).toBeNull();
    expect(cache.getSpecificationGroupUri("group-5")).toBeNull();
  });
});

// The three typed service extensions share the service cache with the legacy one. Without every arm
// a typed file never caches — every read falls back to a full workspace scan — and, worse, never
// invalidates, so an edit keeps serving the content read before it.
describe("FileCacheService plain-service extensions share one cache", () => {
  const TYPED = [
    EXTENSIONS.externalService,
    EXTENSIONS.internalService,
    EXTENSIONS.implementedService,
  ];

  test.each([...TYPED, EXTENSIONS.service])(
    "setFileUri / getFileUri round-trip through the service cache for %s",
    (extension) => {
      const serviceUri = uri(`/svc/svc-1${extension}`);
      cache.setFileUri("svc-1", extension, serviceUri);

      expect(cache.getFileUri("svc-1", extension)).toBe(serviceUri);
      expect(cache.getServiceUri("svc-1")).toBe(serviceUri);
    },
  );

  test.each(TYPED)("invalidateByUri drops the entry for a %s file", (extension) => {
    const serviceUri = uri(`/svc/svc-1${extension}`);
    cache.setFileUri("svc-1", extension, serviceUri);
    expect(cache.getServiceUri("svc-1")).toBe(serviceUri);

    cache.invalidateByUri(serviceUri);

    expect(cache.getServiceUri("svc-1")).toBeNull();
    expect(cache.getFileUri("svc-1", extension)).toBeNull();
  });

  test("a typed service uri is not mistaken for a context service", () => {
    const serviceUri = uri(`/svc/svc-1${EXTENSIONS.externalService}`);
    cache.setFileUri("svc-1", EXTENSIONS.externalService, serviceUri);
    cache.setFileUri("ctx-1", EXTENSIONS.contextService, uri(`/ctx/ctx-1${EXTENSIONS.contextService}`));

    cache.invalidateByUri(serviceUri);

    expect(cache.getServiceUri("svc-1")).toBeNull();
    expect(cache.getContextServiceUri("ctx-1")).not.toBeNull();
  });

  test("clearServiceCache empties entries cached under any plain-service extension", () => {
    cache.setFileUri("svc-1", EXTENSIONS.externalService, uri("/a/svc-1.external-service.qip.yaml"));
    cache.setFileUri("svc-2", EXTENSIONS.service, uri("/b/svc-2.service.qip.yaml"));

    cache.clearServiceCache();

    expect(cache.getServiceUri("svc-1")).toBeNull();
    expect(cache.getServiceUri("svc-2")).toBeNull();
  });

  // A project config from before the three keys existed still has to work.
  test("tolerates a config that carries only the legacy service extension", () => {
    getConfig.mockReturnValue({
      extensions: { ...EXTENSIONS, externalService: undefined, internalService: undefined, implementedService: undefined },
    });
    const serviceUri = uri("/svc/svc-1.service.qip.yaml");

    cache.setFileUri("svc-1", EXTENSIONS.service, serviceUri);
    expect(cache.getServiceUri("svc-1")).toBe(serviceUri);

    cache.invalidateByUri(serviceUri);
    expect(cache.getServiceUri("svc-1")).toBeNull();
  });
});
