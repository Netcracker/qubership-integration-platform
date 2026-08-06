// The request needs a systemId to find the service file; the stored environment
// does not, because it lives inside that very file. The backend does not export
// one either — Environment.system is @JsonBackReference — so writing it made
// extension files differ from exported ones for no gain.

import { createMinimalVscodeMock, QIP_FILE_EXTENSIONS as ext } from "../helpers/mocks";

jest.mock("vscode", () => createMinimalVscodeMock(), { virtual: true });

const getRawServiceById = jest.fn();
jest.mock("../../src/web/api-services/SystemService", () => ({
  SystemService: jest.fn().mockImplementation(() => ({
    getRawServiceById: (...args: unknown[]) => getRawServiceById(...args),
  })),
}));

const findFileById = jest.fn();
const writeMainService = jest.fn();
jest.mock("../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    findFileById: (...args: unknown[]) => findFileById(...args),
    writeMainService: (...args: unknown[]) => writeMainService(...args),
  },
}));

jest.mock("../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: () => ext,
  extractFilename: (fileRef: string | { path: string }) =>
    (typeof fileRef === "string" ? fileRef : fileRef.path).split("/").pop() ??
    "",
}));

import { EnvironmentService } from "../../src/web/api-services/EnvironmentService";

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

  // Environments live inside the service file, so a service stored under a typed name that the
  // write path cannot find leaves the environments tab empty and every save failing.
  it("writes back to a service stored under a typed name", async () => {
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
});
