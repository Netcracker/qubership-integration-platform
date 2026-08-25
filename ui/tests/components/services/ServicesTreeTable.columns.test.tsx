/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

import { describe, it, expect, beforeEach } from "@jest/globals";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import type {
  Api,
  ApiGroup,
  SystemOperation,
  OperationInfo,
} from "../../../src/api/apiTypes";
import {
  useServicesTreeTable,
  isSystemOperation,
  ServiceEntity,
} from "../../../src/components/services/ServicesTreeTable";
import {
  getColumnsOrderKey,
  getColumnsVisibleKey,
} from "../../../src/components/table/ColumnsFilter";

const mockGetOperationInfo = jest.fn<Promise<OperationInfo>, [string]>();
const mockNavigate = jest.fn();

jest.mock("../../../src/api/api", () => ({
  api: {
    getOperationInfo: (...args: [string]) => mockGetOperationInfo(...args),
  },
}));

// OperationInfoModal pulls in useSyntaxHighlighterTheme -> useVSCodeTheme, which
// probes window.postMessage; jsdom's postMessage doesn't accept the VS Code
// bridge's single-argument call, so stub the theme hook like other table tests do.
jest.mock("../../../src/hooks/useVSCodeTheme", () => ({
  useVSCodeTheme: () => ({
    isDark: false,
    colors: {},
    palette: {},
  }),
}));

jest.mock("react-router-dom", () => {
  const actual =
    jest.requireActual<Record<string, unknown>>("react-router-dom");
  return { ...actual, useNavigate: () => mockNavigate };
});

function makeGroup(overrides: Partial<ApiGroup> = {}): ApiGroup {
  return {
    id: "group-1",
    name: "Group One",
    systemId: "sys-1",
    synchronization: true,
    specifications: [],
    ...overrides,
  };
}

function makeSpec(overrides: Partial<Api> = {}): Api {
  return {
    id: "spec-1",
    name: "Spec One",
    specificationGroupId: "group-1",
    version: "1.0",
    source: "MANUAL",
    systemId: "sys-1",
    ...overrides,
  };
}

function makeOperation(
  overrides: Partial<SystemOperation> = {},
): SystemOperation {
  return {
    id: "op-1",
    name: "Operation One",
    method: "GET",
    path: "/foo",
    modelId: "spec-1",
    chains: [],
    ...overrides,
  };
}

function Harness({
  dataSource,
  columns,
  storageKey,
  defaultVisibleKeys,
}: {
  dataSource: ServiceEntity[];
  columns: string[];
  storageKey: string;
  defaultVisibleKeys?: string[];
}) {
  const { tableElement } = useServicesTreeTable<ServiceEntity>({
    dataSource,
    rowKey: "id",
    columns,
    allColumns: columns,
    defaultVisibleKeys: defaultVisibleKeys ?? columns,
    storageKey,
  });
  return tableElement;
}

function renderHarness(
  dataSource: ServiceEntity[],
  columns: string[],
  storageKey: string,
  defaultVisibleKeys?: string[],
) {
  return render(
    <MemoryRouter initialEntries={["/test"]}>
      <Routes>
        <Route
          path="/test"
          element={
            <Harness
              dataSource={dataSource}
              columns={columns}
              storageKey={storageKey}
              defaultVisibleKeys={defaultVisibleKeys}
            />
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

describe("API row: specificationType / specificationVersion columns", () => {
  const columns = ["name", "specificationType", "specificationVersion"];

  beforeEach(() => {
    localStorage.clear();
  });

  it("shows the format and format version when present", () => {
    const spec = makeSpec({
      specificationType: "OpenAPI",
      specificationVersion: "3.1",
    });
    renderHarness([spec], columns, "present");

    expect(screen.getByText("OpenAPI")).toBeInTheDocument();
    expect(screen.getByText("3.1")).toBeInTheDocument();
  });

  it("renders an empty cell instead of crashing when the fields are undefined", () => {
    const spec = makeSpec();
    renderHarness([spec], columns, "absent");

    expect(screen.getByText(spec.name)).toBeInTheDocument();
    expect(screen.queryByText("undefined")).not.toBeInTheDocument();
  });
});

describe("column settings: columns added after the settings were saved", () => {
  // The API level gained these two columns after users had already saved column settings.
  const allColumns = [
    "name",
    "protocol",
    "status",
    "specificationType",
    "specificationVersion",
  ];
  const columnsBeforeApiFormat = ["name", "protocol", "status"];
  const STORAGE_KEY = "servicesTreeReconcile";
  const orderKey = getColumnsOrderKey(STORAGE_KEY);
  const visibleKey = getColumnsVisibleKey(STORAGE_KEY);

  function storeSettings(order: string[], visible: string[]) {
    localStorage.setItem(orderKey, JSON.stringify(order));
    localStorage.setItem(visibleKey, JSON.stringify(visible));
  }

  function headerTitles(): string[] {
    return screen
      .getAllByRole("columnheader")
      .map((header) => header.textContent?.trim() ?? "");
  }

  function readStored(key: string): string[] {
    return JSON.parse(localStorage.getItem(key)!) as string[];
  }

  beforeEach(() => {
    localStorage.clear();
  });

  it("shows the new columns when the stored visibility list predates them", () => {
    storeSettings(columnsBeforeApiFormat, ["name", "protocol", "status"]);
    renderHarness([makeSpec()], allColumns, STORAGE_KEY);

    const titles = headerTitles();
    expect(titles).toContain("API Format");
    expect(titles).toContain("API Format Version");
    expect(titles).toContain("Protocol");
    expect(titles).toContain("Status");
  });

  it("keeps a column the user hid while showing the new ones", () => {
    storeSettings(columnsBeforeApiFormat, ["name", "protocol"]);
    renderHarness([makeSpec()], allColumns, STORAGE_KEY);

    const titles = headerTitles();
    expect(titles).not.toContain("Status");
    expect(titles).toContain("API Format");
    expect(titles).toContain("API Format Version");
  });

  it("keeps a hidden column hidden when the table is mounted again", () => {
    storeSettings(columnsBeforeApiFormat, ["name", "protocol"]);
    const { unmount } = renderHarness([makeSpec()], allColumns, STORAGE_KEY);
    unmount();
    renderHarness([makeSpec()], allColumns, STORAGE_KEY);

    expect(headerTitles()).not.toContain("Status");
    expect(readStored(visibleKey)).not.toContain("status");
  });

  it("does not show a new column that the defaults hide", () => {
    storeSettings(columnsBeforeApiFormat, ["name", "protocol", "status"]);
    renderHarness([makeSpec()], allColumns, STORAGE_KEY, [
      "name",
      "protocol",
      "status",
      "specificationType",
    ]);

    const titles = headerTitles();
    expect(titles).toContain("API Format");
    expect(titles).not.toContain("API Format Version");
  });

  // The picker reads localStorage when it opens, so the reconciliation has to be written back.
  it("writes the reconciled order and visibility back to storage", () => {
    storeSettings(columnsBeforeApiFormat, ["name", "protocol"]);
    renderHarness([makeSpec()], allColumns, STORAGE_KEY);

    expect(readStored(orderKey)).toEqual(allColumns);
    expect(readStored(visibleKey)).toEqual([
      "name",
      "protocol",
      "specificationType",
      "specificationVersion",
    ]);
  });

  it("uses the default visible columns and stores nothing when no settings are saved", () => {
    renderHarness([makeSpec()], allColumns, STORAGE_KEY, [
      "name",
      "protocol",
      "specificationType",
    ]);

    const titles = headerTitles();
    expect(titles).toEqual(["Name", "Protocol", "API Format"]);
    expect(localStorage.getItem(orderKey)).toBeNull();
    expect(localStorage.getItem(visibleKey)).toBeNull();
  });

  it("falls back to the defaults when the stored settings are unreadable", () => {
    localStorage.setItem(orderKey, "{not json");
    localStorage.setItem(visibleKey, "{not json");
    renderHarness([makeSpec()], allColumns, STORAGE_KEY, [
      "name",
      "protocol",
      "specificationType",
    ]);

    expect(headerTitles()).toEqual(["Name", "Protocol", "API Format"]);
  });
});

describe("catalog navigation: group -> API -> operation", () => {
  beforeEach(() => {
    localStorage.clear();
    mockNavigate.mockClear();
    mockGetOperationInfo.mockReset();
  });

  it("navigates to the API list when a group row is clicked", () => {
    const group = makeGroup();
    renderHarness([group], ["name"], "group-nav");

    fireEvent.click(screen.getByText(group.name));

    expect(mockNavigate).toHaveBeenCalledWith(
      `/services/systems/${group.systemId}/specificationGroups/${group.id}/specifications`,
    );
  });

  it("navigates to the operations list when an API row is clicked", () => {
    const spec = makeSpec();
    renderHarness([spec], ["name"], "api-nav");

    fireEvent.click(screen.getByText(spec.name));

    expect(mockNavigate).toHaveBeenCalledWith(
      `/services/systems/${spec.systemId}/specificationGroups/${spec.specificationGroupId}/specifications/${spec.id}/operations`,
    );
  });

  it("loads the operation info when an operation row is clicked and renders it in the viewer", async () => {
    const operation = makeOperation();
    mockGetOperationInfo.mockResolvedValue({
      id: operation.id,
      specification: { openapi: "3.0.0" },
      requestSchema: null as unknown as Record<string, unknown>,
      responseSchemas: null as unknown as Record<string, unknown>,
    });
    renderHarness([operation], ["name"], "operation-nav");

    fireEvent.click(screen.getByText(operation.name));

    await waitFor(() =>
      expect(mockGetOperationInfo).toHaveBeenCalledWith(operation.id),
    );

    expect(await screen.findByText("Operation info")).toBeInTheDocument();
    expect(screen.getByText("Specification")).toBeInTheDocument();
    expect(screen.getByText("Request schema")).toBeInTheDocument();
    expect(screen.getByText("Response schemas")).toBeInTheDocument();
  });
});

describe("operation row: typed field badges (NameCell)", () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it("shows the protocol, rpc method, and deprecated badges when present", () => {
    const operation = makeOperation({
      binding: "kafka",
      rpcMethod: "GetWidget",
      isDeprecated: true,
    });
    renderHarness([operation], ["name"], "badges-present");

    expect(screen.getByText("kafka")).toBeInTheDocument();
    expect(screen.getByText("GetWidget")).toBeInTheDocument();
    expect(screen.getByText("Deprecated")).toBeInTheDocument();
  });

  // The channel has a column of its own, so repeating it next to the name is
  // noise.
  it("does not repeat the channel next to the operation name", () => {
    const operation = makeOperation({
      binding: "kafka",
      channel: "widgets.updated",
    });
    renderHarness([operation], ["name"], "badges-no-channel");

    expect(screen.queryByText("widgets.updated")).not.toBeInTheDocument();
  });

  it("renders only the name, without crashing, when the typed fields are undefined", () => {
    const operation = makeOperation();
    renderHarness([operation], ["name"], "badges-absent");

    expect(screen.getByText(operation.name)).toBeInTheDocument();
    expect(screen.queryByText("Deprecated")).not.toBeInTheDocument();
    expect(screen.queryByText("undefined")).not.toBeInTheDocument();
  });

  it("falls back to operationType for the protocol badge when binding is absent", () => {
    const operation = makeOperation({ operationType: "unary" });
    renderHarness([operation], ["name"], "badges-operationtype");

    expect(screen.getByText("unary")).toBeInTheDocument();
  });

  it("recognizes an operation as a system operation when it has no path", () => {
    const { path: _path, ...withoutPath } = makeOperation({
      binding: "soap",
    });

    expect(isSystemOperation(withoutPath as ServiceEntity)).toBe(true);
  });

  it("renders the badges of an operation when it has no path (WSDL/degraded)", () => {
    const { path: _path, ...withoutPath } = makeOperation({
      binding: "soap",
      isDeprecated: true,
    });
    renderHarness([withoutPath as ServiceEntity], ["name"], "badges-no-path");

    expect(screen.getByText(withoutPath.name)).toBeInTheDocument();
    expect(screen.getByText("soap")).toBeInTheDocument();
    expect(screen.getByText("Deprecated")).toBeInTheDocument();
  });
});
