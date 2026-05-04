/**
 * @jest-environment jsdom
 */

import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import DomainsTable from "../../../../src/components/admin_tools/domains/DomainsTable.tsx";
import { DomainType, type EngineDomain } from "../../../../src/api/apiTypes.ts";

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
    useEngines: () => ({
      engines: [],
      isLoading: false,
      error: undefined,
      retry: jest.fn(),
    }),
  }),
);

jest.mock("../../../../src/api/api.ts", () => ({
  api: {
    deleteMicroDomain: jest.fn().mockResolvedValue(undefined),
  },
}));

jest.mock("../../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => ({ requestFailed: jest.fn() }),
}));

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => <span>{name}</span>,
}));

const nativeDomain: EngineDomain = {
  id: "d1",
  name: "alpha-domain",
  replicas: 1,
  namespace: "ns1",
  version: "1.0",
  type: DomainType.NATIVE,
};

describe("DomainsTable", () => {
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
});
