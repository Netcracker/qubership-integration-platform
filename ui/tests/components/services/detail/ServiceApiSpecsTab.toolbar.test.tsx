/**
 * @jest-environment jsdom
 */
import React, { useCallback, useState } from "react";
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen, within, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import {
  IntegrationSystem,
  IntegrationSystemType,
} from "../../../../src/api/apiTypes";
import {
  ServiceContext,
  ServiceParametersToolbarContext,
} from "../../../../src/components/services/detail/ServiceParametersPage";
import { ServiceApiSpecsTab } from "../../../../src/components/services/detail/ServiceApiSpecsTab";

jest.mock("../../../../src/api/api", () => ({
  api: {
    getApiSpecifications: jest.fn().mockResolvedValue([] as never),
    getSpecificationModel: jest.fn().mockResolvedValue([] as never),
    exportServices: jest
      .fn()
      .mockResolvedValue(new File([], "x") as never),
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

const mockShowModal = jest.fn();

jest.mock("../../../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({ requestFailed: jest.fn(), info: jest.fn() }),
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
        {...(rest)}
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

describe("ServiceApiSpecsTab toolbar (groups)", () => {
  it("renders columns filter first, then export, then primary add group", async () => {
    render(
      <MemoryRouter
        initialEntries={["/services/systems/sys-1/specificationGroups"]}
      >
        <ServiceContext.Provider value={makeSystem()}>
          <ToolbarOutletShell>
            <Routes>
              <Route
                path="/services/systems/:systemId/specificationGroups"
                element={<ServiceApiSpecsTab />}
              />
            </Routes>
          </ToolbarOutletShell>
        </ServiceContext.Provider>
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(
        within(screen.getByTestId("toolbar-outlet")).getByTestId(
          "api-specs-groups-toolbar",
        ),
      ).toBeInTheDocument();
    });
    const toolbar = within(screen.getByTestId("toolbar-outlet")).getByTestId(
      "api-specs-groups-toolbar",
    );
    const buttons = within(toolbar).getAllByRole("button");
    expect(buttons[0]).toHaveAttribute("data-testid", "mock-columns-filter");
    expect(buttons[1]).toHaveAttribute(
      "data-testid",
      "api-specs-action-export-service",
    );
    expect(buttons[2]).toHaveAttribute(
      "data-testid",
      "api-specs-add-group",
    );
    expect(buttons[2]).toHaveAttribute("data-button-type", "primary");
    expect(buttons[2]).toHaveTextContent("Add Specification Group");
  });

  it("places the primary Add Specification last in the specifications toolbar", async () => {
    render(
      <MemoryRouter
        initialEntries={[
          "/services/systems/sys-1/specificationGroups/group-1/specifications",
        ]}
      >
        <ServiceContext.Provider value={makeSystem()}>
          <ToolbarOutletShell>
            <Routes>
              <Route
                path="/services/systems/:systemId/specificationGroups/:groupId/specifications"
                element={<ServiceApiSpecsTab />}
              />
            </Routes>
          </ToolbarOutletShell>
        </ServiceContext.Provider>
      </MemoryRouter>,
    );

    const toolbar = await waitFor(() =>
      within(screen.getByTestId("toolbar-outlet")).getByTestId(
        "api-specs-specifications-toolbar",
      ),
    );

    const buttons = within(toolbar).getAllByRole("button");
    expect(buttons.map((button) => button.getAttribute("data-testid"))).toEqual([
      "api-specs-action-export-selected-specifications",
      "mock-columns-filter",
      "api-specs-add-specification",
    ]);
    expect(buttons[2]).toHaveTextContent("Add Specification");
    expect(buttons[2]).toHaveAttribute("data-button-type", "primary");
  });

  it("passes an implemented service type to the add-group dialog after the service loads", async () => {
    const { rerender } = render(
      <MemoryRouter
        initialEntries={["/services/systems/sys-1/specificationGroups"]}
      >
        <ServiceContext.Provider value={null}>
          <ToolbarOutletShell>
            <Routes>
              <Route
                path="/services/systems/:systemId/specificationGroups"
                element={<ServiceApiSpecsTab />}
              />
            </Routes>
          </ToolbarOutletShell>
        </ServiceContext.Provider>
      </MemoryRouter>,
    );

    const addGroup = () =>
      within(screen.getByTestId("toolbar-outlet")).getByTestId(
        "api-specs-add-group",
      );

    await waitFor(() => expect(addGroup()).toBeInTheDocument());

    rerender(
      <MemoryRouter
        initialEntries={["/services/systems/sys-1/specificationGroups"]}
      >
        <ServiceContext.Provider value={makeSystem()}>
          <ToolbarOutletShell>
            <Routes>
              <Route
                path="/services/systems/:systemId/specificationGroups"
                element={<ServiceApiSpecsTab />}
              />
            </Routes>
          </ToolbarOutletShell>
        </ServiceContext.Provider>
      </MemoryRouter>,
    );

    fireEvent.click(addGroup());

    expect(mockShowModal).toHaveBeenCalledTimes(1);
    const [modal] = mockShowModal.mock.calls[0] as [
      { component: React.ReactElement<{ isImplementedService?: boolean }> },
    ];
    expect(modal.component.props.isImplementedService).toBe(true);
  });
});
