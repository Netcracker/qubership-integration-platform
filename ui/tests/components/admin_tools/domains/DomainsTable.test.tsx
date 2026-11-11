/**
 * @jest-environment jsdom
 */

import React from "react";
import {
  act,
  render,
  screen,
  fireEvent,
  waitFor,
  within,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import DomainsTable from "../../../../src/components/admin_tools/domains/DomainsTable.tsx";
import {
  DomainType,
  type Engine,
  type EngineDomain,
} from "../../../../src/api/apiTypes.ts";
import { mockDomainsTablesLayoutClasses as layoutStyles } from "../../../helpers/mockDomainsTablesLayout";

type UseEnginesResult = {
  engines: Engine[];
  isLoading: boolean;
  error: Error | undefined;
  retry: jest.Mock;
};

const mockUseEngines: jest.MockedFunction<() => UseEnginesResult> = jest.fn(
  () => ({
    engines: [],
    isLoading: false,
    error: undefined,
    retry: jest.fn(),
  }),
);

jest.mock("react-resizable/css/styles.css", () => ({}));

jest.mock("../../../../src/components/table/ResizableTitle.tsx", () => ({
  ResizableTitle: React.forwardRef<
    HTMLTableCellElement,
    React.ThHTMLAttributes<HTMLTableCellElement> & {
      onResize?: unknown;
      onResizeStop?: unknown;
      width?: unknown;
      minResizeWidth?: unknown;
      maxResizeWidth?: unknown;
      resizeHandleZIndex?: unknown;
    }
  >(function ResizableTitleMock(props, ref) {
    const {
      onResize,
      onResizeStop,
      width,
      minResizeWidth,
      maxResizeWidth,
      resizeHandleZIndex,
      ...rest
    } = props;
    void onResize;
    void onResizeStop;
    void width;
    void minResizeWidth;
    void maxResizeWidth;
    void resizeHandleZIndex;
    return React.createElement("th", { ref, ...rest });
  }),
}));

jest.mock("antd", () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-return -- jest.mock factory
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

jest.mock("antd/lib/table", () => ({}));
jest.mock("antd/lib/table/interface", () => ({}));
jest.mock("antd/es/table/interface", () => ({}));

jest.mock(
  "../../../../src/components/admin_tools/domains/EngineTable.tsx",
  () => ({
    EngineTable: () => <div data-testid="engine-table-stub" />,
  }),
);

jest.mock(
  "../../../../src/components/admin_tools/domains/hooks/useEngines.ts",
  () => ({
    useEngines: () => mockUseEngines(),
  }),
);

type ConfirmOptions = {
  title: string;
  content: string;
  onOk: () => void | Promise<void>;
};

const mockDeleteDomain = jest.fn<Promise<void>, [string]>();
const mockRequestFailed = jest.fn<void, unknown[]>();
const mockNotificationService = { requestFailed: mockRequestFailed };
const mockConfirmAndRun = jest.fn<void, [ConfirmOptions]>();

jest.mock("../../../../src/api/api.ts", () => ({
  api: {
    deleteDomain: (...args: [string]) => mockDeleteDomain(...args),
  },
}));

jest.mock("../../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => mockNotificationService,
}));

jest.mock("../../../../src/misc/confirm-utils.ts", () => ({
  confirmAndRun: (options: ConfirmOptions) => mockConfirmAndRun(options),
}));

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => <span>{name}</span>,
}));

const nativeDomain = {
  id: "d1",
  name: "alpha-domain",
  replicas: 1,
  namespace: "ns1",
  version: "1.0",
  type: DomainType.CLASSIC,
} satisfies EngineDomain;

const microDomain = {
  id: "d2",
  name: "beta-domain",
  replicas: 22,
  namespace: "ns-beta",
  version: "2.2.2",
  type: DomainType.MICRO,
} satisfies EngineDomain;

const otherDomain = {
  id: "d3",
  name: "gamma-domain",
  replicas: 55,
  namespace: "ns-gamma",
  version: "3.3.3",
  type: DomainType.CLASSIC,
} satisfies EngineDomain;

const unversionedDomain = {
  id: "d4",
  name: "delta-domain",
  replicas: 77,
  namespace: "ns-delta",
  type: DomainType.CLASSIC,
} satisfies EngineDomain;

describe("DomainsTable", () => {
  beforeEach(() => {
    mockDeleteDomain.mockResolvedValue(undefined);
    mockUseEngines.mockReturnValue({
      engines: [],
      isLoading: false,
      error: undefined,
      retry: jest.fn(),
    });
  });

  test("applies DomainsTablesLayout section and main table classes", () => {
    render(<DomainsTable domains={[nativeDomain]} />);

    expect(
      document.querySelector(`.${layoutStyles.tableSection}`),
    ).toBeInTheDocument();
    const main = document.querySelector(".flex-table");
    expect(main).toBeInTheDocument();
    expect(main).toHaveClass(layoutStyles.mainTable);
  });

  test("wraps expanded row content with nestedExpandWrap", () => {
    const { container } = render(<DomainsTable domains={[nativeDomain]} />);

    const expander = container.querySelector(
      "tbody tr td button[type='button']",
    );
    expect(expander).toBeTruthy();
    fireEvent.click(expander!);

    const wrap = document.querySelector(`.${layoutStyles.nestedExpandWrap}`);
    expect(wrap).toBeInTheDocument();
    expect(wrap).toContainElement(screen.getByTestId("engine-table-stub"));
  });

  test("expanded row shows engines load error and calls retry", async () => {
    const retry = jest.fn();
    mockUseEngines.mockReturnValue({
      engines: [],
      isLoading: false,
      error: new Error("unavailable"),
      retry,
    });

    const { container } = render(<DomainsTable domains={[nativeDomain]} />);
    fireEvent.click(
      container.querySelector("tbody tr td button[type='button']")!,
    );

    expect(
      await screen.findByText("Error while loading list of engines"),
    ).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(retry).toHaveBeenCalled();
  });

  test("table scroll includes empty y when filtered domains exist", () => {
    render(<DomainsTable domains={[nativeDomain]} />);

    const raw = document
      .querySelector(".flex-table")
      ?.getAttribute("data-scroll");
    expect(raw).toBeTruthy();
    const scroll = JSON.parse(raw!) as { x: number; y?: string };
    expect(scroll.y).toBe("");
    expect(typeof scroll.x).toBe("number");
  });

  test("table scroll omits y when search filters out all domains", async () => {
    render(<DomainsTable domains={[nativeDomain]} />);

    fireEvent.change(screen.getByPlaceholderText("Search domains..."), {
      target: { value: "no-such-domain-zzz" },
    });

    await waitFor(() => {
      const raw = document
        .querySelector(".flex-table")
        ?.getAttribute("data-scroll");
      expect(raw).toBeTruthy();
      const scroll = JSON.parse(raw!) as { x: number; y?: string };
      expect("y" in scroll).toBe(false);
    });
  });

  test("filters domains by name, version, namespace and replicas", async () => {
    render(
      <DomainsTable
        domains={[nativeDomain, microDomain, otherDomain, unversionedDomain]}
      />,
    );

    const search = screen.getByPlaceholderText("Search domains...");
    const allNames = [
      "alpha-domain",
      "beta-domain",
      "gamma-domain",
      "delta-domain",
    ];
    const cases: [string, string][] = [
      ["ALPHA", "alpha-domain"],
      ["2.2.2", "beta-domain"],
      ["ns-gamma", "gamma-domain"],
      ["55", "gamma-domain"],
      ["ns-delta", "delta-domain"],
    ];

    for (const [term, expected] of cases) {
      fireEvent.change(search, { target: { value: term } });
      await waitFor(() =>
        expect(screen.queryByText(expected)).toBeInTheDocument(),
      );
      allNames
        .filter((name) => name !== expected)
        .forEach((name) =>
          expect(screen.queryByText(name)).not.toBeInTheDocument(),
        );
    }
  });

  test("shows micro tag and delete button only for micro domains", () => {
    const { container } = render(
      <DomainsTable domains={[nativeDomain, microDomain]} />,
    );

    expect(screen.getAllByText("micro")).toHaveLength(1);

    const microRow = container.querySelector<HTMLTableRowElement>(
      `tr[data-row-key="${microDomain.id}"]`,
    );
    const classicRow = container.querySelector<HTMLTableRowElement>(
      `tr[data-row-key="${nativeDomain.id}"]`,
    );

    expect(within(microRow!).getByText("delete")).toBeInTheDocument();
    expect(within(classicRow!).queryByText("delete")).not.toBeInTheDocument();
  });

  test("deletes micro domain after confirmation and removes its row", async () => {
    const { container } = render(<DomainsTable domains={[microDomain]} />);

    const row = container.querySelector<HTMLTableRowElement>(
      `tr[data-row-key="${microDomain.id}"]`,
    );
    fireEvent.click(within(row!).getByText("delete").closest("button")!);

    expect(mockConfirmAndRun).toHaveBeenCalledWith({
      title: "Delete domain",
      content: "Are you sure you want to permanently delete this domain?",
      onOk: expect.any(Function),
    });

    act(() => void mockConfirmAndRun.mock.calls[0][0].onOk());

    await waitFor(() =>
      expect(screen.queryByText("beta-domain")).not.toBeInTheDocument(),
    );
    expect(mockDeleteDomain).toHaveBeenCalledWith("beta-domain");
  });

  test("reports a failed delete and keeps the row", async () => {
    const error = new Error("unavailable");
    mockDeleteDomain.mockRejectedValueOnce(error);
    const { container } = render(<DomainsTable domains={[microDomain]} />);

    const row = container.querySelector<HTMLTableRowElement>(
      `tr[data-row-key="${microDomain.id}"]`,
    );
    fireEvent.click(within(row!).getByText("delete").closest("button")!);

    act(() => void mockConfirmAndRun.mock.calls[0][0].onOk());

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to delete micro domain beta-domain",
        error,
      ),
    );
    expect(screen.getByText("beta-domain")).toBeInTheDocument();
  });

  test("wires the refresh button and loading state to props", async () => {
    const onRefresh = jest.fn<Promise<void>, []>().mockResolvedValue(undefined);
    const { rerender } = render(
      <DomainsTable domains={[nativeDomain]} onRefresh={onRefresh} />,
    );

    fireEvent.click(screen.getByRole("button", { name: "Refresh" }));
    await waitFor(() => expect(onRefresh).toHaveBeenCalledTimes(1));

    rerender(
      <DomainsTable domains={[nativeDomain]} onRefresh={onRefresh} isLoading />,
    );
    expect(screen.getByTestId("table-loading")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Refresh" })).toHaveClass(
      "ant-btn-loading",
    );

    rerender(<DomainsTable domains={[nativeDomain]} />);
    expect(
      screen.queryByRole("button", { name: "Refresh" }),
    ).not.toBeInTheDocument();
  });

  test("replaces rows when the domains prop changes", async () => {
    const { rerender } = render(<DomainsTable domains={[nativeDomain]} />);
    expect(screen.getByText("alpha-domain")).toBeInTheDocument();

    rerender(<DomainsTable domains={[otherDomain]} />);
    await waitFor(() =>
      expect(screen.queryByText("alpha-domain")).not.toBeInTheDocument(),
    );
    expect(screen.getByText("gamma-domain")).toBeInTheDocument();

    rerender(<DomainsTable domains={[]} />);
    await waitFor(() =>
      expect(screen.queryByText("gamma-domain")).not.toBeInTheDocument(),
    );
  });
});
