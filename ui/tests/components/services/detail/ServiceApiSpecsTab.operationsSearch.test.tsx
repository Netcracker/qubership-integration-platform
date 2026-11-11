/**
 * @jest-environment jsdom
 */
import React, { useCallback, useState } from "react";
import { describe, it, expect, beforeEach, jest } from "@jest/globals";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import {
  IntegrationSystem,
  IntegrationSystemType,
  SystemOperation,
} from "../../../../src/api/apiTypes";
import {
  ServiceContext,
  ServiceParametersToolbarContext,
} from "../../../../src/components/services/detail/ServiceParametersPage";
import { ServiceApiSpecsTab } from "../../../../src/components/services/detail/ServiceApiSpecsTab";

const mockGetApiSpecifications =
  jest.fn<(...args: unknown[]) => Promise<unknown[]>>();
const mockGetSpecificationModel =
  jest.fn<(...args: unknown[]) => Promise<unknown[]>>();
const mockGetOperations = jest.fn<(...args: unknown[]) => Promise<unknown[]>>();
const mockExportServices = jest
  .fn<(...args: unknown[]) => Promise<File>>()
  .mockResolvedValue(new File([], "x"));
const mockExportSpecifications = jest
  .fn<(...args: unknown[]) => Promise<File>>()
  .mockResolvedValue(new File([], "x"));
const mockRequestFailed = jest.fn<(...args: unknown[]) => void>();

let isVsCodeFlag = false;

type TreeTableMock = {
  tableElement: React.ReactNode;
  FilterButton: () => React.ReactNode;
};

const mockUseServicesTreeTable = jest.fn<(options: unknown) => TreeTableMock>();
mockUseServicesTreeTable.mockImplementation(() => ({
  tableElement: <div data-testid="mock-tree-table" />,
  FilterButton: () => (
    <button type="button" data-testid="mock-columns-filter" />
  ),
}));

jest.mock("../../../../src/api/api", () => ({
  api: {
    getApiSpecifications: (...args: unknown[]) =>
      mockGetApiSpecifications(...args),
    getSpecificationModel: (...args: unknown[]) =>
      mockGetSpecificationModel(...args),
    getOperations: (...args: unknown[]) => mockGetOperations(...args),
    exportServices: (...args: unknown[]) => mockExportServices(...args),
    exportSpecifications: (...args: unknown[]) =>
      mockExportSpecifications(...args),
    updateApiSpecificationGroup: jest.fn(),
    updateSpecificationModel: jest.fn(),
  },
}));

jest.mock("../../../../src/api/rest/vscodeExtensionApi.ts", () => ({
  get isVsCode() {
    return isVsCodeFlag;
  },
}));

jest.mock("../../../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: jest.fn() }),
}));

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
    info: jest.fn(),
  }),
}));

jest.mock("../../../../src/misc/download-utils", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../../../src/components/services/utils.tsx", () => {
  const actual = jest.requireActual<Record<string, unknown>>(
    "../../../../src/components/services/utils.tsx",
  );
  return { ...actual, invalidateServiceCache: jest.fn() };
});

jest.mock("../../../../src/permissions/ProtectedButton.tsx", () => ({
  ProtectedButton: () => null,
}));

jest.mock("../../../../src/components/services/ServicesTreeTable", () => {
  const actual = jest.requireActual<Record<string, unknown>>(
    "../../../../src/components/services/ServicesTreeTable",
  );
  return {
    ...actual,
    useServicesTreeTable: (options: unknown) =>
      mockUseServicesTreeTable(options),
  };
});

const opGet: SystemOperation = {
  id: "op-get",
  name: "List users",
  method: "GET",
  path: "/users",
  modelId: "spec-1",
  chains: [],
};
const opPost: SystemOperation = {
  id: "op-post",
  name: "Create user",
  method: "POST",
  path: "/users",
  modelId: "spec-1",
  chains: [],
};
const opServer: SystemOperation = {
  id: "op-server",
  name: "Server result",
  method: "GET",
  path: "/server",
  modelId: "spec-1",
  chains: [],
};

function makeSystem(): IntegrationSystem {
  return {
    id: "sys-1",
    name: "S",
    type: IntegrationSystemType.IMPLEMENTED,
    activeEnvironmentId: "e",
    internalServiceName: "i",
    protocol: "http",
    extendedProtocol: "",
    specification: "",
  } as IntegrationSystem;
}

function operationsDataSource(): unknown {
  const call = [...mockUseServicesTreeTable.mock.calls]
    .reverse()
    .find(
      (c) =>
        (c[0] as { storageKey?: string }).storageKey ===
        "systemParameters_operations",
    );
  return (call?.[0] as { dataSource?: unknown } | undefined)?.dataSource;
}

function ToolbarOutletShell({ children }: { children: React.ReactNode }) {
  const [toolbar, setToolbarNode] = useState<React.ReactNode>(null);
  const setToolbar = useCallback(
    (_owner: string, node: React.ReactNode) => setToolbarNode(node),
    [],
  );
  return (
    <ServiceParametersToolbarContext.Provider value={{ setToolbar }}>
      {children}
      <div data-testid="toolbar-outlet">{toolbar}</div>
    </ServiceParametersToolbarContext.Provider>
  );
}

function renderTab() {
  return render(
    <MemoryRouter
      initialEntries={[
        "/services/systems/sys-1/specificationGroups/g-1/specifications/spec-1/operations",
      ]}
    >
      <ServiceContext.Provider value={makeSystem()}>
        <ToolbarOutletShell>
          <Routes>
            <Route
              path="/services/systems/:systemId/specificationGroups/:groupId/specifications/:specId/operations"
              element={<ServiceApiSpecsTab />}
            />
          </Routes>
        </ToolbarOutletShell>
      </ServiceContext.Provider>
    </MemoryRouter>,
  );
}

function searchInput(): HTMLElement {
  return screen.getByPlaceholderText("Search operations...");
}

describe("ServiceApiSpecsTab operations search", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    isVsCodeFlag = false;
    mockGetApiSpecifications.mockResolvedValue([]);
    mockGetSpecificationModel.mockResolvedValue([
      { id: "spec-1", operations: [opGet, opPost] },
    ]);
    mockGetOperations.mockResolvedValue([opServer]);
  });

  it("trims the term, requests the catalog, renders its result, and restores the model operations when cleared", async () => {
    renderTab();
    const input = await waitFor(() => searchInput());

    await waitFor(() =>
      expect(operationsDataSource()).toEqual([opGet, opPost]),
    );

    fireEvent.change(input, { target: { value: "  post  " } });

    await waitFor(() =>
      expect(mockGetOperations).toHaveBeenCalledWith("spec-1", {
        count: 0,
        searchFilter: "post",
      }),
    );
    await waitFor(() => expect(operationsDataSource()).toEqual([opServer]));

    fireEvent.change(input, { target: { value: "" } });

    await waitFor(() =>
      expect(operationsDataSource()).toEqual([opGet, opPost]),
    );
    expect(mockGetOperations).toHaveBeenCalledTimes(1);
  });

  it("filters the model operations locally without a catalog request in VS Code", async () => {
    isVsCodeFlag = true;
    renderTab();
    const input = await waitFor(() => searchInput());

    await waitFor(() =>
      expect(operationsDataSource()).toEqual([opGet, opPost]),
    );

    fireEvent.change(input, { target: { value: "GET /users" } });

    await waitFor(() => expect(operationsDataSource()).toEqual([opGet]));

    fireEvent.change(input, { target: { value: "post" } });

    await waitFor(() => expect(operationsDataSource()).toEqual([opPost]));
    expect(mockGetOperations).not.toHaveBeenCalled();
  });

  it("clears the operations and reports the failure when the catalog request rejects", async () => {
    const error = new Error("boom");
    mockGetOperations.mockRejectedValue(error);
    renderTab();
    const input = await waitFor(() => searchInput());

    fireEvent.change(input, { target: { value: "post" } });

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to load operations",
        error,
      ),
    );
    expect(operationsDataSource()).toEqual([]);
  });
});
