// getSystemById feeds the specification import: validateAllowedSystemProtocol and the
// external-versus-internal environment branch both read the type it returns. A typed file whose type
// came back empty let an import through that the protocol rules should have refused.

import { QIP_FILE_EXTENSIONS as ext, URN_SCHEMA_URLS } from "../helpers/mocks";

jest.mock(
  "vscode",
  () => {
    const { createMinimalVscodeMock, joinUriPath } =
      jest.requireActual("../helpers/mocks");
    return {
      ...createMinimalVscodeMock(),
      Uri: { joinPath: jest.fn(joinUriPath) },
    };
  },
  { virtual: true },
);

const findFileById = jest.fn();
const getMainService = jest.fn();
const writeMainService = jest.fn();
const deleteFile = jest.fn();

jest.mock("../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    findFileById: (...args: unknown[]) => findFileById(...args),
    getMainService: (...args: unknown[]) => getMainService(...args),
    writeMainService: (...args: unknown[]) => writeMainService(...args),
    deleteFile: (...args: unknown[]) => deleteFile(...args),
  },
}));

jest.mock("../../src/web/services/ProjectConfigService", () => {
  const { QIP_FILE_EXTENSIONS } = jest.requireActual("../helpers/mocks");
  return {
    ProjectConfigService: {
      getConfig: () => ({
        extensions: QIP_FILE_EXTENSIONS,
        schemaUrls: URN_SCHEMA_URLS,
      }),
      getInstance: jest.fn(),
    },
  };
});

jest.mock("../../src/web/response/serviceApiRead", () => ({
  getMainService: (...args: unknown[]) => getMainService(...args),
}));

const SCHEMA_URLS = URN_SCHEMA_URLS;

jest.mock("../../src/web/response/file/fileExtensions", () =>
  jest.requireActual("../helpers/mocks").fileExtensionsMock(
    () => ext,
    () => SCHEMA_URLS,
  ),
);

jest.mock("../../src/web/api-services/LabelUtils", () => ({
  LabelUtils: {
    toEntityLabels: jest.fn().mockReturnValue([]),
    fromEntityLabels: jest.fn().mockReturnValue([]),
  },
}));

import { SystemService } from "../../src/web/api-services/SystemService";
import { UnreadableFileError } from "../../src/web/response/fileFilteringUtils";

const SYSTEM_ID = "sys-1";

// Only the named extension is on disk; the rest reject the way findFileById does for a miss.
function onlyOnDisk(extension: string) {
  findFileById.mockImplementation((id: string, requested: string) =>
    requested === extension
      ? Promise.resolve({ path: `/${id}/${id}${requested}` })
      : Promise.reject(new Error("not found")),
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe("SystemService.getSystemById", () => {
  it.each([
    ["urn:external", "EXTERNAL"],
    ["urn:internal", "INTERNAL"],
    ["urn:implemented", "IMPLEMENTED"],
  ])("reads the type a %s $schema states", async (schema, expected) => {
    onlyOnDisk(ext.service);
    getMainService.mockResolvedValue({
      $schema: schema,
      id: SYSTEM_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    const system = await new SystemService().getSystemById(SYSTEM_ID);

    expect(system).toMatchObject({
      integrationSystemType: expected,
      type: expected,
    });
  });

  it("falls back to the field for a pre-#553 document", async () => {
    onlyOnDisk(ext.service);
    getMainService.mockResolvedValue({
      id: SYSTEM_ID,
      name: "Orders",
      content: { protocol: "HTTP", integrationSystemType: "INTERNAL" },
    });

    const system = await new SystemService().getSystemById(SYSTEM_ID);

    expect(system?.type).toBe("INTERNAL");
  });

  it("returns null when no plain-service name carries the id", async () => {
    onlyOnDisk(ext.chain);

    expect(await new SystemService().getSystemById(SYSTEM_ID)).toBeNull();
  });
});

describe("SystemService.saveSystem", () => {
  it("writes back to a per-type file rather than looking only for the current name", async () => {
    onlyOnDisk(ext.externalService);
    getMainService.mockResolvedValue({
      id: SYSTEM_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    await new SystemService().saveSystem({
      id: SYSTEM_ID,
      name: "Orders",
      protocol: "http",
    } as any);

    const [fileUri] = writeMainService.mock.calls[0];
    expect(fileUri.path).toBe(
      `/${SYSTEM_ID}/${SYSTEM_ID}${ext.externalService}`,
    );
  });

  // The services list saves through here, not through updateService, so this path converts too —
  // otherwise the same service migrates or not depending on which screen edited it.
  it("converts a pre-#553 file it saves to the $schema its type states", async () => {
    onlyOnDisk(ext.service);
    getMainService.mockResolvedValue({
      id: SYSTEM_ID,
      name: "Orders",
      content: { protocol: "HTTP", integrationSystemType: "INTERNAL" },
    });

    await new SystemService().saveSystem({
      id: SYSTEM_ID,
      name: "Orders",
      protocol: "http",
      integrationSystemType: "INTERNAL",
    } as any);

    const [fileUri, service] = writeMainService.mock.calls[0];
    expect(fileUri.path).toBe(`/${SYSTEM_ID}/${SYSTEM_ID}${ext.service}`);
    expect(service.content).not.toHaveProperty("integrationSystemType");
    expect(service.$schema).toBe("urn:internal");
    expect(deleteFile).not.toHaveBeenCalled();
  });

  // The one rename left: a per-type file a #553 version wrote goes back to the plain name.
  it("converts a per-type file it saves back to the plain name", async () => {
    onlyOnDisk(ext.internalService);
    getMainService.mockResolvedValue({
      $schema: "urn:internal",
      id: SYSTEM_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    await new SystemService().saveSystem({
      id: SYSTEM_ID,
      name: "Orders",
      protocol: "http",
    } as any);

    const [fileUri] = writeMainService.mock.calls[0];
    expect(fileUri.path).toBe(`/${SYSTEM_ID}/${SYSTEM_ID}${ext.service}`);
    expect(deleteFile).toHaveBeenCalledWith(
      expect.objectContaining({
        path: `/${SYSTEM_ID}/${SYSTEM_ID}${ext.internalService}`,
      }),
    );
  });

  // A caller holding the old uri reads a deleted path — the specification import is one, and it
  // saves the protocol before it writes any file into the service folder.
  it("returns the file the conversion produced", async () => {
    onlyOnDisk(ext.externalService);
    getMainService.mockResolvedValue({
      $schema: "urn:external",
      id: SYSTEM_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    const writtenFileUri = await new SystemService().saveSystem({
      id: SYSTEM_ID,
      name: "Orders",
      protocol: "http",
    } as any);

    expect(writtenFileUri.path).toBe(
      `/${SYSTEM_ID}/${SYSTEM_ID}${ext.service}`,
    );
  });

  // The type is set at creation and never again. Writing it from the request let a caller both
  // switch the stored type and rename a pre-#553 file to the type it just supplied.
  it("keeps the type the stored file states, whatever the request says", async () => {
    onlyOnDisk(ext.service);
    getMainService.mockResolvedValue({
      id: SYSTEM_ID,
      name: "Orders",
      content: { protocol: "HTTP", integrationSystemType: "INTERNAL" },
    });

    await new SystemService().saveSystem({
      id: SYSTEM_ID,
      name: "Orders",
      protocol: "http",
      type: "EXTERNAL",
      integrationSystemType: "EXTERNAL",
    } as any);

    const [, service] = writeMainService.mock.calls[0];
    expect(service.$schema).toBe("urn:internal");
  });

  it("adds no type to a pre-#553 file that states none", async () => {
    onlyOnDisk(ext.service);
    getMainService.mockResolvedValue({
      id: SYSTEM_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });

    await new SystemService().saveSystem({
      id: SYSTEM_ID,
      name: "Orders",
      protocol: "http",
      type: "EXTERNAL",
    } as any);

    const [fileUri, service] = writeMainService.mock.calls[0];
    expect(fileUri.path).toBe(`/${SYSTEM_ID}/${SYSTEM_ID}${ext.service}`);
    expect(service.content).not.toHaveProperty("integrationSystemType");
    expect(service.$schema).toBeUndefined();
  });
});

// A lookup that refused because it would have answered with the sibling of a file it could not read
// is not "no such system": both accessors feed environment edits and the specification import, and
// `null` there reports an absence nobody can act on.
describe("a service file the lookup refused to resolve", () => {
  beforeEach(() => {
    findFileById.mockImplementation((id: string, requested: string) =>
      requested === ext.service
        ? Promise.reject(
            new UnreadableFileError(requested, [
              { path: `/${id}/${id}${requested}` } as any,
            ]),
          )
        : Promise.resolve({ path: `/${id}/${id}${requested}` }),
    );
    getMainService.mockResolvedValue({
      id: SYSTEM_ID,
      name: "Orders",
      content: { protocol: "HTTP" },
    });
  });

  it("reports the refusal from getSystemById rather than answering null", async () => {
    await expect(new SystemService().getSystemById(SYSTEM_ID)).rejects.toThrow(
      `/${SYSTEM_ID}/${SYSTEM_ID}${ext.service}`,
    );
  });

  it("reports it from getRawServiceById too", async () => {
    await expect(
      new SystemService().getRawServiceById(SYSTEM_ID),
    ).rejects.toThrow(`/${SYSTEM_ID}/${SYSTEM_ID}${ext.service}`);
  });

  it("still answers null for a plain miss", async () => {
    findFileById.mockRejectedValue(new Error("not found"));

    expect(await new SystemService().getSystemById(SYSTEM_ID)).toBeNull();
  });
});
