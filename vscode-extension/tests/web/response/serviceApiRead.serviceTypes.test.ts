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
const findAndBuildChainsRecursively = jest.fn().mockResolvedValue(undefined);
const getRootDirectory = jest.fn().mockReturnValue({ path: "/root" });

jest.mock("../../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    getMainService,
    findFileById,
    findFiles,
    getSpecificationGroupFiles,
    findAndBuildChainsRecursively,
    getRootDirectory,
    parseFile: jest.fn(),
    getFileCreatedWhen: jest.fn().mockResolvedValue(0),
  },
}));

import {
  getApiSpecifications,
  getEnvironments,
  getService,
  getServices,
} from "../../../src/web/response/serviceApiRead";

const SERVICE_ID = "svc-1";

function uri(path: string): any {
  return { path, fsPath: path };
}

function serviceFile(extension: string): any {
  return uri(`/root/${SERVICE_ID}/${SERVICE_ID}${extension}`);
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
  findAndBuildChainsRecursively.mockResolvedValue(undefined);
});

describe("getService - the file name states the type", () => {
  it.each([
    [ext.externalService, "EXTERNAL"],
    [ext.internalService, "INTERNAL"],
    [ext.implementedService, "IMPLEMENTED"],
  ])("reads %s as a %s service", async (extension, expected) => {
    const fileUri = serviceFile(extension);
    getMainService.mockResolvedValue(serviceDocument());

    const service = await getService(fileUri, SERVICE_ID);

    expect(service.type).toBe(expected);
    expect(service.integrationSystemType).toBe(expected);
  });

  it("falls back to the field for the legacy type-less name", async () => {
    const fileUri = serviceFile(ext.service);
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
    getMainService.mockResolvedValue(
      serviceDocument({ integrationSystemType: "INTERNAL" }),
    );

    const service = await getService(fileUri, SERVICE_ID);

    expect(service.type).toBe("EXTERNAL");
  });

  it("reads no type when neither the name nor the body states one", async () => {
    const fileUri = serviceFile(ext.service);
    getMainService.mockResolvedValue(serviceDocument());

    const service = await getService(fileUri, SERVICE_ID);

    expect(service.type).toBe("");
  });

  it("keeps environments, labels and protocol intact for a typed file", async () => {
    const fileUri = serviceFile(ext.implementedService);
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
  // Only the file that exists answers; the others reject the way findFileById does for a miss.
  function onlyOnDisk(extension: string) {
    findFileById.mockImplementation((id: string, requested: string) =>
      requested === extension
        ? Promise.resolve(serviceFile(extension))
        : Promise.reject(new Error("not found")),
    );
  }

  it.each([
    [ext.externalService, "EXTERNAL"],
    [ext.internalService, "INTERNAL"],
    [ext.implementedService, "IMPLEMENTED"],
    [ext.service, ""],
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

  it("accepts a typed service file for the group level without resolving it again", async () => {
    const fileUri = serviceFile(ext.externalService);
    getMainService.mockResolvedValue(serviceDocument());

    const groups = await getApiSpecifications(fileUri, SERVICE_ID);

    expect(groups).toEqual([]);
    expect(findFileById).not.toHaveBeenCalled();
    expect(getSpecificationGroupFiles).toHaveBeenCalledWith(fileUri);
  });
});

describe("getServices", () => {
  it("returns the single service when handed a typed service file", async () => {
    const fileUri = serviceFile(ext.internalService);
    getMainService.mockResolvedValue(serviceDocument());

    const services = await getServices(fileUri);

    expect(services).toHaveLength(1);
    expect(services[0].type).toBe("INTERNAL");
    expect(findFiles).not.toHaveBeenCalled();
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
