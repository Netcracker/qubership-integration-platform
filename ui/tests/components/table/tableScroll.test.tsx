/**
 * @jest-environment jsdom
 */
import { describe, it, expect } from "@jest/globals";
import { render } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Table } from "antd";
import { tableScroll } from "../../../src/components/table/tableScroll";

type Row = { key: string; name: string };

const columns = [{ key: "name", dataIndex: "name", title: "Name", width: 400 }];

function renderTable(rows: Row[]) {
  return render(
    <Table<Row>
      columns={columns}
      dataSource={rows}
      pagination={false}
      scroll={tableScroll(400, rows.length)}
    />,
  );
}

describe("tableScroll", () => {
  // antd treats `y: ""` as a fixed header; this pins that contract along with the helper.
  it("should split the header from a scrolling body when the table has rows", () => {
    const { container } = renderTable([{ key: "1", name: "first" }]);

    expect(container.querySelector(".ant-table-header")).not.toBeNull();
    expect(container.querySelector(".ant-table-body")).not.toBeNull();
  });

  it("should render no scrolling body when the table is empty", () => {
    const { container } = renderTable([]);

    expect(container.querySelector(".ant-table-body")).toBeNull();
    expect(
      container.querySelector(".ant-table-content .ant-table-placeholder"),
    ).not.toBeNull();
  });
});
