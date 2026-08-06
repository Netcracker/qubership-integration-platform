import {
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
  stubProjectConfigService,
  buildServiceRecord,
} from "../helpers/mocks";

jest.mock(
  "vscode",
  () => {
    // serviceApiModify uses both the default (`vscode.Uri` / `vscode.window`)
    // and named (`Uri`) imports, so expose the mock as both.
    const api = createVscodeMock();
    return { __esModule: true, default: api, ...api };
  },
  { virtual: true },
);
jest.mock("yaml", () => ({ stringify: jest.fn(), parse: jest.fn() }));
jest.mock("../../src/web/response/file/fileApiProvider", () => stubFileApi());
jest.mock("../../src/web/response/serviceApiRead", () => ({
  getMainService: jest.fn(),
  getService: jest.fn(),
  getContextService: jest.fn(),
}));
jest.mock("../../src/web/response/file/fileExtensions", () => {
  const { QIP_FILE_EXTENSIONS } = jest.requireActual("../helpers/mocks");
  return {
    getExtensionsForFile: jest.fn().mockReturnValue(QIP_FILE_EXTENSIONS),
    getExtensionsForUri: jest.fn().mockReturnValue(QIP_FILE_EXTENSIONS),
    extractFilename: jest.fn(
      (fileRef: any) =>
        (typeof fileRef === "string" ? fileRef : fileRef.path)
          .split("/")
          .pop() ?? "",
    ),
  };
});
jest.mock("../../src/web/extension", () => ({ refreshQipExplorer: jest.fn() }));
jest.mock("../../src/web/api-services/LabelUtils", () => stubLabelUtils());
jest.mock("../../src/web/services/ProjectConfigService", () =>
  stubProjectConfigService(),
);
jest.mock("../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile: jest.fn() },
}));
const regenerateGroupApisSafely = jest.fn();
const resolveGroupFile = jest.fn();
jest.mock("../../src/web/api-services/ApiGroupService", () => ({
  ApiGroupService: { regenerateGroupApisSafely, resolveGroupFile },
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
import {
  updateService,
  updateSpecificationModel,
  deprecateModel,
  deleteSpecificationGroup,
} from "../../src/web/response/serviceApiModify";
import {
  getMainService,
  getService,
} from "../../src/web/response/serviceApiRead";
import { validateAllowedSystemProtocol } from "../../src/web/response/serviceApiUtils";
import { fileApi } from "../../src/web/response/file/fileApiProvider";
import { ContentParser } from "../../src/web/api-services/parsers/ContentParser";

describe("updateService – validateAllowedSystemProtocol integration", () => {
  const serviceId = "svc-1";
  const serviceFileUri = {
    path: `/svc-1/${serviceId}.service.qip.yaml`,
  } as any;

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

// apis[] is derived from each API file's parentId. Every writer must rebuild it
// so a stale or hand-edited list is corrected on the next write. Removing any of
// these hooks must fail a test.
describe("apis[] regeneration wiring after a model write", () => {
  const serviceFileUri = { path: "/svc/service.qip.yaml" } as any;
  const MODEL_ID = "model-1";
  const GROUP_ID = "group-1";
  const SPEC_FILE = "model-1.api.qip.yaml";

  beforeEach(() => {
    jest.clearAllMocks();
    (fileApi.getSpecificationFiles as jest.Mock).mockResolvedValue([SPEC_FILE]);
    (ContentParser.parseContentFromFile as jest.Mock).mockResolvedValue({
      id: MODEL_ID,
      name: "Model One",
      content: { parentId: GROUP_ID },
    });
    (fileApi.writeFile as jest.Mock).mockResolvedValue(undefined);
  });

  test("updateSpecificationModel rebuilds the group apis[]", async () => {
    await updateSpecificationModel(serviceFileUri, MODEL_ID, {
      description: "updated",
    });

    expect(regenerateGroupApisSafely).toHaveBeenCalledWith(
      serviceFileUri,
      GROUP_ID,
    );
  });

  test("deprecateModel rebuilds the group apis[]", async () => {
    await deprecateModel(serviceFileUri, MODEL_ID);

    expect(regenerateGroupApisSafely).toHaveBeenCalledWith(
      serviceFileUri,
      GROUP_ID,
    );
  });
});

// A group can have a file under both group extensions. Leaving one behind resurrects the group on the next
// read, with its APIs already deleted.
describe("deleteSpecificationGroup removes every file carrying the group id", () => {
  const serviceFileUri = { path: "/svc/service.qip.yaml" } as any;
  const GROUP_ID = "group-1";
  const GROUP_FILE = "group-1.api-group.qip.yaml";
  const LEGACY_GROUP_FILE = "group-1.specification-group.qip.yaml";

  beforeEach(() => {
    jest.clearAllMocks();
    (getMainService as jest.Mock).mockResolvedValue(
      buildServiceRecord("svc-1"),
    );
    (fileApi.getSpecificationFiles as jest.Mock).mockResolvedValue([]);
    (fileApi.deleteFile as jest.Mock).mockResolvedValue(undefined);
    resolveGroupFile.mockResolvedValue({
      fileName: GROUP_FILE,
      info: { id: GROUP_ID, name: "Group One" },
      duplicates: [LEGACY_GROUP_FILE],
    });
  });

  test("deletes the resolved file and its pre-rename sibling", async () => {
    await deleteSpecificationGroup(serviceFileUri, GROUP_ID);

    const deleted = (fileApi.deleteFile as jest.Mock).mock.calls.map(
      ([uri]) => uri.path,
    );
    expect(deleted).toEqual([GROUP_FILE, LEGACY_GROUP_FILE]);
  });

  test("throws when no file carries the group id", async () => {
    resolveGroupFile.mockResolvedValue(null);

    await expect(
      deleteSpecificationGroup(serviceFileUri, GROUP_ID),
    ).rejects.toThrow(GROUP_ID);
  });
});
