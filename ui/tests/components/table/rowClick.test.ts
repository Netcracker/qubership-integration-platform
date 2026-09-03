/**
 * @jest-environment jsdom
 */
import type React from "react";

import {
  rowClickProps,
  shouldIgnoreRowClick,
} from "../../../src/components/table/rowClick";

function render(html: string): HTMLElement {
  const row = document.createElement("tr");
  row.innerHTML = html;
  document.body.appendChild(row);
  return row;
}

function target(html: string, selector: string): Element {
  const found = render(html).querySelector(selector);
  if (!found) {
    throw new Error(`nothing matched ${selector}`);
  }
  return found;
}

describe("shouldIgnoreRowClick", () => {
  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("should ignore a click when it lands on a link", () => {
    expect(
      shouldIgnoreRowClick(target("<td><a href='/x'>name</a></td>", "a")),
    ).toBe(true);
  });

  it("should ignore a click when it lands on a button", () => {
    expect(
      shouldIgnoreRowClick(target("<td><button>run</button></td>", "button")),
    ).toBe(true);
  });

  it("should ignore a click when it lands inside a link", () => {
    expect(
      shouldIgnoreRowClick(
        target("<td><a href='/x'><span>name</span></a></td>", "span"),
      ),
    ).toBe(true);
  });

  it("should ignore a click when it lands in the selection column", () => {
    expect(
      shouldIgnoreRowClick(
        target(
          "<td class='ant-table-selection-column'><span>cell</span></td>",
          "span",
        ),
      ),
    ).toBe(true);
  });

  it("should ignore a click when it lands on the expand icon", () => {
    expect(
      shouldIgnoreRowClick(
        target(
          "<td><button class='ant-table-row-expand-icon'></button></td>",
          ".ant-table-row-expand-icon",
        ),
      ),
    ).toBe(true);
  });

  it("should not ignore a click when it lands on a plain cell", () => {
    expect(shouldIgnoreRowClick(target("<td>plain</td>", "td"))).toBe(false);
  });

  it("should not ignore a click when there is no target", () => {
    expect(shouldIgnoreRowClick(null)).toBe(false);
  });

  it("should not ignore a click when the target is not an element", () => {
    expect(shouldIgnoreRowClick(new EventTarget())).toBe(false);
  });
});

describe("rowClickProps", () => {
  afterEach(() => {
    document.body.innerHTML = "";
  });

  function clickOn(element: Element, open: (record: string) => void): void {
    const props = rowClickProps(open)("row-1");
    props.onClick?.({
      target: element,
    } as unknown as React.MouseEvent<HTMLElement>);
  }

  it("should open the record when the click lands on a plain cell", () => {
    const opened: string[] = [];

    clickOn(target("<td>plain</td>", "td"), (record) => opened.push(record));

    expect(opened).toEqual(["row-1"]);
  });

  it("should not open the record when the click lands on a link", () => {
    const opened: string[] = [];

    clickOn(target("<td><a href='/x'>name</a></td>", "a"), (record) =>
      opened.push(record),
    );

    expect(opened).toEqual([]);
  });

  it("should not open the record when the click lands in the selection column", () => {
    const opened: string[] = [];

    clickOn(
      target(
        "<td class='ant-table-selection-column'><span>cell</span></td>",
        "span",
      ),
      (record) => opened.push(record),
    );

    expect(opened).toEqual([]);
  });
});
