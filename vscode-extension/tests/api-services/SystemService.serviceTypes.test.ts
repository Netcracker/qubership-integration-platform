// getSystemById feeds the specification import: validateAllowedSystemProtocol and the
// external-versus-internal environment branch both read the type it returns. A typed file whose type
// came back empty let an import through that the protocol rules should have refused.

import {
  createMinimalVscodeMock,
  QIP_FILE_EXTENSIONS as ext,
} from "../helpers/mocks";

jest.mock("vscode", () => createMinimalVscodeMock(), { virtual: true });

const findFileById = jest.fn();
const getMainService = jest.fn();
const writeMainService = jest.fn();

jest.mock("../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    findFileById: (...args: unknown[]) => findFileById(...args),
    getMainService: (...args: unknown[]) => getMainService(...args),
    writeMainService: (...args: unknown[]) => writeMainService(...args),
  },
}));

jest.mock("../../src/web/response/serviceApiRead", () => ({
  getMainService: (...args: unknown[]) => getMainService(...args),
}));

jest.mock("../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: () => ext,
  extractFilename: (fileRef: string | { path: string }) =>
    (typeof fileRef === "string" ? fileRef : fileRef.path).split("/").pop() ??
    "",
}));

jest.mock("../../src/web/api-services/LabelUtils", () => ({
  LabelUtils: {
    toEntityLabels: jest.fn().mockReturnValue([]),
    fromEntityLabels: jest.fn().mockReturnValue([]),
  },
}));

import { SystemService } from "../../src/web/api-services/SystemService";

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
    [ext.externalService, "EXTERNAL"],
    [ext.internalService, "INTERNAL"],
    [ext.implementedService, "IMPLEMENTED"],
  ])("reads the type a %s name states", async (extension, expected) => {
    onlyOnDisk(extension);
    getMainService.mockResolvedValue({
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

  it("falls back to the field for the legacy type-less name", async () => {
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
  it("writes back to the typed file rather than looking only for the legacy one", async () => {
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
});
