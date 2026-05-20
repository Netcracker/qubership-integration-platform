/**
 * @jest-environment jsdom
 */

import React from "react";
import { render, screen, fireEvent, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import { PLACEHOLDER } from "../../../src/misc/format-utils.ts";
import { SessionElementKVChanges } from "../../../src/components/sessions/SessionElementKVChanges.tsx";

jest.mock(
  "../../../src/components/sessions/SessionElementKVChanges.module.css",
  () => ({
    valueChanged: "valueChanged",
  }),
);

jest.mock("antd", () =>
  // eslint-disable-next-line @typescript-eslint/no-require-imports, @typescript-eslint/no-unsafe-call, @typescript-eslint/no-unsafe-member-access, @typescript-eslint/no-unsafe-return -- jest.mock hoisting
  require("tests/helpers/chainPageAntdJestMock").createChainPageAntdMock(),
);

jest.mock("antd/lib/table", () => ({}));
jest.mock("antd/lib/table/interface", () => ({}));
jest.mock("antd/es/table/interface", () => ({}));

describe("SessionElementKVChanges", () => {
  test("wraps differing string values with highlight class", () => {
    render(<SessionElementKVChanges before={{ k: "a" }} after={{ k: "b" }} />);
    expect(screen.getByText("a")).toHaveClass("valueChanged");
    expect(screen.getByText("b")).toHaveClass("valueChanged");
  });

  test("Only modified switch hides unchanged keys", () => {
    render(
      <SessionElementKVChanges
        before={{ same: "x", diff: "1" }}
        after={{ same: "x", diff: "2" }}
      />,
    );
    expect(screen.getByText("same")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("switch"));
    expect(screen.queryByText("same")).not.toBeInTheDocument();
    expect(screen.getByText("diff")).toBeInTheDocument();
  });

  test("properties comparator treats undefined before as modified (value highlight)", () => {
    type Prop = { type: string; value: string };
    const comparator = (p1: Prop | undefined, p2: Prop | undefined) => {
      if (p1 === p2) return 0;
      if (p1 === undefined || p2 === undefined) return 1;
      const byType = (p1.type ?? "").localeCompare(p2.type ?? "");
      if (byType !== 0) return byType;
      return (p1.value ?? "").localeCompare(p2.value ?? "");
    };

    render(
      <SessionElementKVChanges<Prop>
        addTypeColumns
        before={{}}
        after={{ p: { type: "t", value: "v" } }}
        comparator={comparator}
        typeRenderer={(p) => p.type}
        valueRenderer={(p) => p.value}
      />,
    );

    const row = document.querySelector('tr[data-row-key="p"]') as HTMLElement;
    expect(row).toBeTruthy();
    expect(within(row).getByText("t")).toHaveClass("valueChanged");
    expect(within(row).getByText("v")).toHaveClass("valueChanged");
  });

  test("onColumnClick receives column name for name cell", () => {
    const onColumnClick = jest.fn();
    render(
      <SessionElementKVChanges
        before={{ key1: "a" }}
        after={{ key1: "b" }}
        onColumnClick={onColumnClick}
      />,
    );

    const row = screen.getByRole("row", { name: /key1/i });
    fireEvent.click(within(row).getByText("key1"));
    expect(onColumnClick).toHaveBeenCalledWith(
      expect.objectContaining({ name: "key1" }),
      "name",
    );
  });

  test("onColumnClick for value before column", () => {
    const onColumnClick = jest.fn();
    render(
      <SessionElementKVChanges
        before={{ k: "x" }}
        after={{ k: "y" }}
        onColumnClick={onColumnClick}
      />,
    );

    const row = screen.getByRole("row", { name: /k/i });
    fireEvent.click(within(row).getByText("x"));
    expect(onColumnClick).toHaveBeenCalledWith(
      expect.objectContaining({ name: "k" }),
      "valueBefore",
    );
  });

  test("shows PLACEHOLDER for missing before or after side", () => {
    render(
      <SessionElementKVChanges
        before={{ onlyBefore: "a" }}
        after={{ onlyAfter: "b" }}
      />,
    );
    const placeholders = screen.getAllByText(PLACEHOLDER);
    expect(placeholders.length).toBeGreaterThanOrEqual(2);
    expect(screen.getByText("a")).toBeInTheDocument();
    expect(screen.getByText("b")).toBeInTheDocument();
  });
});
