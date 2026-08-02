/**
 * @jest-environment jsdom
 */
import React, { useCallback, useState } from "react";
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import {
  IntegrationSystem,
  IntegrationSystemType,
  Api,
  ApiGroup,
} from "../../../../src/api/apiTypes";
import {
  ServiceContext,
  ServiceParametersToolbarContext,
} from "../../../../src/components/services/detail/ServiceParametersPage";
import {
  ServiceApiSpecsTab,
  getGroupActions,
  getSpecActions,
} from "../../../../src/components/services/detail/ServiceApiSpecsTab";
import type { useNotificationService } from "../../../../src/hooks/useNotificationService";

jest.mock("../../../../src/api/api", () => ({
  api: {
    getApiSpecifications: jest.fn().mockResolvedValue([] as never),
    getSpecificationModel: jest.fn().mockResolvedValue([] as never),
    exportServices: jest.fn().mockResolvedValue(new File([], "x") as never),
    exportSpecifications: jest
      .fn()
      .mockResolvedValue(new File([], "x") as never),
    updateApiSpecificationGroup: jest.fn(),
    updateSpecificationModel: jest.fn(),
  },
}));

jest.mock("../../../../src/api/rest/vscodeExtensionApi.ts", () => ({
  isVsCode: false,
}));

jest.mock("../../../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: jest.fn() }),
}));

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: jest.fn(),
    errorWithDetails: jest.fn(),
    info: jest.fn(),
    warning: jest.fn(),
  }),
}));

jest.mock("../../../../src/misc/download-utils", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../../../src/components/services/utils.tsx", () => {
  const actual = jest.requireActual<Record<string, unknown>>(
    "../../../../src/components/services/utils.tsx",
  );
  return {
    ...actual,
    invalidateServiceCache: jest.fn(),
  };
});

jest.mock("../../../../src/permissions/ProtectedButton.tsx", () => ({
  ProtectedButton: ({
    buttonProps,
    tooltipProps,
  }: {
    buttonProps: Record<string, unknown> & { type?: string };
    tooltipProps: { title: string };
  }) => {
    const { iconName: _i, icon: _n, onClick, type, ...rest } = buttonProps;
    const testId = `api-specs-action-${String(tooltipProps.title)
      .replace(/\s+/g, "-")
      .toLowerCase()}`;
    return (
      <button
        type="button"
        data-testid={testId}
        data-button-type={type === "primary" ? "primary" : "default"}
        onClick={onClick as () => void}
        {...rest}
      />
    );
  },
}));

jest.mock("../../../../src/components/services/ServicesTreeTable", () => {
  const actual = jest.requireActual<Record<string, unknown>>(
    "../../../../src/components/services/ServicesTreeTable",
  );
  return {
    ...actual,
    useServicesTreeTable: () => ({
      tableElement: <div data-testid="mock-specs-tree-table" />,
      FilterButton: () => (
        <button type="button" data-testid="mock-columns-filter">
          Columns
        </button>
      ),
    }),
  };
});

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

function renderAt(initialPath: string, routePath: string) {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <ServiceContext.Provider value={makeSystem()}>
        <ToolbarOutletShell>
          <Routes>
            <Route path={routePath} element={<ServiceApiSpecsTab />} />
          </Routes>
        </ToolbarOutletShell>
      </ServiceContext.Provider>
    </MemoryRouter>,
  );
}

describe("ServiceApiSpecsTab toolbar labels", () => {
  it("labels the API-level actions as API and the group level as API Group", async () => {
    renderAt(
      "/services/systems/sys-1/specificationGroups",
      "/services/systems/:systemId/specificationGroups",
    );

    expect(
      await screen.findByTestId("api-specs-action-import-api"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("api-specs-action-export-selected-apis"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("api-specs-action-export-api"),
    ).toBeInTheDocument();
    expect(
      screen.getByTestId("api-specs-action-add-api-group"),
    ).toBeInTheDocument();
  });

  it('shows a "To APIs" back-button on the operations table', async () => {
    renderAt(
      "/services/systems/sys-1/specificationGroups/group-1/specifications/spec-1/operations",
      "/services/systems/:systemId/specificationGroups/:groupId/specifications/:specId/operations",
    );

    expect(await screen.findByText("To APIs")).toBeInTheDocument();
  });
});

const notifyStub: ReturnType<typeof useNotificationService> = {
  requestFailed: jest.fn(),
  errorWithDetails: jest.fn(),
  info: jest.fn(),
  warning: jest.fn(),
};

function makeGroup(): ApiGroup {
  return {
    id: "group-1",
    name: "G",
    systemId: "sys-1",
    synchronization: true,
    specifications: [],
  };
}

function makeSpec(overrides: Partial<Api> = {}): Api {
  return {
    id: "spec-1",
    name: "S",
    specificationGroupId: "group-1",
    version: "1.0",
    source: "MANUAL",
    systemId: "sys-1",
    ...overrides,
  };
}

describe("getGroupActions", () => {
  it('labels the add action "Add API"', () => {
    const actions = getGroupActions(
      [],
      jest.fn(),
      jest.fn(async () => {}),
      notifyStub,
      jest.fn(),
      "sys-1",
      false,
      jest.fn(async () => {}),
      jest.fn(async () => {}),
    )(makeGroup());

    expect(actions.find((a) => a.key === "add")?.label).toBe("Add API");
  });
});

describe("getSpecActions", () => {
  it('confirms deletion of a deprecated API with "Delete this API?"', () => {
    const actions = getSpecActions(
      [],
      jest.fn(),
      jest.fn(async () => {}),
      notifyStub,
    )(makeSpec({ deprecated: true }));

    expect(actions.find((a) => a.key === "delete")?.confirm?.title).toBe(
      "Delete this API?",
    );
  });

  it('confirms deprecation of an active API with "Deprecate this API?"', () => {
    const actions = getSpecActions(
      [],
      jest.fn(),
      jest.fn(async () => {}),
      notifyStub,
    )(makeSpec({ deprecated: false }));

    expect(actions.find((a) => a.key === "deprecate")?.confirm?.title).toBe(
      "Deprecate this API?",
    );
  });
});
