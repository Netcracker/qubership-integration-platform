// The request needs a systemId to find the service file; the stored environment
// does not, because it lives inside that very file. The backend does not export
// one either — Environment.system is @JsonBackReference — so writing it made
// extension files differ from exported ones for no gain.

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

const getRawServiceById = jest.fn();
jest.mock("../../src/web/api-services/SystemService", () => ({
  SystemService: jest.fn().mockImplementation(() => ({
    getRawServiceById: (...args: unknown[]) => getRawServiceById(...args),
  })),
}));

const findFileById = jest.fn();
const writeMainService = jest.fn();
const deleteFile = jest.fn();
jest.mock("../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    findFileById: (...args: unknown[]) => findFileById(...args),
    writeMainService: (...args: unknown[]) => writeMainService(...args),
    deleteFile: (...args: unknown[]) => deleteFile(...args),
  },
}));

jest.mock("../../src/web/response/file/fileExtensions", () =>
  jest.requireActual("../helpers/mocks").fileExtensionsMock(
    () => ext,
    () => undefined,
  ),
);

import { EnvironmentService } from "../../src/web/api-services/EnvironmentService";
import { UnreadableServiceFileError } from "../../src/web/response/file/serviceFileLookup";

describe("EnvironmentService.createEnvironment", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    findFileById.mockResolvedValue({ path: `/sys-1/sys-1${ext.service}` });
    getRawServiceById.mockResolvedValue({
      id: "sys-1",
      content: { protocol: "HTTP", environments: [] },
    });
  });

  it("does not store a back-reference to the owning service", async () => {
    const created = await new EnvironmentService().createEnvironment({
      systemId: "sys-1",
      name: "Production",
      address: "https://example.test",
    } as any);

    expect(created).not.toHaveProperty("systemId");

    const [, written] = writeMainService.mock.calls[0];
    expect(written.content.environments).toHaveLength(1);
    expect(written.content.environments[0]).not.toHaveProperty("systemId");
    expect(written.content.environments[0].address).toBe(
      "https://example.test",
    );
  });

  it("still requires the systemId on the request", async () => {
    await expect(
      new EnvironmentService().createEnvironment({ name: "X" } as any),
    ).rejects.toThrow(/System id is required/);
  });

  // Environments live inside the service file, so a service stored under a per-type name that the
  // write path cannot find leaves the environments tab empty and every save failing.
  it("writes back to a service stored under a per-type name", async () => {
    findFileById.mockImplementation((_id: string, extension: string) =>
      extension === ext.implementedService
        ? Promise.resolve({ path: `/sys-1/sys-1${ext.implementedService}` })
        : Promise.reject(new Error("not found")),
    );

    await new EnvironmentService().createEnvironment({
      systemId: "sys-1",
      name: "Production",
      address: "https://example.test",
    } as any);

    const [fileUri] = writeMainService.mock.calls[0];
    expect(fileUri.path).toBe(`/sys-1/sys-1${ext.implementedService}`);
  });

  // Adding an environment is a write like any other, so it converts a pre-#553 file too. Leaving
  // this one path on the old carrier is how a project ends up half migrated.
  it("converts a pre-#553 file when it writes an environment back", async () => {
    getRawServiceById.mockResolvedValue({
      id: "sys-1",
      content: {
        protocol: "HTTP",
        integrationSystemType: "EXTERNAL",
        environments: [],
      },
    });

    await new EnvironmentService().createEnvironment({
      systemId: "sys-1",
      name: "Production",
      address: "https://example.test",
    } as any);

    // The name does not move — the conversion is the carrier, `content` to `$schema`.
    const [fileUri, service] = writeMainService.mock.calls[0];
    expect(fileUri.path).toBe(`/sys-1/sys-1${ext.service}`);
    expect(service.content).not.toHaveProperty("integrationSystemType");
    expect(deleteFile).not.toHaveBeenCalled();
  });
});

// An empty environments tab reads as "this service has none", which is the wrong answer for a
// service the lookup refused to resolve — and the file to fix is never named.
describe("EnvironmentService.getEnvironmentsForSystem", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("reports a refusal rather than an empty list", async () => {
    getRawServiceById.mockRejectedValue(
      new UnreadableServiceFileError("sys-1", {
        path: `/sys-1/sys-1${ext.externalService}`,
      } as any),
    );

    await expect(
      new EnvironmentService().getEnvironmentsForSystem("sys-1"),
    ).rejects.toThrow(`/sys-1/sys-1${ext.externalService}`);
  });

  it("still answers an empty list when the service is simply gone", async () => {
    getRawServiceById.mockRejectedValue(new Error("not found"));

    expect(
      await new EnvironmentService().getEnvironmentsForSystem("sys-1"),
    ).toEqual([]);
  });
});
