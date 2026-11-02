/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import { type ReactNode } from "react";
import { render, screen, within } from "@testing-library/react";
import { AdminToolsHeader } from "../../../src/components/admin_tools/AdminToolsHeader.tsx";
import { TableToolbar } from "../../../src/components/table/TableToolbar.tsx";

jest.mock("../../../src/components/admin_tools/CommonStyle.module.css", () => ({
  header: "common-header",
  title: "common-title",
}));

jest.mock("../../../src/components/table/CompactSearch.tsx", () => ({
  CompactSearch: (props: {
    value: string;
    onChange: (v: string) => void;
    placeholder?: string;
  }) => (
    <input
      data-testid="search-mock"
      value={props.value}
      onChange={(e) => props.onChange(e.target.value)}
      placeholder={props.placeholder}
    />
  ),
}));

jest.mock("antd", () => ({
  Flex: ({
    children,
    className,
  }: {
    children?: ReactNode;
    className?: string;
  }) => <div className={className}>{children}</div>,
  Typography: {
    Title: ({
      children,
      className,
      level: _level,
    }: {
      children?: ReactNode;
      className?: string;
      level?: number;
    }) => <h4 className={className}>{children}</h4>,
  },
}));

describe("AdminToolsHeader", () => {
  it("renders title", () => {
    render(<AdminToolsHeader title="Audit" />);

    expect(screen.getByRole("heading", { name: /audit/i })).toBeInTheDocument();
  });

  it("renders toolbar to the right of the title in the same header row", () => {
    const { container } = render(
      <AdminToolsHeader
        title="Sessions"
        toolbar={
          <TableToolbar
            variant="admin"
            search={{
              value: "",
              onChange: jest.fn(),
              placeholder: "Search sessions...",
            }}
            actions={<button type="button">Refresh</button>}
          />
        }
      />,
    );

    const header = container.querySelector(".common-header");
    expect(header).toBeInTheDocument();

    expect(
      within(header as HTMLElement).getByPlaceholderText("Search sessions..."),
    ).toBeInTheDocument();
    expect(
      within(header as HTMLElement).getByRole("button", { name: /refresh/i }),
    ).toBeInTheDocument();
  });
});
