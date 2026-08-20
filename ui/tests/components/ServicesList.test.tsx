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
import {
  act,
  render,
  fireEvent,
  waitFor,
  screen,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import { IntegrationSystemType } from "../../src/api/apiTypes";
import type { Api, ApiGroup, IntegrationSystem } from "../../src/api/apiTypes";
import type { EntityFilterModel } from "../../src/components/table/filter/filterTypes";

const mockGetServices = jest.fn<Promise<IntegrationSystem[]>, unknown[]>();
const mockGetApiSpecifications = jest.fn<Promise<ApiGroup[]>, unknown[]>();
const mockFilterSystems = jest.fn<Promise<IntegrationSystem[]>, unknown[]>();
const mockSearchSystems = jest.fn<Promise<IntegrationSystem[]>, unknown[]>();
const mockShowModal = jest.fn();
const mockNavigate = jest.fn();

let mockFilters: EntityFilterModel[] = [];
const mockTableOptions: Record<string, unknown>[] = [];

jest.mock("../../src/api/api", () => ({
  api: {
    getServices: (...args: unknown[]) => mockGetServices(...args),
    filterServices: (...args: unknown[]) => mockFilterSystems(...args),
    searchServices: (...args: unknown[]) => mockSearchSystems(...args),
    getApiSpecifications: (...args: unknown[]) =>
      mockGetApiSpecifications(...args),
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
  useServicesTreeTable: (options: Record<string, unknown>) => {
    // Captured so a test can drive the callbacks the real table would invoke.
    mockTableOptions.push(options);
    return {
      tableElement: <table data-testid="services-table" />,
      FilterButton: () => (
        <button data-testid="columns-filter-button">Columns</button>
      ),
    };
  },
  allServicesTreeTableColumns: [{ key: "name" }, { key: "protocol" }],
  getActionsColumn: () => ({ key: "actions" }),
  getServiceActions: () => [],
  // Shape-based, like the real guards, so a test can drive any of the three branches.
  isApi: (r: object) =>
    "specificationGroupId" in r && "version" in r && "source" in r,
  isApiGroup: (r: object) => "systemId" in r && "synchronization" in r,
  isIntegrationSystem: (r: unknown) =>
    !!(r as { type?: string })?.type &&
    (r as { type: string }).type !== "CONTEXT",
  isContextSystem: (r: unknown) => (r as { type?: string })?.type === "CONTEXT",
}));

jest.mock("../../src/components/services/modals/CreateServiceModal", () => ({
  CreateServiceModal: () => <div data-testid="create-modal" />,
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
  getErrorMessage: (_e: unknown, msg: string) => msg,
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
        {...rest}
      />
    );
  },
}));

import { message } from "antd";
import { api } from "../../src/api/api";
import { ServicesList } from "../../src/components/services/ServicesList.tsx";

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
  }) as unknown as IntegrationSystem;

// The tree row a table callback receives carries the loaded children and the whole
// server-side entity, none of which belongs in an update payload.
const makeApi = (id = "m1"): Api =>
  ({
    id,
    name: "1.0.0",
    specificationGroupId: "g1",
    version: "1.0.0",
    source: "MANUAL",
    systemId: "1",
    labels: [],
    chains: [{ id: "c1", name: "Chain 1" }],
    operations: [{ id: "o1", name: "getThings" }],
  }) as unknown as Api;

const makeApiGroup = (id = "g1"): ApiGroup =>
  ({
    id,
    name: "Group A",
    systemId: "1",
    synchronization: true,
    labels: [],
    specifications: [makeApi()],
    chains: [{ id: "c1", name: "Chain 1" }],
    children: [makeApi()],
  }) as unknown as ApiGroup;

const makeTreeService = (): IntegrationSystem =>
  ({
    id: "1",
    name: "Service A",
    type: IntegrationSystemType.EXTERNAL,
    description: "billing gateway",
    activeEnvironmentId: "env-1",
    protocol: "http",
    internalServiceName: "svc-a",
    labels: [],
    children: [makeApiGroup()],
  }) as unknown as IntegrationSystem;

type LabelUpdater = (record: unknown, labels: string[]) => Promise<void> | void;

const captureOnUpdateLabels = (): LabelUpdater =>
  mockTableOptions.at(-1)?.onUpdateLabels as LabelUpdater;

describe("ServicesListPage", () => {
  let messageInfoSpy: jest.SpyInstance;

  beforeEach(() => {
    jest.clearAllMocks();
    jest.useFakeTimers();
    mockTableOptions.length = 0;
    messageInfoSpy = jest
      .spyOn(message, "info")
      .mockImplementation((() => {}) as never);
    mockGetServices.mockResolvedValue([
      makeService("1", "Service A", IntegrationSystemType.EXTERNAL),
      makeService("2", "Service B", IntegrationSystemType.EXTERNAL),
    ]);
    mockFilterSystems.mockResolvedValue([]);
    mockSearchSystems.mockResolvedValue([]);
    mockGetApiSpecifications.mockResolvedValue([]);
  });

  afterEach(() => {
    jest.useRealTimers();
    mockFilters = [];
    messageInfoSpy.mockRestore();
  });

  it("calls getServices on initial load when no search/filters", async () => {
    jest.useRealTimers();
    render(<ServicesList tab="external" />);
    await waitFor(() => {
      expect(mockGetServices).toHaveBeenCalledWith("", false);
    });
    expect(mockFilterSystems).not.toHaveBeenCalled();
    expect(mockSearchSystems).not.toHaveBeenCalled();
  });

  it("renders search input and updates value when typing", () => {
    render(<ServicesList tab="external" />);
    const searchInput = screen.getByPlaceholderText("Search services...");
    expect(searchInput).toBeInTheDocument();
    fireEvent.change(searchInput, { target: { value: "test query" } });
    expect((searchInput as HTMLInputElement).value).toBe("test query");
  });

  it("renders page without errors", () => {
    render(<ServicesList tab="external" />);
    expect(screen.getByTestId("services-table")).toBeInTheDocument();
  });

  it("updates search input value when typing", () => {
    render(<ServicesList tab="external" />);

    const searchInput = screen.getByPlaceholderText("Search services...");
    fireEvent.change(searchInput, { target: { value: "test query" } });

    expect((searchInput as HTMLInputElement).value).toBe("test query");
  });

  it("calls searchServices after user types and debounce passes", async () => {
    render(<ServicesList tab="external" />);
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
    render(<ServicesList tab="external" />);

    await waitFor(() => {
      expect(mockFilterSystems).toHaveBeenCalledWith(mockFilters);
    });
  });

  it("calls getServices when tab is internal", async () => {
    jest.useRealTimers();
    mockGetServices.mockResolvedValue([
      makeService("3", "Service C", IntegrationSystemType.INTERNAL),
    ]);
    render(<ServicesList tab="internal" />);

    await waitFor(() => {
      expect(mockGetServices).toHaveBeenCalledWith("", false);
    });
  });

  it("shows External Services title when tab is external", async () => {
    jest.useRealTimers();
    render(<ServicesList tab="external" />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());
    expect(screen.getByText("External Services")).toBeInTheDocument();
  });

  it("shows Implemented Services title when tab is implemented", async () => {
    jest.useRealTimers();
    render(<ServicesList tab="implemented" />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());
    expect(screen.getByText("Implemented Services")).toBeInTheDocument();
  });

  it("calls showModal when Upload services is clicked", async () => {
    jest.useRealTimers();
    render(<ServicesList tab="external" />);
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
    render(<ServicesList tab="external" />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());
    fireEvent.click(screen.getByTestId("svc-action-create-service"));
    expect(mockShowModal).toHaveBeenCalledWith(
      expect.objectContaining({
        component: expect.anything(),
      }),
    );
  });

  it("shows message when Download selected with no selection", async () => {
    jest.useRealTimers();
    render(<ServicesList tab="external" />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());
    fireEvent.click(
      screen.getByTestId("svc-action-download-selected-services"),
    );
    expect(messageInfoSpy).toHaveBeenCalledWith("No services selected");
  });

  // The list holds the whole record, so a payload built by spreading it would carry the type
  // back to a backend that refuses to change it.
  it("should send no type when service labels are edited", async () => {
    jest.useRealTimers();
    render(<ServicesList tab="external" />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());

    const onUpdateLabels = mockTableOptions.at(-1)?.onUpdateLabels as (
      record: IntegrationSystem,
      labels: string[],
    ) => Promise<void>;
    await onUpdateLabels(
      makeService("1", "Service A", IntegrationSystemType.EXTERNAL),
      ["billing"],
    );

    expect(api.updateService).toHaveBeenCalledTimes(1);
    const [id, payload] = jest.mocked(api.updateService).mock.calls[0];
    expect(id).toBe("1");
    expect(payload).not.toHaveProperty("type");
    expect(payload.labels).toEqual([{ name: "billing", technical: false }]);
  });

  it("should send only the request DTO fields when service labels are edited", async () => {
    jest.useRealTimers();
    render(<ServicesList tab="external" />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());

    await captureOnUpdateLabels()(makeTreeService(), ["billing"]);

    expect(api.updateService).toHaveBeenCalledTimes(1);
    const [id, payload] = jest.mocked(api.updateService).mock.calls[0];
    expect(id).toBe("1");
    // PUT overwrites what the body omits, so these four are exactly what may be sent.
    expect(Object.keys(payload).sort()).toEqual([
      "activeEnvironmentId",
      "description",
      "labels",
      "name",
    ]);
    expect(payload).toEqual({
      name: "Service A",
      description: "billing gateway",
      activeEnvironmentId: "env-1",
      labels: [{ name: "billing", technical: false }],
    });
    expect(payload).not.toHaveProperty("children");
    expect(payload).not.toHaveProperty("specifications");
    expect(payload).not.toHaveProperty("chains");
  });

  it("should send synchronization and labels only when API group labels are edited", async () => {
    jest.useRealTimers();
    const group = makeApiGroup();
    jest
      .mocked(api.updateApiSpecificationGroup)
      .mockResolvedValue({ ...group, labels: [] });
    render(<ServicesList tab="external" />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());

    await captureOnUpdateLabels()(group, ["billing"]);

    expect(api.updateApiSpecificationGroup).toHaveBeenCalledTimes(1);
    const [id, payload] = jest.mocked(api.updateApiSpecificationGroup).mock
      .calls[0];
    expect(id).toBe("g1");
    // An absent synchronization reads as false on the request DTO, so it has to travel.
    expect(Object.keys(payload).sort()).toEqual(["labels", "synchronization"]);
    expect(payload.synchronization).toBe(true);
    expect(payload.labels).toEqual([{ name: "billing", technical: false }]);
    expect(payload).not.toHaveProperty("children");
    expect(payload).not.toHaveProperty("specifications");
    expect(payload).not.toHaveProperty("chains");
  });

  it("should send the model id and labels only when API labels are edited", async () => {
    jest.useRealTimers();
    const model = makeApi();
    jest
      .mocked(api.updateSpecificationModel)
      .mockResolvedValue({ ...model, labels: [] });
    render(<ServicesList tab="external" />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());

    await captureOnUpdateLabels()(model, ["billing"]);

    expect(api.updateSpecificationModel).toHaveBeenCalledTimes(1);
    const [id, payload] = jest.mocked(api.updateSpecificationModel).mock
      .calls[0];
    expect(id).toBe("m1");
    // The handler looks the model up by the id in the body, not by the path variable.
    expect(Object.keys(payload).sort()).toEqual(["id", "labels"]);
    expect(payload.id).toBe("m1");
    expect(payload.labels).toEqual([{ name: "billing", technical: false }]);
    expect(payload).not.toHaveProperty("children");
    expect(payload).not.toHaveProperty("chains");
    expect(payload).not.toHaveProperty("operations");
  });

  it("should reload the services when service labels are edited", async () => {
    jest.useRealTimers();
    render(<ServicesList tab="external" />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalledTimes(1));

    await captureOnUpdateLabels()(makeTreeService(), ["billing"]);

    await waitFor(() => expect(mockGetServices).toHaveBeenCalledTimes(2));
  });

  it("should show the returned labels in the table when API group labels are edited", async () => {
    jest.useRealTimers();
    const group = makeApiGroup();
    mockGetApiSpecifications.mockResolvedValue([group]);
    jest.mocked(api.updateApiSpecificationGroup).mockResolvedValue({
      ...group,
      labels: [{ name: "billing", technical: false }],
    });
    render(<ServicesList tab="external" />);
    await waitFor(() => expect(mockGetServices).toHaveBeenCalled());

    const expandable = mockTableOptions.at(-1)?.expandable as {
      onExpand: (expanded: boolean, record: unknown) => void;
    };
    await act(async () => {
      expandable.onExpand(
        true,
        makeService("1", "Service A", IntegrationSystemType.EXTERNAL),
      );
      await Promise.resolve();
    });
    await waitFor(() =>
      expect(mockGetApiSpecifications).toHaveBeenCalledWith("1"),
    );

    await act(async () => {
      await captureOnUpdateLabels()(group, ["billing"]);
    });

    await waitFor(() => {
      const dataSource = mockTableOptions.at(-1)?.dataSource as {
        children: { labels: unknown }[];
      }[];
      expect(dataSource[0].children[0].labels).toEqual([
        { name: "billing", technical: false },
      ]);
    });
  });
});
