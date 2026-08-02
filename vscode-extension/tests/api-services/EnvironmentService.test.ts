// The request needs a systemId to find the service file; the stored environment
// does not, because it lives inside that very file. The backend does not export
// one either — Environment.system is @JsonBackReference — so writing it made
// extension files differ from exported ones for no gain.

import { createMinimalVscodeMock } from "../helpers/mocks";

jest.mock("vscode", () => createMinimalVscodeMock(), { virtual: true });

const getRawServiceById = jest.fn();
jest.mock("../../src/web/api-services/SystemService", () => ({
  SystemService: jest.fn().mockImplementation(() => ({
    getRawServiceById: (...args: unknown[]) => getRawServiceById(...args),
  })),
}));

const writeMainService = jest.fn();
jest.mock("../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {
    findFileById: jest.fn().mockResolvedValue({ path: "/svc.yaml" }),
    writeMainService: (...args: unknown[]) => writeMainService(...args),
  },
}));

jest.mock("../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: () => ({ service: ".service.qip.yaml" }),
}));

import { EnvironmentService } from "../../src/web/api-services/EnvironmentService";

describe("EnvironmentService.createEnvironment", () => {
  beforeEach(() => {
    jest.clearAllMocks();
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
});
