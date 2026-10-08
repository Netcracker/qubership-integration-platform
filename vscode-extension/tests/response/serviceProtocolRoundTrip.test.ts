import {
  buildServiceRecord,
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
  stubProjectConfigService,
} from "../helpers/mocks";

// createService reads `vscode` through its default export.
jest.mock(
  "vscode",
  () => {
    const vscode = createVscodeMock();
    return { __esModule: true, ...vscode, default: vscode };
  },
  { virtual: true },
);
jest.mock("yaml", () => ({ stringify: jest.fn(), parse: jest.fn() }));
jest.mock("../../src/web/response/file/fileApiProvider", () =>
  stubFileApi({ getMainService: jest.fn(), findFileById: jest.fn() }),
);
jest.mock("../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: jest
    .fn()
    .mockReturnValue({ service: ".qip-service.yaml" }),
  getExtensionsForUri: jest
    .fn()
    .mockReturnValue({ service: ".qip-service.yaml" }),
}));
jest.mock("../../src/web/extension", () => ({ refreshQipExplorer: jest.fn() }));
jest.mock("../../src/web/api-services/LabelUtils", () => stubLabelUtils());
jest.mock("../../src/web/services/ProjectConfigService", () =>
  stubProjectConfigService(),
);
jest.mock("../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile: jest.fn() },
}));
jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });

import { getService } from "../../src/web/response/serviceApiRead";
import {
  createService,
  updateService,
} from "../../src/web/response/serviceApiModify";
import { SystemService } from "../../src/web/api-services/SystemService";
import { ServiceNormalizer } from "../../src/web/api-services/ServiceNormalizer";
import { SERVICE_MIGRATIONS } from "../../src/web/constants/migrations";
import { fileApi } from "../../src/web/response/file/fileApiProvider";

const serviceFileUri = {} as any;

describe("getService <-> updateService – the protocol on disk", () => {
  beforeEach(() => jest.clearAllMocks());

  // ServiceParametersTab and the services tree both spread the whole system
  // they read back into the update request, so a save that only means to
  // change the name still carries the protocol the read reported.
  test("renaming a SOAP service leaves the stored protocol alone", async () => {
    (fileApi.getMainService as jest.Mock).mockResolvedValue(
      buildServiceRecord("svc-1", {
        integrationSystemType: "EXTERNAL",
        protocol: "SOAP",
      }),
    );

    const asShownInTheUi = await getService(serviceFileUri, "svc-1");
    await updateService(serviceFileUri, "svc-1", {
      ...asShownInTheUi,
      name: "Renamed",
    });

    const written = (fileApi.writeMainService as jest.Mock).mock.calls[0][1];
    expect(written.content.protocol).toBe("SOAP");
    expect(written.name).toBe("Renamed");
  });

  // Specification import validates the protocol being imported against this value, so it stays
  // the stored enum name while the UI-facing read reports `http`.
  test("SystemService reports the stored protocol name, not the transport", async () => {
    (fileApi.getMainService as jest.Mock).mockResolvedValue(
      buildServiceRecord("svc-1", {
        integrationSystemType: "EXTERNAL",
        protocol: "SOAP",
      }),
    );

    const system = await new SystemService().getSystemById("svc-1");

    expect(system?.protocol).toBe("SOAP");
  });

  // The catalog cannot read an empty protocol, so a service without a specification stores none.
  test("reading a service without a protocol adds none", () => {
    const service = ServiceNormalizer.normalizeService({
      id: "svc-1",
      content: { integrationSystemType: "EXTERNAL" },
    });

    expect(service.content.protocol).toBeUndefined();
  });

  test("creating a service without a protocol stores none, at the current migrations", async () => {
    await createService(
      {} as any,
      {} as any,
      {
        name: "New",
        type: "EXTERNAL",
      } as any,
    );

    const written = (fileApi.writeServiceFile as jest.Mock).mock.calls[0][1];
    expect(written.content.protocol).toBeUndefined();
    expect(written.content.migrations).toBe(SERVICE_MIGRATIONS);
  });

  test("SystemService saves a service that has no protocol without adding one", async () => {
    (fileApi.getMainService as jest.Mock).mockResolvedValue({
      id: "svc-1",
      content: { integrationSystemType: "EXTERNAL" },
    });
    const systemService = new SystemService();

    const system = await systemService.getSystemById("svc-1");
    await systemService.saveSystem(system!);

    const written = (fileApi.writeMainService as jest.Mock).mock.calls[0][1];
    expect(written.content.protocol).toBeUndefined();
  });
});
