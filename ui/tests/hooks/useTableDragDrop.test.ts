/**
 * @jest-environment jsdom
 */

import { describe, it, expect, beforeEach } from "@jest/globals";
import { renderHook, act } from "@testing-library/react";
import { CatalogItemType } from "../../src/api/apiTypes";
import {
  isDescendantOf,
  useTableDragDrop,
  UseTableDragDropProps,
} from "../../src/hooks/useTableDragDrop";

type TestItem = {
  id: string;
  name: string;
  parentId?: string;
  itemType: CatalogItemType;
  children?: TestItem[];
};

const folder1: TestItem = {
  id: "folder-1",
  name: "Folder 1",
  itemType: CatalogItemType.FOLDER,
  children: [
    {
      id: "folder-1-1",
      name: "Folder 1-1",
      parentId: "folder-1",
      itemType: CatalogItemType.FOLDER,
      children: [
        {
          id: "folder-1-1-1",
          name: "Folder 1-1-1",
          parentId: "folder-1-1",
          itemType: CatalogItemType.FOLDER,
          children: [],
        },
      ],
    },
    {
      id: "chain-1",
      name: "Chain 1",
      parentId: "folder-1",
      itemType: CatalogItemType.CHAIN,
    },
  ],
};

const folder2: TestItem = {
  id: "folder-2",
  name: "Folder 2",
  itemType: CatalogItemType.FOLDER,
  children: [],
};

const chain2: TestItem = {
  id: "chain-2",
  name: "Chain 2",
  itemType: CatalogItemType.CHAIN,
};

const tableItems: TestItem[] = [folder1, folder2, chain2];

type MockDragEvent = {
  preventDefault: jest.Mock;
  stopPropagation: jest.Mock;
  dataTransfer: {
    effectAllowed: string;
    dropEffect: string;
    setData: (type: string, data: string) => void;
    getData: (type: string) => string;
    setDragImage: jest.Mock;
  };
  relatedTarget: Node | null;
  currentTarget: {
    contains: (node: Node | null) => boolean;
    querySelector: (sel: string) => Element | null;
    closest: (sel: string) => Element | null;
  };
};

function createDragEvent(
  overrides: Partial<MockDragEvent> = {},
): MockDragEvent & React.DragEvent<HTMLTableRowElement> {
  const dataStore: Record<string, string> = {};
  return {
    preventDefault: jest.fn(),
    stopPropagation: jest.fn(),
    dataTransfer: {
      effectAllowed: "uninitialized",
      dropEffect: "none",
      setData: (type: string, data: string) => {
        dataStore[type] = data;
      },
      getData: (type: string) => dataStore[type] ?? "",
      setDragImage: jest.fn(),
    },
    relatedTarget: null,
    currentTarget: {
      contains: () => false,
      querySelector: () => null,
      closest: () => null,
    },
    ...overrides,
  } as unknown as MockDragEvent & React.DragEvent<HTMLTableRowElement>;
}

describe("isDescendantOf", () => {
  it("returns true for direct child", () => {
    expect(isDescendantOf(tableItems, "folder-1", "folder-1-1")).toBe(true);
  });

  it("returns true for deep descendant", () => {
    expect(isDescendantOf(tableItems, "folder-1", "folder-1-1-1")).toBe(true);
  });

  it("returns true for chain child", () => {
    expect(isDescendantOf(tableItems, "folder-1", "chain-1")).toBe(true);
  });

  it("returns false for parent (reverse direction)", () => {
    expect(isDescendantOf(tableItems, "folder-1-1", "folder-1")).toBe(false);
  });

  it("returns false for unrelated items", () => {
    expect(isDescendantOf(tableItems, "folder-1", "folder-2")).toBe(false);
  });

  it("returns false for self", () => {
    expect(isDescendantOf(tableItems, "folder-1", "folder-1")).toBe(false);
  });
});

describe("useTableDragDrop", () => {
  let onMoveChain: jest.Mock;
  let onMoveFolder: jest.Mock;

  beforeEach(() => {
    onMoveChain = jest.fn().mockResolvedValue(undefined);
    onMoveFolder = jest.fn().mockResolvedValue(undefined);
  });

  function renderDragDropHook(
    overrides?: Partial<UseTableDragDropProps<TestItem>>,
  ) {
    return renderHook(() =>
      useTableDragDrop({
        tableItems,
        onMoveChain,
        onMoveFolder,
        ...overrides,
      }),
    );
  }

  it("initial state has null draggedItemId and dropTargetId", () => {
    const { result } = renderDragDropHook();
    expect(result.current.draggedItemId).toBeNull();
    expect(result.current.dropTargetId).toBeNull();
  });

  it("onRow returns draggable: true when not disabled", () => {
    const { result } = renderDragDropHook();
    const props = result.current.onRow(chain2);
    expect(props.draggable).toBe(true);
  });

  it("onRow returns draggable: false when disabled", () => {
    const { result } = renderDragDropHook({ disabled: true });
    const props = result.current.onRow(chain2);
    expect(props.draggable).toBe(false);
  });

  it("drag chain → drop on folder → calls onMoveChain", () => {
    const { result } = renderDragDropHook();

    // Start drag on chain-2
    act(() => {
      const chainRow = result.current.onRow(chain2);
      chainRow.onDragStart!(createDragEvent());
    });

    // Drop on folder-2
    act(() => {
      const folderRow = result.current.onRow(folder2);
      const event = createDragEvent();
      // First dragOver to validate
      folderRow.onDragOver!(event);
    });

    act(() => {
      const folderRow = result.current.onRow(folder2);
      const dropEvent = createDragEvent();
      // Manually set getData to return the dragged ID
      (dropEvent.dataTransfer as { getData: (t: string) => string }).getData =
        () => "chain-2";
      folderRow.onDrop!(dropEvent);
    });

    expect(onMoveChain).toHaveBeenCalledWith("chain-2", "folder-2");
    expect(onMoveFolder).not.toHaveBeenCalled();
  });

  it("drag folder → drop on folder → calls onMoveFolder", () => {
    const { result } = renderDragDropHook();

    // Start drag on folder-2
    act(() => {
      const row = result.current.onRow(folder2);
      row.onDragStart!(createDragEvent());
    });

    // dragOver on folder-1
    act(() => {
      const row = result.current.onRow(folder1);
      row.onDragOver!(createDragEvent());
    });

    // Drop on folder-1
    act(() => {
      const row = result.current.onRow(folder1);
      const dropEvent = createDragEvent();
      (dropEvent.dataTransfer as { getData: (t: string) => string }).getData =
        () => "folder-2";
      row.onDrop!(dropEvent);
    });

    expect(onMoveFolder).toHaveBeenCalledWith("folder-2", "folder-1");
    expect(onMoveChain).not.toHaveBeenCalled();
  });

  it("drag folder → drop on chain → API not called", () => {
    const { result } = renderDragDropHook();

    act(() => {
      const row = result.current.onRow(folder2);
      row.onDragStart!(createDragEvent());
    });

    // Try to drop on chain-2 (should not be allowed)
    act(() => {
      const row = result.current.onRow(chain2);
      const dropEvent = createDragEvent();
      (dropEvent.dataTransfer as { getData: (t: string) => string }).getData =
        () => "folder-2";
      row.onDrop!(dropEvent);
    });

    expect(onMoveChain).not.toHaveBeenCalled();
    expect(onMoveFolder).not.toHaveBeenCalled();
  });

  it("drag folder → drop on self → API not called", () => {
    const { result } = renderDragDropHook();

    act(() => {
      const row = result.current.onRow(folder2);
      row.onDragStart!(createDragEvent());
    });

    act(() => {
      const row = result.current.onRow(folder2);
      const dropEvent = createDragEvent();
      (dropEvent.dataTransfer as { getData: (t: string) => string }).getData =
        () => "folder-2";
      row.onDrop!(dropEvent);
    });

    expect(onMoveChain).not.toHaveBeenCalled();
    expect(onMoveFolder).not.toHaveBeenCalled();
  });

  it("drag folder → drop on descendant → API not called", () => {
    const { result } = renderDragDropHook();

    act(() => {
      const row = result.current.onRow(folder1);
      row.onDragStart!(createDragEvent());
    });

    // folder-1-1 is a child of folder-1
    const child = folder1.children![0];

    act(() => {
      const row = result.current.onRow(child);
      const dropEvent = createDragEvent();
      (dropEvent.dataTransfer as { getData: (t: string) => string }).getData =
        () => "folder-1";
      row.onDrop!(dropEvent);
    });

    expect(onMoveChain).not.toHaveBeenCalled();
    expect(onMoveFolder).not.toHaveBeenCalled();
  });

  it("drag item already in target folder (parentId matches) → no-op", () => {
    const { result } = renderDragDropHook();

    // chain-1 has parentId "folder-1"
    const chain1 = folder1.children![1]; // chain-1

    act(() => {
      const row = result.current.onRow(chain1);
      row.onDragStart!(createDragEvent());
    });

    act(() => {
      const row = result.current.onRow(folder1);
      row.onDragOver!(createDragEvent());
    });

    act(() => {
      const row = result.current.onRow(folder1);
      const dropEvent = createDragEvent();
      (dropEvent.dataTransfer as { getData: (t: string) => string }).getData =
        () => "chain-1";
      row.onDrop!(dropEvent);
    });

    expect(onMoveChain).not.toHaveBeenCalled();
    expect(onMoveFolder).not.toHaveBeenCalled();
  });

  it("dragEnd resets state", () => {
    const { result } = renderDragDropHook();

    act(() => {
      const row = result.current.onRow(chain2);
      row.onDragStart!(createDragEvent());
    });

    act(() => {
      const row = result.current.onRow(chain2);
      row.onDragEnd!(createDragEvent());
    });

    expect(result.current.draggedItemId).toBeNull();
    expect(result.current.dropTargetId).toBeNull();
  });

  it("dragOver on valid target sets dropTargetId", () => {
    const { result } = renderDragDropHook();

    act(() => {
      const row = result.current.onRow(chain2);
      row.onDragStart!(createDragEvent());
    });

    act(() => {
      const row = result.current.onRow(folder2);
      const event = createDragEvent();
      row.onDragOver!(event);
      expect(event.preventDefault).toHaveBeenCalled();
    });

    expect(result.current.dropTargetId).toBe("folder-2");
  });

  it("dragOver without prior dragStart is no-op", () => {
    const { result } = renderDragDropHook();

    act(() => {
      const row = result.current.onRow(folder2);
      const event = createDragEvent();
      row.onDragOver!(event);
      expect(event.preventDefault).not.toHaveBeenCalled();
    });

    expect(result.current.dropTargetId).toBeNull();
  });

  it("onDragLeave resets dropTargetId", () => {
    const { result } = renderDragDropHook();

    act(() => {
      const row = result.current.onRow(chain2);
      row.onDragStart!(createDragEvent());
    });

    act(() => {
      const row = result.current.onRow(folder2);
      row.onDragOver!(createDragEvent());
    });

    expect(result.current.dropTargetId).toBe("folder-2");

    act(() => {
      const row = result.current.onRow(folder2);
      row.onDragLeave!(createDragEvent());
    });

    expect(result.current.dropTargetId).toBeNull();
  });

  it("onDragLeave ignores leave to child element", () => {
    const { result } = renderDragDropHook();

    act(() => {
      const row = result.current.onRow(chain2);
      row.onDragStart!(createDragEvent());
    });

    act(() => {
      const row = result.current.onRow(folder2);
      row.onDragOver!(createDragEvent());
    });

    expect(result.current.dropTargetId).toBe("folder-2");

    // Simulate leave to a child element (currentTarget.contains(relatedTarget) = true)
    act(() => {
      const row = result.current.onRow(folder2);
      const event = createDragEvent({
        relatedTarget: {} as Node,
        currentTarget: {
          contains: () => true,
          querySelector: () => null,
          closest: () => null,
        } as unknown as HTMLTableRowElement,
      });
      row.onDragLeave!(event);
    });

    // Should NOT have cleared dropTargetId
    expect(result.current.dropTargetId).toBe("folder-2");
  });

  it("drag preview is created with record name", () => {
    const { result } = renderDragDropHook();
    const appendSpy = jest.spyOn(document.body, "appendChild");

    act(() => {
      const row = result.current.onRow(chain2);
      row.onDragStart!(createDragEvent());
    });

    expect(appendSpy).toHaveBeenCalled();
    const preview = appendSpy.mock.calls[0][0] as HTMLElement;
    expect(preview.textContent).toBe("Chain 2");
    appendSpy.mockRestore();
    preview.remove();
  });

  it("drag preview uses icon when .anticon is present", () => {
    const { result } = renderDragDropHook();
    const iconEl = document.createElement("span");
    iconEl.className = "anticon";
    iconEl.textContent = "icon";

    const appendSpy = jest.spyOn(document.body, "appendChild");

    act(() => {
      const row = result.current.onRow(chain2);
      const event = createDragEvent({
        currentTarget: {
          contains: () => false,
          querySelector: (sel: string) => (sel === ".anticon" ? iconEl : null),
          closest: () => null,
        } as unknown as HTMLTableRowElement,
      });
      row.onDragStart!(event);
    });

    const preview = appendSpy.mock.calls[0][0] as HTMLElement;
    expect(preview.textContent).toContain("icon");
    expect(preview.textContent).toContain("Chain 2");
    appendSpy.mockRestore();
    preview.remove();
  });

  it("dragOver on invalid target does not set dropTargetId", () => {
    const { result } = renderDragDropHook();

    act(() => {
      const row = result.current.onRow(folder2);
      row.onDragStart!(createDragEvent());
    });

    act(() => {
      const row = result.current.onRow(chain2);
      const event = createDragEvent();
      row.onDragOver!(event);
      expect(event.preventDefault).not.toHaveBeenCalled();
    });

    expect(result.current.dropTargetId).toBeNull();
  });

  describe("getBreadcrumbDropProps", () => {
    type MockSpanDragEvent = {
      preventDefault: jest.Mock;
      stopPropagation: jest.Mock;
      dataTransfer: {
        effectAllowed: string;
        dropEffect: string;
        setData: (type: string, data: string) => void;
        getData: (type: string) => string;
      };
      relatedTarget: Node | null;
      currentTarget: {
        contains: (node: Node | null) => boolean;
      };
    };

    function createSpanDragEvent(
      overrides: Partial<MockSpanDragEvent> = {},
    ): MockSpanDragEvent & React.DragEvent<HTMLSpanElement> {
      const dataStore: Record<string, string> = {};
      return {
        preventDefault: jest.fn(),
        stopPropagation: jest.fn(),
        dataTransfer: {
          effectAllowed: "uninitialized",
          dropEffect: "none",
          setData: (type: string, data: string) => {
            dataStore[type] = data;
          },
          getData: (type: string) => dataStore[type] ?? "",
        },
        relatedTarget: null,
        currentTarget: {
          contains: () => false,
        },
        ...overrides,
      } as unknown as MockSpanDragEvent & React.DragEvent<HTMLSpanElement>;
    }

    it("drop chain on root breadcrumb calls onMoveChain with undefined", () => {
      const { result } = renderDragDropHook();

      // chain-1 has parentId "folder-1" (not root)
      const chain1 = folder1.children![1];

      act(() => {
        const row = result.current.onRow(chain1);
        row.onDragStart!(createDragEvent());
      });

      const props = result.current.getBreadcrumbDropProps(undefined);

      act(() => {
        props.onDragOver!(createSpanDragEvent());
      });

      expect(result.current.dropBreadcrumbId).toBe("root");

      act(() => {
        const dropEvent = createSpanDragEvent();
        (dropEvent.dataTransfer as { getData: (t: string) => string }).getData =
          () => "chain-1";
        props.onDrop!(dropEvent);
      });

      expect(onMoveChain).toHaveBeenCalledWith("chain-1", undefined);
    });

    it("drop chain on folder breadcrumb calls onMoveChain with folderId", () => {
      const { result } = renderDragDropHook();

      // chain-1 has parentId "folder-1"
      const chain1 = folder1.children![1];

      act(() => {
        const row = result.current.onRow(chain1);
        row.onDragStart!(createDragEvent());
      });

      // Drop on folder-2 breadcrumb
      const props = result.current.getBreadcrumbDropProps("folder-2");

      act(() => {
        props.onDragOver!(createSpanDragEvent());
      });

      expect(result.current.dropBreadcrumbId).toBe("folder-2");

      act(() => {
        const dropEvent = createSpanDragEvent();
        (dropEvent.dataTransfer as { getData: (t: string) => string }).getData =
          () => "chain-1";
        props.onDrop!(dropEvent);
      });

      expect(onMoveChain).toHaveBeenCalledWith("chain-1", "folder-2");
    });

    it("drop on same parent breadcrumb is no-op", () => {
      const { result } = renderDragDropHook();

      // chain-1 has parentId "folder-1"
      const chain1 = folder1.children![1];

      act(() => {
        const row = result.current.onRow(chain1);
        row.onDragStart!(createDragEvent());
      });

      // Try to drop on folder-1 breadcrumb (same parent)
      const props = result.current.getBreadcrumbDropProps("folder-1");

      act(() => {
        const event = createSpanDragEvent();
        props.onDragOver!(event);
        // Should not prevent default since it's the same parent
        expect(event.preventDefault).not.toHaveBeenCalled();
      });
    });

    it("drop root item on root breadcrumb is no-op", () => {
      const { result } = renderDragDropHook();

      // chain-2 has no parentId (root level)
      act(() => {
        const row = result.current.onRow(chain2);
        row.onDragStart!(createDragEvent());
      });

      const props = result.current.getBreadcrumbDropProps(undefined);

      act(() => {
        const event = createSpanDragEvent();
        props.onDragOver!(event);
        expect(event.preventDefault).not.toHaveBeenCalled();
      });

      expect(onMoveChain).not.toHaveBeenCalled();
    });

    it("returns empty props when disabled", () => {
      const { result } = renderDragDropHook({ disabled: true });
      const props = result.current.getBreadcrumbDropProps(undefined);
      expect(props).toEqual({});
    });

    it("breadcrumb dragOver without prior dragStart is no-op", () => {
      const { result } = renderDragDropHook();
      const props = result.current.getBreadcrumbDropProps(undefined);

      act(() => {
        const event = createSpanDragEvent();
        props.onDragOver!(event);
        expect(event.preventDefault).not.toHaveBeenCalled();
      });

      expect(result.current.dropBreadcrumbId).toBeNull();
    });

    it("breadcrumb dragOver on self folder is no-op", () => {
      const { result } = renderDragDropHook();

      act(() => {
        const row = result.current.onRow(folder2);
        row.onDragStart!(createDragEvent());
      });

      // folder-2 breadcrumb — dragging folder-2 onto itself
      const props = result.current.getBreadcrumbDropProps("folder-2");

      act(() => {
        const event = createSpanDragEvent();
        props.onDragOver!(event);
        expect(event.preventDefault).not.toHaveBeenCalled();
      });
    });

    it("breadcrumb onDragLeave resets dropBreadcrumbId", () => {
      const { result } = renderDragDropHook();

      const chain1 = folder1.children![1];
      act(() => {
        const row = result.current.onRow(chain1);
        row.onDragStart!(createDragEvent());
      });

      const props = result.current.getBreadcrumbDropProps(undefined);

      act(() => {
        props.onDragOver!(createSpanDragEvent());
      });
      expect(result.current.dropBreadcrumbId).toBe("root");

      act(() => {
        props.onDragLeave!(createSpanDragEvent());
      });
      expect(result.current.dropBreadcrumbId).toBeNull();
    });

    it("breadcrumb drop without dragStart is no-op", () => {
      const { result } = renderDragDropHook();
      const props = result.current.getBreadcrumbDropProps(undefined);

      act(() => {
        const dropEvent = createSpanDragEvent();
        props.onDrop!(dropEvent);
      });

      expect(onMoveChain).not.toHaveBeenCalled();
      expect(onMoveFolder).not.toHaveBeenCalled();
    });

    it("breadcrumb drop on self folder is no-op", () => {
      const { result } = renderDragDropHook();

      act(() => {
        const row = result.current.onRow(folder2);
        row.onDragStart!(createDragEvent());
      });

      const props = result.current.getBreadcrumbDropProps("folder-2");

      act(() => {
        const dropEvent = createSpanDragEvent();
        (dropEvent.dataTransfer as { getData: (t: string) => string }).getData =
          () => "folder-2";
        props.onDrop!(dropEvent);
      });

      expect(onMoveChain).not.toHaveBeenCalled();
      expect(onMoveFolder).not.toHaveBeenCalled();
    });

    it("drop folder on breadcrumb calls onMoveFolder", () => {
      const { result } = renderDragDropHook();

      // folder-1-1 has parentId "folder-1"
      const folder1_1 = folder1.children![0];

      act(() => {
        const row = result.current.onRow(folder1_1);
        row.onDragStart!(createDragEvent());
      });

      const props = result.current.getBreadcrumbDropProps(undefined);

      act(() => {
        props.onDragOver!(createSpanDragEvent());
      });

      act(() => {
        const dropEvent = createSpanDragEvent();
        (dropEvent.dataTransfer as { getData: (t: string) => string }).getData =
          () => "folder-1-1";
        props.onDrop!(dropEvent);
      });

      expect(onMoveFolder).toHaveBeenCalledWith("folder-1-1", undefined);
      expect(onMoveChain).not.toHaveBeenCalled();
    });
  });
});
