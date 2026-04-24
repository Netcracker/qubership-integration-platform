/**
 * @jest-environment jsdom
 */
import { renderHook } from "@testing-library/react";
import { useRef } from "react";
import type { editor } from "monaco-editor";

jest.mock("monaco-editor", () => ({
  editor: {},
  Range: class {
    startLineNumber: number;
    startColumn: number;
    endLineNumber: number;
    endColumn: number;
    constructor(
      startLineNumber: number,
      startColumn: number,
      endLineNumber: number,
      endColumn: number,
    ) {
      this.startLineNumber = startLineNumber;
      this.startColumn = startColumn;
      this.endLineNumber = endLineNumber;
      this.endColumn = endColumn;
    }
  },
}));

type BlockRanges = Map<string, { startLine: number; endLine: number }>;
const mockFindElementBlockRanges =
  jest.fn<BlockRanges, [editor.ITextModel, readonly string[]]>();
jest.mock("../../src/misc/element-code-utils", () => ({
  findElementBlockRanges: (
    model: editor.ITextModel,
    ids: readonly string[],
  ): BlockRanges => mockFindElementBlockRanges(model, ids),
}));

import {
  HIGHLIGHT_BLOCK_CLASS,
  useElementCodeHighlight,
} from "../../src/hooks/useElementCodeHighlight";

type FakeEditor = {
  getModel: jest.Mock<editor.ITextModel | null, []>;
  createDecorationsCollection: jest.Mock<
    FakeCollection,
    [editor.IModelDeltaDecoration[]]
  >;
  revealLineInCenterIfOutsideViewport: jest.Mock<void, [number]>;
};

type FakeCollection = {
  clear: jest.Mock<void, []>;
};

const makeEditor = (
  modelLines = 20,
): {
  editor: FakeEditor;
  collections: FakeCollection[];
} => {
  const collections: FakeCollection[] = [];
  const model = {
    getLineCount: () => modelLines,
    getLineMaxColumn: (line: number) => line * 10 + 1,
  } as unknown as editor.ITextModel;
  const ed: FakeEditor = {
    getModel: jest.fn<editor.ITextModel | null, []>(() => model),
    createDecorationsCollection: jest.fn<
      FakeCollection,
      [editor.IModelDeltaDecoration[]]
    >(() => {
      const collection: FakeCollection = { clear: jest.fn<void, []>() };
      collections.push(collection);
      return collection;
    }),
    revealLineInCenterIfOutsideViewport: jest.fn<void, [number]>(),
  };
  return { editor: ed, collections };
};

const runHook = (
  editorValue: FakeEditor | null,
  initial: {
    selectedIds: string[];
    content: string;
  },
) => {
  return renderHook(
    (props: { selectedIds: string[]; content: string }) => {
      const ref = useRef<editor.IStandaloneCodeEditor | null>(
        editorValue as unknown as editor.IStandaloneCodeEditor | null,
      );
      useElementCodeHighlight({
        editorRef: ref,
        selectedIds: props.selectedIds,
        content: props.content,
      });
    },
    { initialProps: initial },
  );
};

describe("useElementCodeHighlight", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockFindElementBlockRanges.mockReturnValue(new Map());
  });

  test("does nothing when editor ref is null", () => {
    runHook(null, { selectedIds: ["a"], content: "yaml" });
    expect(mockFindElementBlockRanges).not.toHaveBeenCalled();
  });

  test("does nothing when selection is empty", () => {
    const { editor: ed } = makeEditor();
    runHook(ed, { selectedIds: [], content: "yaml" });
    expect(mockFindElementBlockRanges).not.toHaveBeenCalled();
    expect(ed.createDecorationsCollection).not.toHaveBeenCalled();
  });

  test("does nothing when model is null", () => {
    const { editor: ed } = makeEditor();
    ed.getModel.mockReturnValueOnce(null);
    runHook(ed, { selectedIds: ["a"], content: "yaml" });
    expect(ed.createDecorationsCollection).not.toHaveBeenCalled();
  });

  test("does nothing when no matching ranges found", () => {
    const { editor: ed } = makeEditor();
    mockFindElementBlockRanges.mockReturnValue(new Map());
    runHook(ed, { selectedIds: ["missing"], content: "yaml" });
    expect(ed.createDecorationsCollection).not.toHaveBeenCalled();
  });

  test("creates one block decoration per matched id", () => {
    const { editor: ed } = makeEditor();
    mockFindElementBlockRanges.mockReturnValue(
      new Map([
        ["a", { startLine: 2, endLine: 5 }],
        ["b", { startLine: 6, endLine: 9 }],
      ]),
    );
    runHook(ed, { selectedIds: ["a", "b"], content: "yaml" });
    expect(ed.createDecorationsCollection).toHaveBeenCalledTimes(1);
    const decorations = ed.createDecorationsCollection.mock.calls[0][0];
    expect(decorations).toHaveLength(2);
    expect(
      decorations.every((d) => d.options.className === HIGHLIGHT_BLOCK_CLASS),
    ).toBe(true);
  });

  test("scrolls to the newly-added id on subsequent render", () => {
    const { editor: ed } = makeEditor();
    mockFindElementBlockRanges.mockImplementation((_model, ids) => {
      const map = new Map<string, { startLine: number; endLine: number }>();
      if (ids.includes("a")) map.set("a", { startLine: 2, endLine: 4 });
      if (ids.includes("b")) map.set("b", { startLine: 10, endLine: 12 });
      return map;
    });
    const { rerender } = runHook(ed, {
      selectedIds: ["a"],
      content: "yaml",
    });
    expect(ed.revealLineInCenterIfOutsideViewport).toHaveBeenLastCalledWith(2);

    rerender({ selectedIds: ["a", "b"], content: "yaml" });
    expect(ed.revealLineInCenterIfOutsideViewport).toHaveBeenLastCalledWith(10);
  });

  test("clears previous decorations on next update", () => {
    const { editor: ed, collections } = makeEditor();
    mockFindElementBlockRanges.mockReturnValue(
      new Map([["a", { startLine: 2, endLine: 4 }]]),
    );
    const { rerender } = runHook(ed, {
      selectedIds: ["a"],
      content: "yaml",
    });
    mockFindElementBlockRanges.mockReturnValue(
      new Map([["b", { startLine: 10, endLine: 12 }]]),
    );
    rerender({ selectedIds: ["b"], content: "yaml" });
    expect(collections[0].clear).toHaveBeenCalled();
  });

  test("clears decorations when selection becomes empty", () => {
    const { editor: ed, collections } = makeEditor();
    mockFindElementBlockRanges.mockReturnValue(
      new Map([["a", { startLine: 2, endLine: 4 }]]),
    );
    const { rerender } = runHook(ed, {
      selectedIds: ["a"],
      content: "yaml",
    });
    rerender({ selectedIds: [], content: "yaml" });
    expect(collections[0].clear).toHaveBeenCalled();
  });

  test("re-runs when content changes even if selection is stable", () => {
    const { editor: ed } = makeEditor();
    mockFindElementBlockRanges.mockReturnValue(
      new Map([["a", { startLine: 2, endLine: 4 }]]),
    );
    const { rerender } = runHook(ed, {
      selectedIds: ["a"],
      content: "yaml-v1",
    });
    expect(ed.createDecorationsCollection).toHaveBeenCalledTimes(1);
    rerender({ selectedIds: ["a"], content: "yaml-v2" });
    expect(ed.createDecorationsCollection).toHaveBeenCalledTimes(2);
  });

  test("clears decorations on unmount", () => {
    const { editor: ed, collections } = makeEditor();
    mockFindElementBlockRanges.mockReturnValue(
      new Map([["a", { startLine: 2, endLine: 4 }]]),
    );
    const { unmount } = runHook(ed, {
      selectedIds: ["a"],
      content: "yaml",
    });
    unmount();
    expect(collections[0].clear).toHaveBeenCalled();
  });

  test("does not scroll when nothing new is added", () => {
    const { editor: ed } = makeEditor();
    mockFindElementBlockRanges.mockReturnValue(
      new Map([["a", { startLine: 2, endLine: 4 }]]),
    );
    const { rerender } = runHook(ed, {
      selectedIds: ["a"],
      content: "yaml",
    });
    ed.revealLineInCenterIfOutsideViewport.mockClear();

    rerender({ selectedIds: ["a"], content: "yaml-updated" });
    expect(ed.revealLineInCenterIfOutsideViewport).not.toHaveBeenCalled();
  });

  test("decoration range uses model.getLineMaxColumn for end column", () => {
    const { editor: ed } = makeEditor();
    mockFindElementBlockRanges.mockReturnValue(
      new Map([["a", { startLine: 3, endLine: 5 }]]),
    );
    runHook(ed, { selectedIds: ["a"], content: "yaml" });
    const decorations = ed.createDecorationsCollection.mock.calls[0][0];
    const blockDecoration = decorations.find(
      (d) => d.options.className === HIGHLIGHT_BLOCK_CLASS,
    );
    expect(blockDecoration?.range.endColumn).toBe(5 * 10 + 1);
  });
});
