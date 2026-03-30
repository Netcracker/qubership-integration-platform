import type { ColumnsType } from "antd/lib/table";
import {
  ACTIONS_COLUMN_CLASS,
  ACTIONS_COLUMN_KEY,
  createActionsColumnBase,
  createActionsSizing,
  DEFAULT_ACTIONS_COLUMN_WIDTH,
  disableResizeBeforeActions,
} from "../../../src/components/table/actionsColumn";

describe("actionsColumn", () => {
  it("exports constants", () => {
    expect(ACTIONS_COLUMN_KEY).toBe("actions");
    expect(ACTIONS_COLUMN_CLASS).toBe("actions-column");
    expect(DEFAULT_ACTIONS_COLUMN_WIDTH).toBe(40);
  });

  it("createActionsSizing and createActionsColumnBase use default width when omitted", () => {
    expect(createActionsSizing().width).toBe(DEFAULT_ACTIONS_COLUMN_WIDTH);
    expect(createActionsColumnBase().width).toBe(DEFAULT_ACTIONS_COLUMN_WIDTH);
  });

  it("createActionsSizing applies fixed width styles on header and cell", () => {
    const sizing = createActionsSizing(48);
    expect(sizing.width).toBe(48);
    const headerProps = sizing.onHeaderCell?.();
    const cellProps = sizing.onCell?.();
    expect(headerProps?.style).toEqual({
      width: 48,
      minWidth: 48,
      maxWidth: 48,
    });
    expect(cellProps?.style).toEqual(headerProps?.style);
  });

  it("createActionsColumnBase merges key, className, align and sizing", () => {
    const col = createActionsColumnBase(40);
    expect(col.key).toBe(ACTIONS_COLUMN_KEY);
    expect(col.className).toBe(ACTIONS_COLUMN_CLASS);
    expect(col.align).toBe("center");
    expect(col.width).toBe(40);
  });

  it("disableResizeBeforeActions returns same columns when actions missing or first", () => {
    const cols: ColumnsType<{ x: string }> = [
      { key: "a", title: "A" },
      { key: "b", title: "B" },
    ];
    expect(disableResizeBeforeActions(cols)).toBe(cols);

    const withActionsFirst: ColumnsType<{ x: string }> = [
      { key: ACTIONS_COLUMN_KEY, title: "" },
      { key: "a", title: "A" },
    ];
    expect(disableResizeBeforeActions(withActionsFirst)).toBe(withActionsFirst);
  });

  it("disableResizeBeforeActions strips resize props from column left of actions", () => {
    const prevOnHeaderCell = jest.fn(() => ({
      className: "hdr",
      onResize: jest.fn(),
      onResizeStop: jest.fn(),
      minResizeWidth: 10,
      resizeHandleZIndex: 2,
    }));
    const cols: ColumnsType<{ x: string }> = [
      {
        key: "name",
        title: "Name",
        onHeaderCell: prevOnHeaderCell,
      },
      { key: ACTIONS_COLUMN_KEY, title: "" },
    ];

    const next = disableResizeBeforeActions(cols);
    expect(next[0].onHeaderCell).not.toBe(prevOnHeaderCell);

    const mockCol = { key: "name" };
    const header = next[0].onHeaderCell?.(mockCol as never);
    expect(prevOnHeaderCell).toHaveBeenCalledWith(mockCol);
    expect(header).toEqual({ className: "hdr" });
    expect(header).not.toHaveProperty("onResize");
  });

  it("disableResizeBeforeActions uses empty object when previous onHeaderCell is absent", () => {
    const cols: ColumnsType<{ x: string }> = [
      { key: "name", title: "Name" },
      { key: ACTIONS_COLUMN_KEY, title: "" },
    ];
    const next = disableResizeBeforeActions(cols);
    const header = next[0].onHeaderCell?.({} as never);
    expect(header).toEqual({});
  });
});
