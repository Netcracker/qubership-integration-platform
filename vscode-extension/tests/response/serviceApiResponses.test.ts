import { createVscodeMock, stubFileApi } from "../helpers/mocks";

jest.mock(
  "vscode",
  () => {
    const vscode = createVscodeMock();
    return { __esModule: true, ...vscode, default: vscode };
  },
  { virtual: true },
);
jest.mock("yaml", () => ({ stringify: (o: unknown) => JSON.stringify(o) }));
jest.mock("../../src/web/response/file/fileApiProvider", () =>
  stubFileApi({
    getMainService: jest.fn(),
    findAndBuildChainsRecursively: jest.fn(),
    getRootDirectory: jest.fn(),
    getFileCreatedWhen: jest.fn(),
    findFileById: jest.fn(),
  }),
);
jest.mock("../../src/web/extension", () => ({ refreshQipExplorer: jest.fn() }));
jest.mock("../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile: jest.fn() },
}));
jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });

import {
  getApiSpecifications,
  getEnvironment,
  getEnvironments,
  getService,
} from "../../src/web/response/serviceApiRead";
import {
  createEnvironment,
  updateSpecificationModel,
} from "../../src/web/response/serviceApiModify";
import { SystemService } from "../../src/web/api-services/SystemService";
import { SpecificationGroupService } from "../../src/web/api-services/SpecificationGroupService";
import { fileApi } from "../../src/web/response/file/fileApiProvider";
import { ContentParser } from "../../src/web/api-services/parsers/ContentParser";

const serviceFileUri = { path: "/ws/svc-1.service.cip.yaml" } as any;

const files: Record<string, any> = {
  "grp-1.qip-group.yaml": {
    id: "grp-1",
    name: "Group",
    content: { parentId: "svc-1", synchronization: false, labels: [] },
  },
  "spec-1.qip-spec.yaml": {
    id: "spec-1",
    name: "1.0",
    content: { parentId: "grp-1", version: "1.0", labels: [], operations: [] },
  },
};

const chain = {
  id: "chain-1",
  name: "Chain",
  content: {
    description: "Calls the service",
    elements: [
      {
        properties: {
          integrationSystemId: "svc-1",
          integrationSpecificationGroupId: "grp-1",
          integrationSpecificationId: "spec-1",
        },
      },
    ],
  },
};

function storedService() {
  return {
    id: "svc-1",
    name: "Service",
    content: {
      integrationSystemType: "EXTERNAL",
      environments: [{ id: "env-1", name: "Env", address: "http://host" }],
      labels: [],
    },
  };
}

beforeEach(() => {
  jest.clearAllMocks();
  const read = async (uri: { path: string }) =>
    structuredClone(files[uri.path.split("/").pop() as string]);
  (fileApi.getMainService as jest.Mock).mockImplementation(async () =>
    storedService(),
  );
  (fileApi.parseFile as jest.Mock).mockImplementation(read);
  (ContentParser.parseContentFromFile as jest.Mock).mockImplementation(read);
  (fileApi.getSpecificationGroupFiles as jest.Mock).mockResolvedValue([
    "grp-1.qip-group.yaml",
  ]);
  (fileApi.getSpecificationFiles as jest.Mock).mockResolvedValue([
    "spec-1.qip-spec.yaml",
  ]);
  (fileApi.findAndBuildChainsRecursively as jest.Mock).mockImplementation(
    async (_root: unknown, build: (c: unknown) => unknown, out: unknown[]) => {
      const built = build(chain);
      if (built) {
        out.push(built);
      }
    },
  );
});

// The catalog's EnvironmentDTO carries the service id, and the shared Environment type requires it.
describe("environments carry the id of their service", () => {
  test("getEnvironments", async () => {
    const [environment] = await getEnvironments(serviceFileUri, "svc-1");

    expect(environment.systemId).toBe("svc-1");
  });

  test("getEnvironments returns none for a service that stores none", async () => {
    (fileApi.getMainService as jest.Mock).mockResolvedValue({
      id: "svc-1",
      content: {},
    });

    expect(await getEnvironments(serviceFileUri, "svc-1")).toEqual([]);
  });

  test("getEnvironment", async () => {
    const environment = await getEnvironment(serviceFileUri, "svc-1", "env-1");

    expect(environment.systemId).toBe("svc-1");
  });

  test("createEnvironment", async () => {
    const environment = await createEnvironment(serviceFileUri, "svc-1", {
      name: "New",
    });

    expect(environment.systemId).toBe("svc-1");
    expect(environment.sourceType).toBe("MANUAL");
  });
});

describe("responses carry no field that only the files have", () => {
  test("a service", async () => {
    const service = await getService(serviceFileUri, "svc-1");

    expect(service).not.toHaveProperty("integrationSystemType");
    expect(service).not.toHaveProperty("environments");
    expect(service.type).toBe("EXTERNAL");
  });

  test("a group and its specification", async () => {
    const [group] = await getApiSpecifications(serviceFileUri, "svc-1");
    const [specification] = group.specifications;

    expect(group).not.toHaveProperty("parentId");
    expect(group).not.toHaveProperty("description");
    for (const field of ["parentId", "format", "content"]) {
      expect(specification).not.toHaveProperty(field);
    }
  });
});

test("chain references carry the chain's description", async () => {
  const [group] = await getApiSpecifications(serviceFileUri, "svc-1");
  const reference = {
    id: "chain-1",
    name: "Chain",
    description: "Calls the service",
  };

  expect(group.chains).toEqual([reference]);
  expect(group.specifications[0].chains).toEqual([reference]);
});

// The UI spreads the specification it read into the update request.
test("a label edit adds no format or content to a specification that stores none", async () => {
  const [group] = await getApiSpecifications(serviceFileUri, "svc-1");
  const [specification] = group.specifications;

  await updateSpecificationModel(serviceFileUri, "spec-1", {
    ...specification,
    labels: [{ name: "edited", technical: false }],
  });

  const bytes = (fileApi.writeFile as jest.Mock).mock.calls[0][1];
  const written = JSON.parse(new TextDecoder().decode(bytes));
  expect(written.content).not.toHaveProperty("format");
  expect(written.content).not.toHaveProperty("content");
});

test("SystemService saves a system without labels as an empty list", async () => {
  const systemService = new SystemService();
  const system = await systemService.getSystemById("svc-1");

  await systemService.saveSystem({ ...system!, labels: undefined });

  const written = (fileApi.writeMainService as jest.Mock).mock.calls[0][1];
  expect(written.content.labels).toEqual([]);
  expect(written.content.integrationSystemType).toBe("EXTERNAL");
});

// A group file keeps its service id in content.parentId; older files keep it at the top level.
test.each([
  ["content.parentId", { content: { parentId: "svc-1" } }],
  ["a top-level parentId", { parentId: "svc-1", content: {} }],
])(
  "SpecificationGroupService takes the service id from %s",
  async (_case, stored) => {
    (ContentParser.parseContentFromFile as jest.Mock).mockResolvedValue({
      id: "grp-1",
      name: "Group",
      ...stored,
    });

    const group =
      await new SpecificationGroupService().getSpecificationGroupById(
        "grp-1",
        "svc-1",
      );

    expect(group?.systemId).toBe("svc-1");
  },
);
