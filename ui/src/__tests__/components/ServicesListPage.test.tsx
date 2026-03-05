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

import React from "react";
import { describe, it, expect, beforeEach } from "@jest/globals";
import { render, fireEvent, waitFor, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { IntegrationSystemType } from "../../api/apiTypes";
import type { IntegrationSystem, ContextSystem } from "../../api/apiTypes";

const mockGetServices = jest.fn<() => Promise<IntegrationSystem[]>>();
const mockFilterSystems = jest.fn<() => Promise<IntegrationSystem[]>>();
const mockSearchSystems = jest.fn<() => Promise<IntegrationSystem[]>>();
const mockGetContextServices = jest.fn<() => Promise<ContextSystem[]>>();
const mockShowModal = jest.fn();
const mockNavigate = jest.fn();

jest.mock("../../api/api", () => ({
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

jest.mock("../../Modals", () => ({
  useModalsContext: () => ({
    showModal: mockShowModal,
  }),
}));

jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({}),
}));

jest.mock("../../hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: jest.fn(),
    info: jest.fn(),
  }),
}));

jest.mock("../../hooks/useServiceFilter", () => ({
  useServiceFilters: () => ({
    filters: [],
    filterButton: <button data-testid="service-filter-button">Filter</button>,
    resetFilters: jest.fn(),
  }),
}));

jest.mock("../../icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

// Mock CSS modules
jest.mock("../../components/services/Services.module.css", () => ({}), {
  virtual: true,
});

// Mock components that import CSS
jest.mock("../../components/services/ServicesTreeTable", () => ({
  useServicesTreeTable: () => ({
    Table: () => <table data-testid="services-table" />,
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

jest.mock("../../components/services/modals/CreateServiceModal", () => ({
  CreateServiceModal: () => <div data-testid="create-modal" />,
}));

jest.mock("../../components/services/modals/ImportServicesModal", () => ({
  __esModule: true,
  default: () => <div data-testid="import-modal" />,
}));

jest.mock("../../misc/download-utils", () => ({
  downloadFile: jest.fn(),
}));

jest.mock("../../components/services/utils.tsx", () => ({
  prepareFile: jest.fn(),
}));

jest.mock("../../misc/error-utils", () => ({
  getErrorMessage: (e: unknown, msg: string) => msg,
}));

import { ServicesListPage } from "../../components/services/ServicesListPage";

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
  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
    mockGetServices.mockResolvedValue([
      makeService("1", "Service A", IntegrationSystemType.IMPLEMENTED),
      makeService("2", "Service B", IntegrationSystemType.IMPLEMENTED),
    ]);
    mockGetContextServices.mockResolvedValue([]);
    mockFilterSystems.mockResolvedValue([]);
    mockSearchSystems.mockResolvedValue([]);
    window.location.hash = "#implemented";
  });

  afterEach(() => {
    jest.useRealTimers();
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

  it("renders search input", () => {
    render(<ServicesListPage />);
    expect(
      screen.getByPlaceholderText("Search services..."),
    ).toBeInTheDocument();
  });

  it("renders filter button from useServiceFilters", () => {
    render(<ServicesListPage />);
    expect(screen.getByTestId("service-filter-button")).toBeInTheDocument();
  });

  it("renders columns filter button", () => {
    render(<ServicesListPage />);
    expect(screen.getByTestId("columns-filter-button")).toBeInTheDocument();
  });

  it("updates search input value when typing", () => {
    render(<ServicesListPage />);

    const searchInput = screen.getByPlaceholderText(
      "Search services...",
    ) as HTMLInputElement;
    fireEvent.change(searchInput, { target: { value: "test query" } });

    expect(searchInput.value).toBe("test query");
  });
});
