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

globalThis.ResizeObserver = class ResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
};

import React from "react";
import { describe, it, expect, beforeEach, afterEach } from "@jest/globals";
import { render, fireEvent, waitFor, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { IntegrationSystemType } from "../../src/api/apiTypes";
import type { IntegrationSystem, ContextSystem } from "../../src/api/apiTypes";
import type { EntityFilterModel } from "../../src/components/table/filter/filter";

const mockGetServices = jest.fn<() => Promise<IntegrationSystem[]>>();
const mockFilterSystems = jest.fn<() => Promise<IntegrationSystem[]>>();
const mockSearchSystems = jest.fn<() => Promise<IntegrationSystem[]>>();
const mockGetContextServices = jest.fn<() => Promise<ContextSystem[]>>();
const mockShowModal = jest.fn();
const mockNavigate = jest.fn();

let mockFilters: EntityFilterModel[] = [];

jest.mock("../../src/api/api", () => ({
  api: {
    getServices: (...args: unknown[]) => mockGetServices(...args),
    filterServices: (...args: unknown[]) => mockFilterSystems(...args),
    searchServices: (...args: unknown[]) => mockSearchSystems(...args),
    getContextServices: (...args: unknown[]) => mockGetContextServices(...args),
    getApiSpecifications: jest.fn().mockResolvedValue([]),
    exportServices: jest.fn().mockResolvedValue(new File([], "test")),
    exportContextServices: jest.fn().mockResolvedValue(new File([], "test")),
    updateService: jest.fn(),
    updateApiSpecificationGroup: jest.fn(),
    updateSpecificationModel: jest.fn(),
  },
}));

jest.mock("../../src/Modals", () => ({
  useModalsContext: () => ({
    showModal: mockShowModal,
  }),
}));

jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({}),
}));

jest.mock("../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: jest.fn(),
    info: jest.fn(),
  }),
}));

jest.mock("../../src/hooks/useServiceFilter", () => ({
  useServiceFilters: () => ({
    filters: mockFilters,
    filterButton: <button data-testid="service-filter-button">Filter</button>,
    resetFilters: jest.fn(),
  }),
}));

jest.mock("../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

// Mock CSS modules
jest.mock("../../src/components/services/Services.module.css", () => ({}), {
  virtual: true,
});

// Mock components that import CSS
jest.mock("../../src/components/services/ServicesTreeTable", () => ({
  useServicesTreeTable: () => ({
    tableElement: <table data-testid="services-table" />,
    FilterButton: () => (
      <button data-testid="columns-filter-button">Columns</button>
    ),
  }),
  allServicesTreeTableColumns: [{ key: "name" }, { key: "protocol" }],
  getActionsColumn: () => ({ key: "actions" }),
  getServiceActions: () => [],
  isSpecification: () => false,
  isSpecificationGroup: () => false,
  isIntegrationSystem: (r: unknown) =>
    !!(r as { type?: string })?.type &&
    (r as { type: string }).type !== "CONTEXT",
  isContextSystem: (r: unknown) => (r as { type?: string })?.type === "CONTEXT",
}));

jest.mock("../../src/components/services/modals/CreateServiceModal", () => ({
  CreateServiceModal: ({ open }: { open?: boolean }) =>
    open ? <div data-testid="create-modal" /> : null,
}));

jest.mock("../../src/components/services/modals/ImportServicesModal", () => ({
  __esModule: true,
  default: () => <div data-testid="import-modal" />,
}));

jest.mock("../../src/misc/download-utils", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../src/components/services/utils.tsx", () => ({
  prepareFile: jest.fn(),
}));

jest.mock("../../src/misc/error-utils", () => ({
  getErrorMessage: (e: unknown, msg: string) => msg,
}));

jest.mock("../../src/hooks/useResizeHeigth.tsx", () => ({
  useResizeHeight: () => [jest.fn(), 520],
}));

jest.mock("../../src/permissions/Require.tsx", () => ({
  Require: ({ children }: { children?: React.ReactNode }) => <>{children}</>,
}));

jest.mock("../../src/permissions/ProtectedButton.tsx", () => ({
  ProtectedButton: ({
    buttonProps,
    tooltipProps,
  }: {
    buttonProps: Record<string, unknown> & { onClick?: () => void };
    tooltipProps: { title: string };
  }) => {
    const { iconName: _i, icon: _n, ...rest } = buttonProps;
    return (
      <button
        type="button"
        data-testid={`svc-action-${String(tooltipProps.title).replace(/\s+/g, "-").toLowerCase()}`}
        {...(rest as React.ButtonHTMLAttributes<HTMLButtonElement>)}
      />
    );
  },
}));

import { message } from "antd";
import { ServicesListPage } from "../../src/components/services/ServicesListPage";

const makeService = (
  id: string,
  name: string,
  type: IntegrationSystemType,
): IntegrationSystem =>
  ({
    id,
    name,
    type,
    description: "",
    labels: [],
  }) as IntegrationSystem;

describe("ServicesListPage", () => {
  let messageInfoSpy: jest.SpyInstance;

  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
    messageInfoSpy = jest.spyOn(message, "info").mockImplementation(() => {});
    mockGetServices.mockResolvedValue([
      makeService("1", "Service A", IntegrationSystemType.IMPLEMENTED),
      makeService("2", "Service B", IntegrationSystemType.IMPLEMENTED),
    ]);
    mockGetContextServices.mockResolvedValue([]);
    mockFilterSystems.mockResolvedValue([]);
    mockSearchSystems.mockResolvedValue([]);
    window.location.hash = "";
  });

  afterEach(() => {
    jest.useRealTimers();
    mockFilters = [];
    messageInfoSpy.mockRestore();
  });

  it("calls getServices on initial load when no search/filters", async () => {
    jest.useRealTimers();
    render(<ServicesListPage />);
    await waitFor(() => {
      expect(mockGetServices).toHaveBeenCalledWith("", false);
    });
    expect(mockFilterSystems).not.toHaveBeenCalled();
    expect(mockSearchSystems).not.toHaveBeenCalled();
  });

  it("renders search input and updates value when typing", () => {
    render(<ServicesListPage />);
    const searchInput = screen.getByPlaceholderText("Search services...");
    expect(searchInput).toBeInTheDocument();
    fireEvent.change(searchInput, { target: { value: "test query" } });
    expect(searchInput.value).toBe("test query");
  });

  it("renders page without errors", () => {
    render(<ServicesListPage />);
    expect(screen.getByTestId("services-table")).toBeInTheDocument();
  });

  it("updates search input value when typing", () => {
    render(<ServicesListPage />);

    const searchInput = screen.getByPlaceholderText("Search services...");
    fireEvent.change(searchInput, { target: { value: "test query" } });

    expect(searchInput.value).toBe("test query");
  });

  it("calls searchServices after user types and debounce passes", async () => {
    render(<ServicesListPage />);
    const searchInput = screen.getByPlaceholderText("Search services...");
    fireEvent.change(searchInput, { target: { value: "my-service" } });

    jest.advanceTimersByTime(500);

    await waitFor(() => {
      expect(mockSearchSystems).toHaveBeenCalledWith("my-service");
    });
  });

  it("calls filterServices when filters are present", async () => {
    mockFilters = [{ column: "NAME", condition: "CONTAINS", value: "x" }];
    mockFilterSystems.mockResolvedValue([]);
    render(<ServicesListPage />);

    await waitFor(() => {
      expect(mockFilterSystems).toHaveBeenCalledWith(mockFilters);
    });
  });

  it("calls getContextServices when tab is context", async () => {
    window.location.hash = "#context";
    mockGetContextServices.mockResolvedValue([]);
    render(<ServicesListPage />);

    await waitFor(() => {
      expect(mockGetContextServices).toHaveBeenCalled();
    });
  });

  it("shows External Services title when hash is empty", async () => {
    jest.useRealTimers();
    window.location.hash = "";
    render(<ServicesListPage />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());
    expect(screen.getByText("External Services")).toBeInTheDocument();
  });

  it("shows Implemented Services title when hash is #implemented", async () => {
    jest.useRealTimers();
    window.location.hash = "#implemented";
    render(<ServicesListPage />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());
    expect(screen.getByText("Implemented Services")).toBeInTheDocument();
  });

  it("calls showModal when Upload services is clicked", async () => {
    jest.useRealTimers();
    render(<ServicesListPage />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());
    fireEvent.click(screen.getByTestId("svc-action-upload-services"));
    expect(mockShowModal).toHaveBeenCalledWith(
      expect.objectContaining({
        component: expect.anything(),
      }),
    );
  });

  it("opens Create service modal when Create service is clicked", async () => {
    jest.useRealTimers();
    render(<ServicesListPage />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());
    expect(screen.queryByTestId("create-modal")).not.toBeInTheDocument();
    fireEvent.click(screen.getByTestId("svc-action-create-service"));
    expect(screen.getByTestId("create-modal")).toBeInTheDocument();
  });

  it("shows message when Download selected with no selection", async () => {
    jest.useRealTimers();
    render(<ServicesListPage />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());
    fireEvent.click(
      screen.getByTestId("svc-action-download-selected-services"),
    );
    expect(messageInfoSpy).toHaveBeenCalledWith("No services selected");
  });
});
