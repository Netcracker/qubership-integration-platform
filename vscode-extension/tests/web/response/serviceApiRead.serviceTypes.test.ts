// The read side of the per-type service files. Every failure this covers is silent: a service read
// from a typed file used to come back with no type at all, which empties its environments tab and
// lets an import through that the protocol rules should refuse, and a converted service whose legacy
// sibling is still on disk used to be listed twice.

import { QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

jest.mock(
  "vscode",
  () => ({
    __esModule: true,
    Uri: {
      joinPath: jest.fn((_base: any, ...segments: string[]) => ({
        path: segments.join("/"),
        fsPath: segments.join("/"),
      })),
    },
  }),
  { virtual: true },
);

jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForUri: jest.fn(() => ext),
  getExtensionsForFile: jest.fn(() => ext),
  extractFilename: (fileRef: string | { path: string }) =>
    (typeof fileRef === "string" ? fileRef : fileRef.path).split("/").pop() ??
    "",
}));

jest.mock("../../../src/web/api-services/LabelUtils", () => ({
  LabelUtils: { toEntityLabels: jest.fn((labels: any[]) => labels ?? []) },
}));

jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });

const getMainService = jest.fn();
const findFileById = jest.fn();
const findFiles = jest.fn();
const getSpecificationGroupFiles = jest.fn().mockResolvedValue([]);
const getSpecificationFiles = jest.fn().mockResolvedValue([]);
const findAndBuildChainsRecursively = jest.fn().mockResolvedValue(undefined);
const getRootDirectory = jest.fn().mockReturnValue({ path: "/root" });
const parseFile = jest.fn();
const readFileContent = jest.fn().mockResolvedValue("raw source");
const getFileType = jest.fn();
// The real `getFileType` catches a missing path and answers UNKNOWN, so existence is asked of
// `fileExists`, which is `stat` alone. A double that rejects here would be a behaviour the
// implementation cannot produce.
const fileExists = jest.fn();

jest.mock("../../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    getMainService,
    findFileById,
    findFiles,
    getSpecificationGroupFiles,
    getSpecificationFiles,
    findAndBuildChainsRecursively,
    getRootDirectory,
    parseFile,
    readFileContent,
    getFileType,
    fileExists,
    getFileCreatedWhen: jest.fn().mockResolvedValue(0),
  },
}));

const parseContentFromFile = jest.fn();
jest.mock("../../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile },
}));

jest.mock(
  "../../../src/web/api-services/parsers/OperationSchemaExtractor",
  () => ({
    OperationSchemaExtractor: {
      extract: jest.fn().mockResolvedValue({
        specification: { summary: "derived" },
        requestSchema: { type: "object" },
        responseSchemas: { "200": { type: "object" } },
      }),
    },
  }),
);

import {
  getApiSpecifications,
  getCurrentServiceId,
  getEnvironment,
  getEnvironments,
  getOperationInfo,
  getOperations,
  getService,
  getServices,
  getSpecificationModel,
} from "../../../src/web/response/serviceApiRead";

const SERVICE_ID = "svc-1";

function uri(path: string): any {
  return { path, fsPath: path };
}

function serviceFile(extension: string): any {
  return uri(`/root/${SERVICE_ID}/${SERVICE_ID}${extension}`);
}

// Only the named extension is on disk; the rest reject the way findFileById does for a miss. Every
// read resolves the service by id, so a fixture that leaves the lookup unstubbed pins nothing.
function onlyOnDisk(extension: string) {
  findFileById.mockImplementation((id: string, requested: string) =>
    id === SERVICE_ID && requested === extension
      ? Promise.resolve(serviceFile(extension))
      : Promise.reject(new Error("not found")),
  );
}

function serviceDocument(content: Record<string, unknown> = {}): any {
  return {
    id: SERVICE_ID,
    name: "Orders",
    content: { protocol: "HTTP", ...content },
  };
}

beforeEach(() => {
  jest.clearAllMocks();
  findFiles.mockResolvedValue([]);
  getSpecificationGroupFiles.mockResolvedValue([]);
  getSpecificationFiles.mockResolvedValue([]);
  findAndBuildChainsRecursively.mockResolvedValue(undefined);
  readFileContent.mockResolvedValue("raw source");
  getFileType.mockResolvedValue("SERVICE");
  fileExists.mockResolvedValue(true);
});

describe("getService - the file name states the type", () => {
  it.each([
    [ext.externalService, "EXTERNAL"],
    [ext.internalService, "INTERNAL"],
    [ext.implementedService, "IMPLEMENTED"],
  ])("reads %s as a %s service", async (extension, expected) => {
    const fileUri = serviceFile(extension);
    onlyOnDisk(extension);
    getMainService.mockResolvedValue(serviceDocument());

    const service = await getService(fileUri, SERVICE_ID);

    expect(service.type).toBe(expected);
    expect(service.integrationSystemType).toBe(expected);
  });

  it("falls back to the field for the legacy type-less name", async () => {
    const fileUri = serviceFile(ext.service);
    onlyOnDisk(ext.service);
    getMainService.mockResolvedValue(
      serviceDocument({ integrationSystemType: "INTERNAL" }),
    );

    const service = await getService(fileUri, SERVICE_ID);

    expect(service.type).toBe("INTERNAL");
  });

  // Tolerant editor, strict backend: the backend refuses such a file on import, but the editor
  // keeps showing it rather than dropping the service out of the list.
  it("lets the name win when the body disagrees with it", async () => {
    const fileUri = serviceFile(ext.externalService);
    onlyOnDisk(ext.externalService);
    getMainService.mockResolvedValue(
      serviceDocument({ integrationSystemType: "INTERNAL" }),
    );

    const service = await getService(fileUri, SERVICE_ID);

    expect(service.type).toBe("EXTERNAL");
  });

  it("reads no type when neither the name nor the body states one", async () => {
    const fileUri = serviceFile(ext.service);
    onlyOnDisk(ext.service);
    getMainService.mockResolvedValue(serviceDocument());

    const service = await getService(fileUri, SERVICE_ID);

    expect(service.type).toBeUndefined();
  });

  it("keeps environments, labels and protocol intact for a typed file", async () => {
    const fileUri = serviceFile(ext.implementedService);
    onlyOnDisk(ext.implementedService);
    getMainService.mockResolvedValue(
      serviceDocument({
        description: "Order intake",
        activeEnvironmentId: "env-1",
        environments: [{ id: "env-1", name: "dev", address: "http://dev" }],
        labels: [{ name: "team", technical: false }],
      }),
    );

    const service = await getService(fileUri, SERVICE_ID);

    expect(service).toMatchObject({
      description: "Order intake",
      activeEnvironmentId: "env-1",
      protocol: "http",
      type: "IMPLEMENTED",
    });
    expect(service.environments).toHaveLength(1);
    expect(service.labels).toEqual([{ name: "team", technical: false }]);
  });
});

describe("resolving a service file by id", () => {
  it.each([
    [ext.externalService, "EXTERNAL"],
    [ext.internalService, "INTERNAL"],
    [ext.implementedService, "IMPLEMENTED"],
    [ext.service, undefined],
  ])("finds a service stored as %s", async (extension, expectedType) => {
    onlyOnDisk(extension);
    getMainService.mockImplementation((fileUri: any) =>
      Promise.resolve(
        fileUri.path.endsWith(extension)
          ? serviceDocument()
          : { id: "other", name: "Other", content: {} },
      ),
    );

    const service = await getService(
      uri("/root/other.chain.qip.yaml"),
      SERVICE_ID,
    );

    expect(service.type).toBe(expectedType);
  });

  it("prefers a typed file over the legacy sibling of the same service", async () => {
    findFileById.mockImplementation((_id: string, requested: string) =>
      requested === ext.internalService || requested === ext.service
        ? Promise.resolve(serviceFile(requested))
        : Promise.reject(new Error("not found")),
    );
    getMainService.mockImplementation((fileUri: any) =>
      Promise.resolve(
        fileUri.path.endsWith(".chain.qip.yaml")
          ? { id: "other", name: "Other", content: {} }
          : serviceDocument(),
      ),
    );

    const service = await getService(
      uri("/root/other.chain.qip.yaml"),
      SERVICE_ID,
    );

    expect(service.type).toBe("INTERNAL");
    expect(findFileById).not.toHaveBeenCalledWith(SERVICE_ID, ext.service);
  });

  it("resolves a typed file when reading environments by id", async () => {
    findFileById.mockImplementation((_id: string, requested: string) =>
      requested === ext.implementedService
        ? Promise.resolve(serviceFile(ext.implementedService))
        : Promise.reject(new Error("not found")),
    );
    getMainService.mockImplementation((fileUri: any) =>
      Promise.resolve(
        fileUri.path.endsWith(ext.implementedService)
          ? serviceDocument({
              environments: [
                { id: "env-1", name: "dev", address: "http://dev" },
              ],
            })
          : { id: "other", name: "Other", content: {} },
      ),
    );

    const environments = await getEnvironments(
      uri("/root/other.chain.qip.yaml"),
      SERVICE_ID,
    );

    expect(environments).toHaveLength(1);
    expect(environments[0]).toMatchObject({
      id: "env-1",
      address: "http://dev",
    });
  });

  it("reads the group level through the file the id resolves to", async () => {
    const fileUri = serviceFile(ext.externalService);
    onlyOnDisk(ext.externalService);
    getMainService.mockResolvedValue(serviceDocument());

    const groups = await getApiSpecifications(fileUri, SERVICE_ID);

    expect(groups).toEqual([]);
    expect(findFileById).toHaveBeenCalledWith(SERVICE_ID, ext.externalService);
    expect(getSpecificationGroupFiles).toHaveBeenCalledWith(fileUri);
  });
});

describe("getServices", () => {
  it("returns the single service when handed a typed service file", async () => {
    const fileUri = serviceFile(ext.internalService);
    onlyOnDisk(ext.internalService);
    getMainService.mockResolvedValue(serviceDocument());

    const services = await getServices(fileUri);

    expect(services).toHaveLength(1);
    expect(services[0].type).toBe("INTERNAL");
    expect(findFiles).not.toHaveBeenCalled();
  });

  // The branch reads the file to learn the id it was not given. Once the id resolves back to that
  // same file, the document is already in hand — reading it a second time buys nothing.
  it("reads the file it was handed once when the id resolves back to it", async () => {
    const fileUri = serviceFile(ext.internalService);
    onlyOnDisk(ext.internalService);
    getMainService.mockResolvedValue(serviceDocument());

    await getServices(fileUri);

    expect(getMainService).toHaveBeenCalledTimes(1);
  });

  it("scans every plain-service name when handed a file of another kind", async () => {
    findFiles.mockImplementation((extension: string) =>
      Promise.resolve(
        extension === ext.externalService ? [serviceFile(extension)] : [],
      ),
    );
    getMainService.mockResolvedValue(serviceDocument());

    const services = await getServices(uri("/root/c1/c1.chain.qip.yaml"));

    expect(findFiles.mock.calls.map((call) => call[0]).sort()).toEqual(
      [
        ext.externalService,
        ext.implementedService,
        ext.internalService,
        ext.service,
      ].sort(),
    );
    expect(services).toHaveLength(1);
    expect(services[0].type).toBe("EXTERNAL");
  });

  // A conversion writes the typed file before the legacy one is gone, and a half-finished one
  // leaves both behind for good. Listing both would show one service twice in the tree.
  it("lists a service that has both files once, from the typed one", async () => {
    findFiles.mockImplementation((extension: string) =>
      Promise.resolve(
        extension === ext.externalService || extension === ext.service
          ? [serviceFile(extension)]
          : [],
      ),
    );
    getMainService.mockImplementation((fileUri: any) =>
      Promise.resolve(
        serviceDocument(
          fileUri.path.endsWith(ext.service)
            ? { integrationSystemType: "INTERNAL" }
            : {},
        ),
      ),
    );

    const services = await getServices(uri("/root/c1/c1.chain.qip.yaml"));

    expect(services).toHaveLength(1);
    expect(services[0].type).toBe("EXTERNAL");
  });

  it("skips a file that carries no id instead of listing it", async () => {
    findFiles.mockImplementation((extension: string) =>
      Promise.resolve(
        extension === ext.internalService
          ? [
              serviceFile(extension),
              uri("/root/broken/broken.internal-service.qip.yaml"),
            ]
          : [],
      ),
    );
    getMainService.mockImplementation((fileUri: any) =>
      Promise.resolve(
        fileUri.path.includes("broken") ? { content: {} } : serviceDocument(),
      ),
    );

    const services = await getServices(uri("/root/c1/c1.chain.qip.yaml"));

    expect(services).toHaveLength(1);
    expect(services[0].id).toBe(SERVICE_ID);
  });
});

// The spec/operations subtree, on a service stored under a typed name. Every step here used to be
// resolved by the legacy `.service.` name alone, and the failure is silent: a typed service lists no
// APIs and no operations rather than reporting anything. The fixture is one typed service with a
// `resources/` source, an api group and an api under it.
describe("the spec subtree of a typed service", () => {
  const TYPED_SERVICE_ID = "11111111-1111-4111-8111-111111111111";
  const GROUP_ID = "22222222-2222-4222-8222-222222222222";
  const API_ID = `${TYPED_SERVICE_ID}-api`;
  const OPERATION_ID = `${API_ID}-operation`;

  const typedServiceUri = uri(
    `/root/${TYPED_SERVICE_ID}/${TYPED_SERVICE_ID}${ext.externalService}`,
  );
  const groupFileName = `${GROUP_ID}${ext.apiGroup}`;
  const apiFileName = `${API_ID}${ext.api}`;

  const typedServiceDocument = {
    id: TYPED_SERVICE_ID,
    name: "Orders",
    content: {
      protocol: "HTTP",
      environments: [{ id: "env-1", name: "dev", address: "http://dev" }],
    },
  };

  const groupDocument = {
    id: GROUP_ID,
    name: "Orders API",
    content: { parentId: TYPED_SERVICE_ID, description: "" },
  };

  const apiDocument = {
    id: API_ID,
    name: "Orders v1",
    content: {
      parentId: GROUP_ID,
      format: "openapi",
      specifications: [{ filePath: "orders.yaml", isRoot: true }],
      operations: [
        { id: OPERATION_ID, name: "getOrders", method: "GET", path: "/orders" },
      ],
    },
  };

  // Nothing but the typed file is on disk, so a lookup that asks only for `.service.` finds nothing.
  function onlyTypedFileOnDisk() {
    findFileById.mockImplementation((id: string, requested: string) =>
      id === TYPED_SERVICE_ID && requested === ext.externalService
        ? Promise.resolve(typedServiceUri)
        : Promise.reject(new Error("not found")),
    );
    getMainService.mockImplementation((fileUri: any) =>
      Promise.resolve(
        fileUri.path === typedServiceUri.path
          ? typedServiceDocument
          : { id: "other", name: "Other", content: {} },
      ),
    );
  }

  beforeEach(() => {
    onlyTypedFileOnDisk();
    getSpecificationGroupFiles.mockResolvedValue([groupFileName]);
    getSpecificationFiles.mockResolvedValue([apiFileName]);
    parseFile.mockImplementation((fileUri: any) =>
      Promise.resolve(
        fileUri.path.endsWith(ext.apiGroup) ? groupDocument : apiDocument,
      ),
    );
    parseContentFromFile.mockResolvedValue(apiDocument);
  });

  it("reads a single environment through the typed file", async () => {
    const environment = await getEnvironment(
      uri("/root/other.chain.qip.yaml"),
      TYPED_SERVICE_ID,
      "env-1",
    );

    expect(environment).toMatchObject({ id: "env-1", address: "http://dev" });
  });

  it("lists the group and its api from the typed service file", async () => {
    const groups = await getApiSpecifications(
      typedServiceUri,
      TYPED_SERVICE_ID,
    );

    expect(groups).toHaveLength(1);
    expect(groups[0].id).toBe(GROUP_ID);
    expect(groups[0].specifications).toHaveLength(1);
    expect(groups[0].specifications[0].id).toBe(API_ID);
  });

  it("reads the api level from a typed service file", async () => {
    const apis = await getSpecificationModel(
      typedServiceUri,
      TYPED_SERVICE_ID,
      GROUP_ID,
    );

    expect(apis).toHaveLength(1);
    expect(apis[0].id).toBe(API_ID);
    expect(getSpecificationFiles).toHaveBeenCalledWith(typedServiceUri);
  });

  it("resolves the typed file when the api level is read from elsewhere", async () => {
    const apis = await getSpecificationModel(
      uri("/root/other.chain.qip.yaml"),
      TYPED_SERVICE_ID,
      GROUP_ID,
    );

    expect(apis).toHaveLength(1);
    expect(getSpecificationFiles).toHaveBeenCalledWith(typedServiceUri);
  });

  it("lists the operations of an api under a typed service file", async () => {
    const operations = await getOperations(typedServiceUri, API_ID);

    expect(operations).toHaveLength(1);
    expect(operations[0].id).toBe(OPERATION_ID);
  });

  it("resolves the typed file when operations are read from elsewhere", async () => {
    const operations = await getOperations(
      uri("/root/other.chain.qip.yaml"),
      API_ID,
    );

    expect(operations).toHaveLength(1);
    expect(getSpecificationFiles).toHaveBeenCalledWith(typedServiceUri);
  });

  it("resolves the typed file when reading operation info from elsewhere", async () => {
    const info = await getOperationInfo(
      uri("/root/other.chain.qip.yaml"),
      OPERATION_ID,
    );

    expect(info.id).toBe(OPERATION_ID);
    expect(info.requestSchema).toEqual({ type: "object" });
    expect(getSpecificationFiles).toHaveBeenCalledWith(typedServiceUri);
  });
});

// The first save of an old-format service deletes the file the caller was handed. An editor tab
// opened before that save keeps it, so a later read has to resolve the service by id instead of
// failing on a path that no longer exists.
describe("reading through a uri the conversion replaced", () => {
  const staleUri = serviceFile(ext.service);
  const typedUri = serviceFile(ext.externalService);

  beforeEach(() => {
    findFileById.mockImplementation((id: string, requested: string) =>
      id === SERVICE_ID && requested === ext.externalService
        ? Promise.resolve(typedUri)
        : Promise.reject(new Error("not found")),
    );
    getMainService.mockImplementation((fileUri: any) =>
      fileUri.path === typedUri.path
        ? Promise.resolve(
            serviceDocument({
              environments: [
                { id: "env-1", name: "dev", address: "http://dev" },
              ],
            }),
          )
        : Promise.reject(new Error("EntryNotFound")),
    );
  });

  it("reads the service through the file it moved to", async () => {
    const service = await getService(staleUri, SERVICE_ID);

    expect(service.id).toBe(SERVICE_ID);
    expect(service.type).toBe("EXTERNAL");
  });

  it("reads environments through the file it moved to", async () => {
    const environments = await getEnvironments(staleUri, SERVICE_ID);

    expect(environments).toHaveLength(1);
  });

  it("reads one environment through the file it moved to", async () => {
    const environment = await getEnvironment(staleUri, SERVICE_ID, "env-1");

    expect(environment.address).toBe("http://dev");
  });

  it("reports the original failure when no file carries the id either", async () => {
    findFileById.mockRejectedValue(new Error("not found"));

    await expect(getService(staleUri, SERVICE_ID)).rejects.toThrow();
  });
});

// The same conversion, read from the api side. These four took any plain-service name as canonical,
// so a uri handed out before the conversion read the document that lost the precedence race — or
// threw, once the conversion had deleted it.
describe("reading the api subtree through a uri the conversion replaced", () => {
  const CONVERTED_ID = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
  const GROUP_ID = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
  const API_ID = `${CONVERTED_ID}-api`;
  const OPERATION_ID = `${API_ID}-operation`;

  const staleUri = uri(`/root/${CONVERTED_ID}/${CONVERTED_ID}${ext.service}`);
  const typedUri = uri(
    `/root/${CONVERTED_ID}/${CONVERTED_ID}${ext.externalService}`,
  );

  const groupDocument = {
    id: GROUP_ID,
    name: "Orders API",
    content: { parentId: CONVERTED_ID, description: "" },
  };

  const apiDocument = {
    id: API_ID,
    name: "Orders v1",
    content: {
      parentId: GROUP_ID,
      format: "openapi",
      specifications: [{ filePath: "orders.yaml", isRoot: true }],
      operations: [
        { id: OPERATION_ID, name: "getOrders", method: "GET", path: "/orders" },
      ],
    },
  };

  beforeEach(() => {
    findFileById.mockImplementation((id: string, requested: string) =>
      id === CONVERTED_ID && requested === ext.externalService
        ? Promise.resolve(typedUri)
        : Promise.reject(new Error("not found")),
    );
    // The conversion deleted the legacy file, so only the typed one still reads.
    getMainService.mockImplementation((fileUri: any) =>
      fileUri.path === typedUri.path
        ? Promise.resolve({
            id: CONVERTED_ID,
            name: "Orders",
            content: { protocol: "HTTP" },
          })
        : Promise.reject(new Error("EntryNotFound")),
    );
    // Both names sit in the same folder, so the listing cannot tell them apart. Which file the read
    // resolved to is what the assertions below check.
    getSpecificationGroupFiles.mockResolvedValue([
      `${GROUP_ID}${ext.apiGroup}`,
    ]);
    getSpecificationFiles.mockResolvedValue([`${API_ID}${ext.api}`]);
    parseFile.mockImplementation((fileUri: any) =>
      Promise.resolve(
        fileUri.path.endsWith(ext.apiGroup) ? groupDocument : apiDocument,
      ),
    );
    parseContentFromFile.mockResolvedValue(apiDocument);
  });

  it("lists the groups through the file the service moved to", async () => {
    const groups = await getApiSpecifications(staleUri, CONVERTED_ID);

    expect(groups).toHaveLength(1);
    expect(groups[0].id).toBe(GROUP_ID);
    expect(getSpecificationGroupFiles).toHaveBeenCalledWith(typedUri);
  });

  it("reads the api level through the file the service moved to", async () => {
    const apis = await getSpecificationModel(staleUri, CONVERTED_ID, GROUP_ID);

    expect(apis).toHaveLength(1);
    expect(getSpecificationFiles).toHaveBeenCalledWith(typedUri);
  });

  it("reads the operations through the file the service moved to", async () => {
    const operations = await getOperations(staleUri, API_ID);

    expect(operations).toHaveLength(1);
    expect(getSpecificationFiles).toHaveBeenCalledWith(typedUri);
  });

  it("reads operation info through the file the service moved to", async () => {
    const info = await getOperationInfo(staleUri, OPERATION_ID);

    expect(info.id).toBe(OPERATION_ID);
    expect(getSpecificationFiles).toHaveBeenCalledWith(typedUri);
  });
});

// A conversion that could not delete the legacy sibling leaves both files on disk, and the delete
// failure is swallowed on purpose. The tree and `getServices` both list such a service from the typed
// file, so a read handed the legacy uri has to land on the same document — otherwise the editor shows
// what the list does not.
describe("reading a service that has both files on disk", () => {
  const legacyUri = serviceFile(ext.service);
  const typedUri = serviceFile(ext.externalService);

  beforeEach(() => {
    findFileById.mockImplementation((id: string, requested: string) =>
      id === SERVICE_ID &&
      (requested === ext.externalService || requested === ext.service)
        ? Promise.resolve(serviceFile(requested))
        : Promise.reject(new Error("not found")),
    );
    getMainService.mockImplementation((fileUri: any) =>
      Promise.resolve(
        fileUri.path === typedUri.path
          ? serviceDocument({
              description: "current",
              environments: [
                { id: "env-1", name: "dev", address: "http://dev" },
              ],
            })
          : serviceDocument({
              description: "superseded",
              integrationSystemType: "INTERNAL",
              environments: [],
            }),
      ),
    );
  });

  it("reads the typed file when handed the legacy uri", async () => {
    const service = await getService(legacyUri, SERVICE_ID);

    expect(service.type).toBe("EXTERNAL");
    expect(service.description).toBe("current");
  });

  it("reads environments from the typed file when handed the legacy uri", async () => {
    const environments = await getEnvironments(legacyUri, SERVICE_ID);

    expect(environments).toHaveLength(1);
  });

  it("reads one environment from the typed file when handed the legacy uri", async () => {
    const environment = await getEnvironment(legacyUri, SERVICE_ID, "env-1");

    expect(environment.address).toBe("http://dev");
  });

  it("lists the single service from the typed file when handed the legacy uri", async () => {
    const services = await getServices(legacyUri);

    expect(services).toHaveLength(1);
    expect(services[0].type).toBe("EXTERNAL");
    expect(services[0].description).toBe("current");
  });

  it("reads the group level from the typed file when handed the legacy uri", async () => {
    await getApiSpecifications(legacyUri, SERVICE_ID);

    expect(getSpecificationGroupFiles).toHaveBeenCalledWith(typedUri);
  });

  // Both files carry one id — that is what makes them siblings — so the id needs no lookup to
  // answer. The navigation routes this feeds are built from the id alone.
  it("answers the service id from the document it was handed", async () => {
    await expect(getCurrentServiceId(legacyUri)).resolves.toBe(SERVICE_ID);

    expect(findFileById).not.toHaveBeenCalled();
  });
});

// The two reads that start without an id: the navigation route builder and the single-file branch of
// `getServices`. Neither is handed the id, so a uri the conversion deleted is recovered through the
// id the file name states.
describe("reading without an id through a uri the conversion replaced", () => {
  const staleUri = serviceFile(ext.service);
  const typedUri = serviceFile(ext.implementedService);

  beforeEach(() => {
    findFileById.mockImplementation((id: string, requested: string) =>
      id === SERVICE_ID && requested === ext.implementedService
        ? Promise.resolve(typedUri)
        : Promise.reject(new Error("not found")),
    );
    getMainService.mockImplementation((fileUri: any) =>
      fileUri.path === typedUri.path
        ? Promise.resolve(serviceDocument())
        : Promise.reject(new Error("EntryNotFound")),
    );
  });

  it("answers the current service id through the file it moved to", async () => {
    await expect(getCurrentServiceId(staleUri)).resolves.toBe(SERVICE_ID);
  });

  it("lists the single service through the file it moved to", async () => {
    const services = await getServices(staleUri);

    expect(services).toHaveLength(1);
    expect(services[0].type).toBe("IMPLEMENTED");
  });

  it("reports the failure when the name states no id either", async () => {
    await expect(getCurrentServiceId(uri("/root/notes.txt"))).rejects.toThrow();
  });
});

// The fallback to the held uri is what keeps a read that starts from a chain or an api file in the
// folder it came from. It stands for the service only while that uri still points at something.
describe("falling back to the held uri", () => {
  beforeEach(() => {
    findFileById.mockRejectedValue(new Error("not found"));
  });

  it("reads on through a uri that is still there", async () => {
    fileExists.mockResolvedValue(true);
    getSpecificationFiles.mockResolvedValue([]);

    const apis = await getSpecificationModel(
      uri("/root/c1/c1.chain.qip.yaml"),
      SERVICE_ID,
      "group-1",
    );

    expect(apis).toEqual([]);
    expect(getSpecificationFiles).toHaveBeenCalledWith(
      uri("/root/c1/c1.chain.qip.yaml"),
    );
  });

  it("reports the lookup failure rather than handing back a deleted uri", async () => {
    fileExists.mockResolvedValue(false);

    await expect(
      getSpecificationModel(serviceFile(ext.service), SERVICE_ID, "group-1"),
    ).rejects.toThrow(SERVICE_ID);
    expect(getSpecificationFiles).not.toHaveBeenCalled();
  });
});
