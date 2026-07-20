import type { ColumnType } from "antd/lib/table";
import { createAuditColumns } from "../../../src/components/table/auditColumns";
import { PLACEHOLDER, formatTimestamp } from "../../../src/misc/format-utils";

type AuditRecord = {
  createdWhen?: number;
  createdBy?: { id: string; username: string };
  modifiedWhen?: number;
  modifiedBy?: { id: string; username: string };
};

const callRender = (
  column: ColumnType<AuditRecord>,
  record: AuditRecord,
): unknown =>
  (
    column.render as (
      value: unknown,
      record: AuditRecord,
      index: number,
    ) => unknown
  )(undefined, record, 0);

const columnByKey = (key: string): ColumnType<AuditRecord> => {
  const column = createAuditColumns<AuditRecord>().find((c) => c.key === key);
  if (!column) {
    throw new Error(`audit column "${key}" not found`);
  }
  return column;
};

describe("createAuditColumns", () => {
  it("should return the four audit columns in order", () => {
    const columns = createAuditColumns<AuditRecord>();
    expect(columns.map((c) => c.key)).toEqual([
      "createdWhen",
      "createdBy",
      "modifiedWhen",
      "modifiedBy",
    ]);
    expect(columns.map((c) => c.title)).toEqual([
      "Created At",
      "Created By",
      "Modified At",
      "Modified By",
    ]);
    expect(columns.map((c) => c.dataIndex)).toEqual([
      "createdWhen",
      "createdBy",
      "modifiedWhen",
      "modifiedBy",
    ]);
  });

  it("should hide every audit column by default", () => {
    for (const column of createAuditColumns<AuditRecord>()) {
      expect((column as { hidden?: boolean }).hidden).toBe(true);
    }
  });

  it("should size the timestamp columns wider than the user columns", () => {
    expect(columnByKey("createdWhen").width).toBe(160);
    expect(columnByKey("modifiedWhen").width).toBe(160);
    expect(columnByKey("createdBy").width).toBe(130);
    expect(columnByKey("modifiedBy").width).toBe(130);
  });

  it("should render createdWhen and modifiedWhen as formatted timestamps", () => {
    const record: AuditRecord = {
      createdWhen: 1700000000000,
      modifiedWhen: 1700000001000,
    };
    expect(callRender(columnByKey("createdWhen"), record)).toBe(
      formatTimestamp(record.createdWhen),
    );
    expect(callRender(columnByKey("modifiedWhen"), record)).toBe(
      formatTimestamp(record.modifiedWhen),
    );
    // Not the raw epoch value.
    expect(callRender(columnByKey("createdWhen"), record)).not.toBe(
      String(record.createdWhen),
    );
  });

  it("should render createdBy and modifiedBy as the username", () => {
    const record: AuditRecord = {
      createdBy: { id: "u1", username: "alice" },
      modifiedBy: { id: "u2", username: "bob" },
    };
    expect(callRender(columnByKey("createdBy"), record)).toBe("alice");
    expect(callRender(columnByKey("modifiedBy"), record)).toBe("bob");
  });

  it("should render the placeholder when audit fields are absent", () => {
    const record: AuditRecord = {};
    expect(callRender(columnByKey("createdWhen"), record)).toBe(PLACEHOLDER);
    expect(callRender(columnByKey("createdBy"), record)).toBe(PLACEHOLDER);
    expect(callRender(columnByKey("modifiedWhen"), record)).toBe(PLACEHOLDER);
    expect(callRender(columnByKey("modifiedBy"), record)).toBe(PLACEHOLDER);
  });
});
