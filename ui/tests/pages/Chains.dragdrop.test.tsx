/**
 * @jest-environment jsdom
 */

import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Table } from "antd";
import { CatalogItemType } from "../../src/api/apiTypes";
import { useTableDragDrop } from "../../src/hooks/useTableDragDrop";

Object.defineProperty(globalThis, "matchMedia", {
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

type TestItem = {
  id: string;
  name: string;
  parentId?: string;
  itemType: CatalogItemType;
  children?: TestItem[];
};

const folderItem: TestItem = {
  id: "folder-1",
  name: "Test Folder",
  itemType: CatalogItemType.FOLDER,
  children: [],
};

const chainItem: TestItem = {
  id: "chain-1",
  name: "Test Chain",
  itemType: CatalogItemType.CHAIN,
};

const folder2: TestItem = {
  id: "folder-2",
  name: "Second Folder",
  itemType: CatalogItemType.FOLDER,
  children: [],
};

type TestTableProps = {
  items: TestItem[];
  onMoveChain: (chainId: string, folderId?: string) => Promise<void>;
  onMoveFolder: (folderId: string, targetFolderId?: string) => Promise<void>;
  disabled?: boolean;
};

function TestTable({ items, onMoveChain, onMoveFolder, disabled }: TestTableProps) {
  const { draggedItemId, dropTargetId, onRow } = useTableDragDrop({
    tableItems: items,
    onMoveChain,
    onMoveFolder,
    disabled,
  });

  return (
    <Table<TestItem>
      dataSource={items}
      columns={[{ title: "Name", dataIndex: "name", key: "name" }]}
      rowKey="id"
      onRow={onRow}
      rowClassName={(record) =>
        [
          draggedItemId === record.id ? "dragging" : "",
          dropTargetId === record.id ? "dropTarget" : "",
        ]
          .filter(Boolean)
          .join(" ")
      }
      pagination={false}
    />
  );
}

function createDataTransfer(data: Record<string, string> = {}) {
  const store: Record<string, string> = { ...data };
  return {
    effectAllowed: "uninitialized" as string,
    dropEffect: "none" as string,
    setData: (type: string, val: string) => {
      store[type] = val;
    },
    getData: (type: string) => store[type] ?? "",
    clearData: jest.fn(),
    setDragImage: jest.fn(),
    types: Object.keys(store),
    files: [],
    items: [],
  };
}

describe("Table Drag & Drop integration", () => {
  let onMoveChain: jest.Mock;
  let onMoveFolder: jest.Mock;

  beforeEach(() => {
    onMoveChain = jest.fn().mockResolvedValue(undefined);
    onMoveFolder = jest.fn().mockResolvedValue(undefined);
  });

  afterEach(() => {
    // Clean up drag preview elements appended to body by onDragStart
    document.querySelectorAll("body > div[style]").forEach((el) => {
      if ((el as HTMLElement).style.position === "fixed") {
        el.remove();
      }
    });
  });




  it("rows have draggable='true'", () => {
    render(
      <TestTable
        items={[folderItem, chainItem]}
        onMoveChain={onMoveChain}
        onMoveFolder={onMoveFolder}
      />,
    );

    const folderRow = screen.getByText("Test Folder").closest("tr");
    expect(folderRow).toHaveAttribute("draggable", "true");

    const chainRow = screen.getByText("Test Chain").closest("tr");
    expect(chainRow).toHaveAttribute("draggable", "true");
  });

  it("rows have draggable='false' when disabled", () => {
    render(
      <TestTable
        items={[folderItem, chainItem]}
        onMoveChain={onMoveChain}
        onMoveFolder={onMoveFolder}
        disabled
      />,
    );

    const folderRow = screen.getByText("Test Folder").closest("tr");
    expect(folderRow).toHaveAttribute("draggable", "false");
  });

  it("dragOver on folder row adds dropTarget class", () => {
    render(
      <TestTable
        items={[folderItem, chainItem]}
        onMoveChain={onMoveChain}
        onMoveFolder={onMoveFolder}
      />,
    );

    const chainRow = screen.getByText("Test Chain").closest("tr")!;
    const folderRow = screen.getByText("Test Folder").closest("tr")!;

    fireEvent.dragStart(chainRow, { dataTransfer: createDataTransfer() });
    fireEvent.dragOver(folderRow, { dataTransfer: createDataTransfer() });

    expect(folderRow.className).toContain("dropTarget");
  });

  it("dragOver on chain row does not add dropTarget class", () => {
    render(
      <TestTable
        items={[folderItem, chainItem]}
        onMoveChain={onMoveChain}
        onMoveFolder={onMoveFolder}
      />,
    );

    const folderRow = screen.getByText("Test Folder").closest("tr")!;
    const chainRow = screen.getByText("Test Chain").closest("tr")!;

    fireEvent.dragStart(folderRow, { dataTransfer: createDataTransfer() });
    fireEvent.dragOver(chainRow, { dataTransfer: createDataTransfer() });

    expect(chainRow.className).not.toContain("dropTarget");
  });

  it("drop chain on folder calls onMoveChain", () => {
    render(
      <TestTable
        items={[folderItem, chainItem]}
        onMoveChain={onMoveChain}
        onMoveFolder={onMoveFolder}
      />,
    );

    const chainRow = screen.getByText("Test Chain").closest("tr")!;
    const folderRow = screen.getByText("Test Folder").closest("tr")!;

    const dt = createDataTransfer();
    fireEvent.dragStart(chainRow, { dataTransfer: dt });
    fireEvent.dragOver(folderRow, { dataTransfer: dt });
    fireEvent.drop(folderRow, { dataTransfer: dt });

    expect(onMoveChain).toHaveBeenCalledWith("chain-1", "folder-1");
  });

  it("drop folder on another folder calls onMoveFolder", () => {
    render(
      <TestTable
        items={[folderItem, folder2]}
        onMoveChain={onMoveChain}
        onMoveFolder={onMoveFolder}
      />,
    );

    const folder2Row = screen.getByText("Second Folder").closest("tr")!;
    const folder1Row = screen.getByText("Test Folder").closest("tr")!;

    const dt = createDataTransfer();
    fireEvent.dragStart(folder2Row, { dataTransfer: dt });
    fireEvent.dragOver(folder1Row, { dataTransfer: dt });
    fireEvent.drop(folder1Row, { dataTransfer: dt });

    expect(onMoveFolder).toHaveBeenCalledWith("folder-2", "folder-1");
  });
});
