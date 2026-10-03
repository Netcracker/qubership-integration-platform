/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/react";
import {
  ActionLog,
  EntityType,
  LogOperation,
} from "../../../src/api/apiTypes.ts";
import { useActionLog } from "../../../src/hooks/useActionLog.tsx";
import { exportActionsLogAsExcel } from "../../../src/misc/log-export-utils.ts";
import { ActionsLog } from "../../../src/components/admin_tools/ActionsLog.tsx";
import { triggerIntersection } from "../../setup/intersection-observer.ts";

// src/components/admin_tools/ActionsLog.integration.test.tsx
// ====== MOCKS ======

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

// Mock useActionLog
jest.mock("../../../src/hooks/useActionLog.tsx", () => ({
  useActionLog: jest.fn(),
}));

// Mock Modals context (used by useFilter via useActionLogFilter)
jest.mock("../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({
    showModal: jest.fn(),
    closeModal: jest.fn(),
  }),
}));

jest.mock("../../../src/components/table/useColumnSettingsButton.tsx", () => {
  const actual: Record<string, unknown> = jest.requireActual(
    "../../../src/components/table/useColumnSettingsButton.tsx",
  );
  return actual;
});

// Mock exportActionsLogAsExcel
jest.mock("../../../src/misc/log-export-utils.ts", () => ({
  exportActionsLogAsExcel: jest.fn(),
}));

// Mock DateRangePicker
jest.mock("../../../src/components/modal/DateRangePicker.tsx", () => {
  return jest.fn(
    (props: {
      trigger: React.ReactNode;
      onRangeApply: (from: Date, to: Date) => void;
    }) => (
      <div>
        <span
          data-testid="date-range-picker-trigger"
          onClick={() =>
            props.onRangeApply(new Date("2023-01-01"), new Date("2023-01-02"))
          }
        >
          {props.trigger}
        </span>
      </div>
    ),
  );
});

// Mock OverridableIcon
jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: (props: {
    name: string;
    className?: string;
    style?: React.CSSProperties;
  }) => (
    <span
      data-testid={`icon-${props.name}`}
      className={props.className}
      style={props.style}
    >
      {props.name}
    </span>
  ),
}));

// Mock Require (renders children always)
jest.mock("../../../src/permissions/Require.tsx", () => ({
  Require: (props: { children?: React.ReactNode }) => <>{props.children}</>,
}));

// Mock TextColumnFilterDropdown and TimestampColumnFilterDropdown
jest.mock("../../../src/components/table/TextColumnFilterDropdown.tsx", () => ({
  TextColumnFilterDropdown: () => <div>TextColumnFilterDropdown</div>,
  getTextColumnFilterFn: () => jest.fn(),
}));
jest.mock(
  "../../../src/components/table/TimestampColumnFilterDropdown.tsx",
  () => ({
    TimestampColumnFilterDropdown: () => (
      <div>TimestampColumnFilterDropdown</div>
    ),
    getTimestampColumnFilterFn: () => jest.fn(),
  }),
);

// Mock makeEnumColumnFilterDropdown
jest.mock("../../../src/components/EnumColumnFilterDropdown.tsx", () => ({
  makeEnumColumnFilterDropdown: () => ({
    filterDropdown: () => <div>EnumColumnFilterDropdown</div>,
    onFilter: jest.fn(),
  }),
}));

jest.mock("../../../src/components/table/ResizableTitle.tsx", () => ({
  ResizableTitle: ({
    children,
    onResize: _onResize,
    onResizeStop: _onResizeStop,
    width: _width,
    minResizeWidth: _minResizeWidth,
    maxResizeWidth: _maxResizeWidth,
    resizeHandleZIndex: _resizeHandleZIndex,
    ...rest
  }: {
    children?: React.ReactNode;
    onResize?: unknown;
    onResizeStop?: unknown;
    width?: number;
    minResizeWidth?: number;
    maxResizeWidth?: number;
    resizeHandleZIndex?: number;
  }) => <th {...rest}>{children}</th>,
}));

// Mock CSS module
jest.mock("../../../src/components/admin_tools/CommonStyle.module.css", () => ({
  container: "container",
  header: "header",
  title: "title",
  actions: "actions",
}));

jest.mock("../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({ requestFailed: jest.fn() }),
}));

// ====== TEST DATA ======
const logsData = [
  {
    id: "1",
    actionTime: 1680000000000,
    username: "alice",
    operation: LogOperation.CREATE,
    entityType: EntityType.FOLDER,
    entityName: "Folder1",
    parentType: EntityType.CHAINS,
    parentName: "ChainsParent",
    entityId: "e1",
    parentId: "p1",
    requestId: "r1",
  },
  {
    id: "2",
    actionTime: 1680000001000,
    username: "bob",
    operation: LogOperation.DELETE,
    entityType: EntityType.CHAIN,
    entityName: "Chain2",
    parentType: EntityType.FOLDER,
    parentName: "FolderParent",
    entityId: "e2",
    parentId: "p2",
    requestId: "r2",
  },
  {
    id: "3",
    actionTime: 1680000002000,
    username: "carol",
    operation: LogOperation.UPDATE,
    entityType: EntityType.SPECIFICATION,
    entityName: "spec.yaml",
    parentType: EntityType.FOLDER,
    parentName: "FolderParent",
    entityId: "e3",
    parentId: "p3",
    requestId: "r3",
  },
];

// ====== useActionLog MOCK SETUP ======
const mockUseActionLog = useActionLog as jest.Mock;

const mockExportActionsLogAsExcel = exportActionsLogAsExcel as jest.Mock;

describe("ActionsLog() ActionsLog method", () => {
  // Happy Paths
  describe("Happy paths", () => {
    beforeEach(() => {
      // Setup: useActionLog returns logsData, not loading, hasNextPage
      mockUseActionLog.mockReturnValue({
        logsData,
        fetchNextPage: jest.fn(),
        hasNextPage: true,
        isFetching: false,
        isLoading: false,
        refresh: jest.fn(),
        confirmSearch: jest.fn(),
      });
      mockExportActionsLogAsExcel.mockClear();
    });

    it("renders the audit title and icon", () => {
      // Test: The audit title and icon are rendered
      render(<ActionsLog />);
      expect(screen.getByText("Audit")).toBeInTheDocument();
      expect(screen.getByTestId("icon-audit")).toBeInTheDocument();
    });

    it("renders compact search with audit log placeholder", () => {
      render(<ActionsLog />);
      expect(
        screen.getByPlaceholderText("Search audit log..."),
      ).toBeInTheDocument();
    });

    it("passes the search term to the audit log query", () => {
      render(<ActionsLog />);
      const search = screen.getByPlaceholderText("Search audit log...");
      fireEvent.change(search, { target: { value: "alice" } });

      expect(mockUseActionLog).toHaveBeenLastCalledWith(
        expect.anything(),
        "alice",
      );
      // The server filters the rows, so the table shows every row it returns.
      expect(screen.getByText("bob")).toBeInTheDocument();
      expect(screen.getByText("carol")).toBeInTheDocument();
    });

    it("confirms the search on Enter", () => {
      render(<ActionsLog />);
      const search = screen.getByPlaceholderText("Search audit log...");
      fireEvent.change(search, { target: { value: "alice" } });
      fireEvent.keyDown(search, { key: "Enter", code: "Enter", keyCode: 13 });

      expect(
        mockUseActionLog.mock.results.at(-1)?.value.confirmSearch,
      ).toHaveBeenCalled();
    });

    it("renders the refresh and export buttons", () => {
      // Test: The refresh and export buttons are rendered
      render(<ActionsLog />);
      expect(
        screen.getByRole("button", { name: "Refresh" }),
      ).toBeInTheDocument();
      expect(screen.getByTestId("icon-cloudDownload")).toBeInTheDocument();
    });

    it("renders the column settings button", () => {
      // Test: The column settings button is rendered
      render(<ActionsLog />);
      expect(screen.getByTestId("icon-settings")).toBeInTheDocument();
    });

    it("renders all log rows with correct data", async () => {
      // Test: All log rows are rendered with correct data
      render(<ActionsLog />);
      // Check for usernames
      expect(await screen.findByText("alice")).toBeInTheDocument();
      expect(screen.getByText("bob")).toBeInTheDocument();
      expect(screen.getByText("carol")).toBeInTheDocument();
      // Check for entity names
      expect(screen.getByText("Folder1")).toBeInTheDocument();
      expect(screen.getByText("Chain2")).toBeInTheDocument();
      expect(screen.getByText("spec.yaml")).toBeInTheDocument();
      // Check for operation badges
      expect(screen.getAllByText("Create").length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText("Delete").length).toBeGreaterThanOrEqual(1);
      expect(screen.getAllByText("Update").length).toBeGreaterThanOrEqual(1);
    });

    it("calls refresh when refresh button is clicked", () => {
      // Test: Clicking refresh calls refresh
      const refreshMock = jest.fn();
      mockUseActionLog.mockReturnValueOnce({
        ...mockUseActionLog(),
        refresh: refreshMock,
      });
      render(<ActionsLog />);
      fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
      expect(refreshMock).toHaveBeenCalled();
    });

    it("calls exportActionsLogAsExcel when export is triggered", async () => {
      // Test: Export button triggers exportActionsLogAsExcel
      render(<ActionsLog />);
      // Click the export trigger (DateRangePicker)
      fireEvent.click(screen.getByTestId("date-range-picker-trigger"));
      await waitFor(() => {
        expect(mockExportActionsLogAsExcel).toHaveBeenCalledWith(
          new Date("2023-01-01"),
          new Date("2023-01-02"),
        );
      });
    });

    it("renders entity name as clickable link when entity is not deleted and not external", () => {
      render(<ActionsLog />);

      // Folder1 is not deleted and not external, should be a link
      const entityLink = screen.getByText("Folder1");
      expect(entityLink).toBeInTheDocument();
      expect(entityLink.tagName).toBe("A");
    });

    it("renders entity name as plain text when entity was already deleted", async () => {
      // Create logs where Chain2 is deleted, then test rendering at same or later time
      const logsWithDeletion: ActionLog[] = [
        {
          id: "del-1",
          actionTime: 1680000000500, // deletion happens AFTER the original action
          operation: LogOperation.DELETE,
          entityType: EntityType.FOLDER,
          entityName: "Folder1",
          entityId: "e1",
          parentId: "p1",
        },
        ...logsData.filter((data) => data.entityType !== EntityType.FOLDER),
      ];

      mockUseActionLog.mockReturnValue({
        logsData: logsWithDeletion,
        fetchNextPage: jest.fn(),
        hasNextPage: true,
        isFetching: false,
        isLoading: false,
        refresh: jest.fn(),
      });

      render(<ActionsLog />);

      // Folder1 should be plain text since it was deleted
      const entityText = await screen.findByText("Folder1");
      expect(entityText).toBeInTheDocument();
      expect(entityText.tagName).not.toBe("A");
    });

    it("renders dash when entity name is empty or undefined", () => {
      const logsWithEmptyName: ActionLog[] = [
        {
          ...logsData[0],
          entityName: undefined,
        },
        ...logsData.slice(1),
      ];

      mockUseActionLog.mockReturnValue({
        logsData: logsWithEmptyName,
        fetchNextPage: jest.fn(),
        hasNextPage: true,
        isFetching: false,
        isLoading: false,
        refresh: jest.fn(),
      });

      render(<ActionsLog />);

      // Should render "—" (em dash) when name is empty
      expect(screen.getByText("—")).toBeInTheDocument();
    });
  });

  describe("Loading further pages", () => {
    const mockLogPage = (hasNextPage: boolean) => {
      const fetchNextPage = jest.fn();
      mockUseActionLog.mockReturnValue({
        logsData,
        fetchNextPage,
        hasNextPage,
        isFetching: false,
        isLoading: false,
        refresh: jest.fn(),
      });
      return fetchNextPage;
    };

    it("fetches the next page when the end of the table is visible without scrolling", () => {
      const fetchNextPage = mockLogPage(true);
      render(<ActionsLog />);

      act(() => triggerIntersection());

      expect(fetchNextPage).toHaveBeenCalledTimes(1);
    });

    it("fetches nothing once the last page is loaded", () => {
      const fetchNextPage = mockLogPage(false);
      render(<ActionsLog />);

      act(() => triggerIntersection());

      expect(fetchNextPage).not.toHaveBeenCalled();
    });
  });

  // Edge Cases
  describe("Edge cases", () => {
    it("does not render export button when isLoading or isFetching", () => {
      // Test: Export button is hidden when loading
      mockUseActionLog.mockReturnValueOnce({
        logsData: [],
        fetchNextPage: jest.fn(),
        hasNextPage: false,
        isFetching: false,
        isLoading: true,
        refresh: jest.fn(),
      });
      render(<ActionsLog />);
      expect(
        screen.queryByTestId("icon-cloudDownload"),
      ).not.toBeInTheDocument();
    });
  });
});
