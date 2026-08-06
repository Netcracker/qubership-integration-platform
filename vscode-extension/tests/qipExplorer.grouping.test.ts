// The services branch of the tree. Grouping is where a service goes missing quietly: a name the
// discovery loop does not recognize, or a type no group claims, drops the service from the tree
// without an error anywhere. Every case below asserts on the tree the provider actually returns.
//
// The "service type precedence" suite covers the rule the tree groups by: the file name wins,
// `content.integrationSystemType` is the fallback for the legacy type-less name, and a file that
// states a type in neither stays visible under Unknown.

import { joinUriPath } from "./helpers/mocks";

const FILE = 1;
const DIRECTORY = 2;

let directories: Record<string, [string, number][]> = {};
let fileContents: Record<string, unknown> = {};

jest.mock(
  "vscode",
  () => ({
    __esModule: true,
    EventEmitter: class {
      event = jest.fn();
      fire = jest.fn();
    },
    TreeItem: class {
      description?: string;
      iconPath?: unknown;
      contextValue?: string;
      tooltip?: string;
      command?: unknown;
      constructor(
        public label: string,
        public collapsibleState: number,
      ) {}
    },
    ThemeIcon: class {
      constructor(public id: string) {}
    },
    TreeItemCollapsibleState: { None: 0, Collapsed: 1, Expanded: 2 },
    FileType: { File: 1, Directory: 2 },
    Uri: {
      joinPath: (base: { path: string }, ...segments: string[]) =>
        joinUriPath(base, ...segments),
    },
    workspace: {
      workspaceFolders: [{ uri: { path: "/workspace", fsPath: "/workspace" } }],
    },
  }),
  { virtual: true },
);

jest.mock("../src/web/services/ProjectConfigService", () => ({
  ProjectConfigService: {
    getInstance: jest.fn(() => ({
      isConfigLoaded: jest.fn(() => false),
      getAllConfigs: jest.fn(() => []),
    })),
  },
}));

jest.mock("../src/web/response/file/fileApiImpl", () => ({
  readDirectory: jest.fn(
    async (uri: { path: string }) => directories[uri.path] ?? [],
  ),
}));

jest.mock("../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: {
    parseContentFromFile: jest.fn(
      async (uri: { path: string }) => fileContents[uri.path],
    ),
  },
}));

import { QipExplorerProvider, QipExplorerItem } from "../src/web/qipExplorer";
import { setDefaultAppName } from "../src/web/response/file/fileExtensions";

type ServiceContent = {
  id: string;
  name: string;
  content: { protocol: string; integrationSystemType?: string };
};

/** Lays out a workspace from full file paths, keeping the listed order as the discovery order. */
function buildWorkspace(files: { path: string; data?: unknown }[]): void {
  directories = {};
  fileContents = {};
  for (const { path, data } of files) {
    const segments = path.split("/").filter(Boolean);
    let parent = "";
    segments.forEach((segment, index) => {
      const entries = (directories[parent] = directories[parent] ?? []);
      if (!entries.some(([name]) => name === segment)) {
        entries.push([
          segment,
          index === segments.length - 1 ? FILE : DIRECTORY,
        ]);
      }
      parent = `${parent}/${segment}`;
    });
    fileContents[path] = data;
  }
}

function service(id: string, type?: string, name = id): ServiceContent {
  return {
    id,
    name,
    content: {
      protocol: "HTTP",
      ...(type ? { integrationSystemType: type } : {}),
    },
  };
}

const provider = () => new QipExplorerProvider({} as never);

const servicesCategory: QipExplorerItem = {
  id: "services-category",
  label: "Services",
  contextValue: "qip-services-category",
  collapsibleState: 1,
  type: "category",
};

/** The groups as the tree renders them: the "Services" category expanded one level. */
async function listGroups(): Promise<QipExplorerItem[]> {
  return provider().getChildren(servicesCategory);
}

async function listServices(): Promise<QipExplorerItem[]> {
  return (await listGroups()).flatMap((group) => group.children ?? []);
}

beforeEach(() => {
  setDefaultAppName("qip");
  directories = {};
  fileContents = {};
});

describe("service discovery", () => {
  test.each([
    ["external-service"],
    ["internal-service"],
    ["implemented-service"],
    ["context-service"],
    ["mcp-service"],
    ["service"],
  ])("lists a service stored under a .%s. name", async (postfix) => {
    buildWorkspace([
      {
        path: `/workspace/svc.${postfix}.qip.yaml`,
        data: service("svc"),
      },
    ]);

    expect((await listServices()).map((item) => item.id)).toEqual(["svc"]);
  });

  test("ignores files that are not service files", async () => {
    buildWorkspace([
      { path: "/workspace/some.chain.qip.yaml", data: service("chain") },
      { path: "/workspace/some.api.qip.yaml", data: service("api") },
      { path: "/workspace/notes.yaml", data: service("notes") },
    ]);

    expect(await listGroups()).toEqual([]);
  });

  test("finds services in nested folders", async () => {
    buildWorkspace([
      {
        path: "/workspace/services/one/one.external-service.qip.yaml",
        data: service("one", "EXTERNAL"),
      },
      {
        path: "/workspace/services/two/deeper/two.mcp-service.qip.yaml",
        data: service("two"),
      },
    ]);

    expect((await listServices()).map((item) => item.id).sort()).toEqual([
      "one",
      "two",
    ]);
  });
});

describe("service grouping", () => {
  test("puts every kind in its own group, in a fixed order", async () => {
    buildWorkspace([
      { path: "/workspace/mcp.mcp-service.qip.yaml", data: service("mcp") },
      {
        path: "/workspace/int.internal-service.qip.yaml",
        data: service("int", "INTERNAL"),
      },
      {
        path: "/workspace/ext.external-service.qip.yaml",
        data: service("ext", "EXTERNAL"),
      },
      { path: "/workspace/ctx.context-service.qip.yaml", data: service("ctx") },
      {
        path: "/workspace/impl.implemented-service.qip.yaml",
        data: service("impl", "IMPLEMENTED"),
      },
    ]);

    const groups = await listGroups();

    expect(groups.map((group) => group.label)).toEqual([
      "External",
      "Internal",
      "Implemented",
      "Context",
      "MCP",
    ]);
    expect(
      groups.map((group) => group.children?.map((item) => item.id)),
    ).toEqual([["ext"], ["int"], ["impl"], ["ctx"], ["mcp"]]);
    expect(groups.every((group) => group.type === "service-group")).toBe(true);
  });

  test("omits a group that holds no service", async () => {
    buildWorkspace([
      {
        path: "/workspace/ext.external-service.qip.yaml",
        data: service("ext", "EXTERNAL"),
      },
    ]);

    const groups = await listGroups();

    expect(groups.map((group) => group.label)).toEqual(["External"]);
  });

  test("sorts services by label inside a group", async () => {
    buildWorkspace([
      {
        path: "/workspace/c.external-service.qip.yaml",
        data: service("c", "EXTERNAL", "charlie"),
      },
      {
        path: "/workspace/a.external-service.qip.yaml",
        data: service("a", "EXTERNAL", "alpha"),
      },
      {
        path: "/workspace/b.external-service.qip.yaml",
        data: service("b", "EXTERNAL", "bravo"),
      },
    ]);

    const [external] = await listGroups();

    expect(external.children?.map((item) => item.label)).toEqual([
      "alpha-HTTP-a",
      "bravo-HTTP-b",
      "charlie-HTTP-c",
    ]);
  });

  test("counts its services on the group", async () => {
    buildWorkspace([
      {
        path: "/workspace/one.external-service.qip.yaml",
        data: service("one", "EXTERNAL"),
      },
      {
        path: "/workspace/two.external-service.qip.yaml",
        data: service("two", "EXTERNAL"),
      },
      {
        path: "/workspace/three.internal-service.qip.yaml",
        data: service("three", "INTERNAL"),
      },
    ]);

    expect((await listGroups()).map((group) => group.description)).toEqual([
      "2 services",
      "1 service",
    ]);
  });
});

describe("service type precedence", () => {
  test.each([
    ["external-service", "EXTERNAL", "External"],
    ["internal-service", "INTERNAL", "Internal"],
    ["implemented-service", "IMPLEMENTED", "Implemented"],
    ["context-service", "CONTEXT", "Context"],
    ["mcp-service", "MCP", "MCP"],
  ])(
    "groups a .%s. file from its name when the body states no type",
    async (postfix, type, label) => {
      buildWorkspace([
        {
          path: `/workspace/svc.${postfix}.qip.yaml`,
          data: service("svc"),
        },
      ]);

      const groups = await listGroups();

      expect(groups.map((group) => group.label)).toEqual([label]);
      expect(groups[0].children?.[0].description).toBe(`${type} service`);
    },
  );

  test.each([
    ["external-service", "INTERNAL", "EXTERNAL", "External"],
    ["internal-service", "IMPLEMENTED", "INTERNAL", "Internal"],
    ["implemented-service", "EXTERNAL", "IMPLEMENTED", "Implemented"],
    ["context-service", "EXTERNAL", "CONTEXT", "Context"],
    ["mcp-service", "INTERNAL", "MCP", "MCP"],
  ])(
    "groups a .%s. file by its name when the body claims another type",
    async (postfix, bodyType, type, label) => {
      buildWorkspace([
        {
          path: `/workspace/svc.${postfix}.qip.yaml`,
          data: service("svc", bodyType),
        },
      ]);

      const groups = await listGroups();

      expect(groups.map((group) => group.label)).toEqual([label]);
      expect(groups[0].children?.[0].description).toBe(`${type} service`);
    },
  );

  test("labels a context file from its name, not from the type its body claims", async () => {
    buildWorkspace([
      {
        path: "/workspace/ctx.context-service.qip.yaml",
        data: service("ctx", "EXTERNAL"),
      },
    ]);

    const [context] = await listGroups();

    // A context service carries no protocol in its label.
    expect(context.children?.[0].label).toBe("ctx-ctx");
  });

  test("groups a legacy service file from its integrationSystemType field", async () => {
    buildWorkspace([
      {
        path: "/workspace/legacy.service.qip.yaml",
        data: service("legacy", "INTERNAL"),
      },
    ]);

    const groups = await listGroups();

    expect(groups.map((group) => group.label)).toEqual(["Internal"]);
    expect(groups[0].children?.[0].id).toBe("legacy");
  });

  test("keeps a service of no recognizable type under Unknown", async () => {
    buildWorkspace([
      { path: "/workspace/bare.service.qip.yaml", data: service("bare") },
      {
        path: "/workspace/odd.service.qip.yaml",
        data: service("odd", "NONSENSE"),
      },
    ]);

    const groups = await listGroups();

    expect(groups.map((group) => group.label)).toEqual(["Unknown"]);
    expect(groups[0].children?.map((item) => item.id).sort()).toEqual([
      "bare",
      "odd",
    ]);
  });

  test("keeps a file the parser read no type from under Unknown", async () => {
    buildWorkspace([
      {
        path: "/workspace/broken.service.qip.yaml",
        data: { id: "broken", name: "broken" },
      },
    ]);

    const groups = await listGroups();

    expect(groups.map((group) => group.label)).toEqual(["Unknown"]);
    expect(groups[0].children?.[0].id).toBe("broken");
    expect(groups[0].children?.[0].description).toBe("Unknown service");
  });
});

describe("group nodes", () => {
  beforeEach(() => {
    buildWorkspace([
      {
        path: "/workspace/ext.external-service.qip.yaml",
        data: service("ext", "EXTERNAL"),
      },
    ]);
  });

  test("carry no fileUri, so no reveal command is attached", async () => {
    const [external] = await listGroups();

    expect(external.fileUri).toBeUndefined();
    expect(provider().getTreeItem(external).command).toBeUndefined();
    expect(provider().getTreeItem(external.children![0]).command).toBeDefined();
  });

  test("return their services as children", async () => {
    const [external] = await listGroups();

    expect(await provider().getChildren(external)).toBe(external.children);
  });

  test("reuse the icon of the services they hold", async () => {
    const [external] = await listGroups();

    expect(external.iconPath).toEqual(external.children![0].iconPath);
  });
});

// A conversion writes the typed file before the legacy one is gone, and a failed delete leaves both
// for good. Without a dedup the same service is listed twice, under two different groups, because
// the legacy sibling still states its type in the body.
describe("a service with both a typed and a legacy file", () => {
  const typedPath = "/workspace/svc/svc.internal-service.qip.yaml";
  const legacyPath = "/workspace/svc/svc.service.qip.yaml";

  test.each([
    ["typed first", [typedPath, legacyPath]],
    ["legacy first", [legacyPath, typedPath]],
  ])("is listed once, from the typed file (%s)", async (_, order) => {
    buildWorkspace(
      order.map((path) => ({
        path,
        data: service("svc", path === legacyPath ? "EXTERNAL" : undefined),
      })),
    );

    const groups = await listGroups();

    expect(groups.map((group) => group.label)).toEqual(["Internal"]);
    expect(groups[0].children).toHaveLength(1);
    expect(groups[0].children![0].fileUri?.path).toBe(typedPath);
  });

  test("keeps two different services apart", async () => {
    buildWorkspace([
      {
        path: "/workspace/one/one.internal-service.qip.yaml",
        data: service("one"),
      },
      {
        path: "/workspace/two/two.service.qip.yaml",
        data: service("two", "EXTERNAL"),
      },
    ]);

    expect((await listServices()).map((item) => item.id).sort()).toEqual([
      "one",
      "two",
    ]);
  });
});
