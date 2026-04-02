/**
 * @jest-environment jsdom
 */
import React, { useState } from "react";
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen, within, waitFor } from "@testing-library/react";
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
    getApiSpecifications: jest.fn().mockResolvedValue([]),
    getSpecificationModel: jest.fn().mockResolvedValue([]),
    exportServices: jest.fn().mockResolvedValue(new File([], "x")),
    exportSpecifications: jest.fn().mockResolvedValue(new File([], "x")),
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
  useNotificationService: () => ({ requestFailed: jest.fn(), info: jest.fn() }),
}));

jest.mock("../../../../src/misc/download-utils", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../../../src/components/services/utils.tsx", () => {
  const actual = jest.requireActual(
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
        {...(rest as React.ButtonHTMLAttributes<HTMLButtonElement>)}
      />
    );
  },
}));

jest.mock("../../../../src/components/services/ServicesTreeTable", () => {
  const actual = jest.requireActual(
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
  const [toolbar, setToolbar] = useState<React.ReactNode>(null);
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
      "api-specs-action-add-specification-group",
    );
    expect(buttons[2]).toHaveAttribute("data-button-type", "primary");
  });
});
