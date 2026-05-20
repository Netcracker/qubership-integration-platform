import {
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
  stubProjectConfigService,
  buildServiceRecord,
} from "../helpers/mocks";

jest.mock("vscode", () => createVscodeMock(), { virtual: true });
jest.mock("yaml", () => ({ stringify: jest.fn(), parse: jest.fn() }));
jest.mock("../../src/web/response/file/fileApiProvider", () => stubFileApi());
jest.mock("../../src/web/response/serviceApiRead", () => ({
  getMainService: jest.fn(),
  getService: jest.fn(),
  getContextService: jest.fn(),
}));
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

jest.mock("../../src/web/response/serviceApiUtils", () => {
  const actual = jest.requireActual("../../src/web/response/serviceApiUtils");
  return {
    ...actual,
    validateAllowedSystemProtocol: jest.fn(
      actual.validateAllowedSystemProtocol,
    ),
  };
});

import {
  IntegrationSystemType,
  IntegrationSystem,
} from "../../src/web/api-services/servicesTypes";
import { ApiSpecificationType } from "../../src/web/api-services/importApiTypes";
import { updateService } from "../../src/web/response/serviceApiModify";
import {
  getMainService,
  getService,
} from "../../src/web/response/serviceApiRead";
import { validateAllowedSystemProtocol } from "../../src/web/response/serviceApiUtils";

describe("updateService – validateAllowedSystemProtocol integration", () => {
  const serviceFileUri = {} as any;
  const serviceId = "svc-1";

  beforeEach(() => jest.clearAllMocks());

  test("calls validateAllowedSystemProtocol with (type, existing protocol) when type is set", async () => {
    (getMainService as jest.Mock).mockResolvedValue(
      buildServiceRecord(serviceId, { protocol: ApiSpecificationType.HTTP }),
    );
    (getService as jest.Mock).mockResolvedValue({
      id: serviceId,
    } as IntegrationSystem);

    await updateService(serviceFileUri, serviceId, {
      type: IntegrationSystemType.EXTERNAL,
    } as Partial<IntegrationSystem>);

    expect(validateAllowedSystemProtocol).toHaveBeenCalledWith(
      IntegrationSystemType.EXTERNAL,
      ApiSpecificationType.HTTP,
    );
  });

  test("throws when type is IMPLEMENTED but stored protocol is GRPC", async () => {
    (getMainService as jest.Mock).mockResolvedValue(
      buildServiceRecord(serviceId, { protocol: ApiSpecificationType.GRPC }),
    );

    await expect(
      updateService(serviceFileUri, serviceId, {
        type: IntegrationSystemType.IMPLEMENTED,
      } as Partial<IntegrationSystem>),
    ).rejects.toThrow(
      "Specification type is not allowed for implemented system: GRPC",
    );
  });

  test("skips validation entirely when type is not provided", async () => {
    (getMainService as jest.Mock).mockResolvedValue(
      buildServiceRecord(serviceId),
    );
    (getService as jest.Mock).mockResolvedValue({
      id: serviceId,
    } as IntegrationSystem);

    await updateService(serviceFileUri, serviceId, {
      name: "Updated Name",
    } as Partial<IntegrationSystem>);

    expect(validateAllowedSystemProtocol).not.toHaveBeenCalled();
  });
});
