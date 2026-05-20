/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, act } from "@testing-library/react";
import "@testing-library/jest-dom";

const mockApplyTheme = jest.fn<void, [unknown]>();
const mockUseMonacoTheme = jest.fn<string, []>(() => "vs");
jest.mock("../../../src/hooks/useMonacoTheme", () => ({
  useMonacoTheme: (): string => mockUseMonacoTheme(),
  applyVSCodeThemeToMonaco: (m: unknown): void => {
    mockApplyTheme(m);
  },
}));

let mockElementAsCode: { code: string } | undefined = { code: "yaml" };
const mockUseElementsAsCode =
  jest.fn<void, [string, (string | number)?]>();
jest.mock("../../../src/hooks/useElementsAsCode", () => ({
  useElementsAsCode: (
    chainId: string,
    cacheKey?: string | number,
  ): {
    elementAsCode: { code: string } | undefined;
    refresh: jest.Mock<void, []>;
  } => {
    mockUseElementsAsCode(chainId, cacheKey);
    return { elementAsCode: mockElementAsCode, refresh: jest.fn<void, []>() };
  },
}));

type StoreSelector<T> = (state: unknown) => T;
type StoreEqualityFn<T> = (a: T, b: T) => boolean;
type UseStoreCall = (
  selector: StoreSelector<unknown>,
  equalityFn?: StoreEqualityFn<unknown>,
) => unknown;

let mockUseStoreImpl: UseStoreCall = () => [];
jest.mock("@xyflow/react", () => ({
  useStore: (
    selector: StoreSelector<unknown>,
    equalityFn?: StoreEqualityFn<unknown>,
  ) => mockUseStoreImpl(selector, equalityFn),
}));

type HighlightOptions = {
  editorRef: React.MutableRefObject<unknown>;
  selectedIds: readonly string[];
  content: string;
};
const mockUseElementCodeHighlight =
  jest.fn<void, [HighlightOptions]>();
jest.mock("../../../src/hooks/useElementCodeHighlight", () => ({
  useElementCodeHighlight: (opts: HighlightOptions): void => {
    mockUseElementCodeHighlight(opts);
  },
}));

type EditorProps = {
  value?: string;
  theme?: string;
  language?: string;
  onMount?: (editorInstance: unknown, monaco: unknown) => void;
  options?: Record<string, unknown>;
};

let lastEditorProps: EditorProps | null = null;
const monacoFake = { __tag: "monaco" };
const editorInstanceFake = { __tag: "editor" };

let mountMonacoOverride: unknown = monacoFake;

jest.mock("@monaco-editor/react", () => ({
  Editor: (props: EditorProps) => {
    lastEditorProps = props;
    const r = jest.requireActual<typeof import("react")>("react");
    r.useEffect(() => {
      props.onMount?.(editorInstanceFake, mountMonacoOverride);
    }, []);
    return r.createElement(
      "div",
      { "data-testid": "editor", "data-theme": props.theme },
      props.value,
    );
  },
}));

import { ChainTextViewPanel } from "../../../src/components/chains/ChainTextViewPanel";
import type { Element } from "../../../src/api/apiTypes";

const mkElement = (id: string, modifiedWhen?: number): Element =>
  ({
    id,
    name: id,
    description: "",
    chainId: "chain-1",
    type: "script",
    properties: undefined as never,
    mandatoryChecksPassed: true,
    modifiedWhen,
  }) as Element;

describe("ChainTextViewPanel", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockElementAsCode = { code: "yaml" };
    mockUseStoreImpl = () => [] as string[];
    mockUseMonacoTheme.mockReturnValue("vs");
    lastEditorProps = null;
    mountMonacoOverride = monacoFake;
  });

  test("renders the Editor with yaml language", () => {
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    expect(screen.getByTestId("editor")).toBeInTheDocument();
    expect(lastEditorProps?.language).toBe("yaml");
  });

  test("passes the fetched code as Editor value", () => {
    mockElementAsCode = { code: "hello yaml" };
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    expect(lastEditorProps?.value).toBe("hello yaml");
  });

  test("renders empty string when elementAsCode is undefined", () => {
    mockElementAsCode = undefined;
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    expect(lastEditorProps?.value).toBe("");
  });

  test("ignores non-string code field", () => {
    mockElementAsCode = { code: 42 as unknown as string };
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    expect(lastEditorProps?.value).toBe("");
  });

  test("passes theme from useMonacoTheme", () => {
    mockUseMonacoTheme.mockReturnValue("hc-black");
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    expect(lastEditorProps?.theme).toBe("hc-black");
  });

  test("applies read-only, folding, minimap disabled", () => {
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    const opts = lastEditorProps?.options ?? {};
    expect(opts.readOnly).toBe(true);
    expect(opts.folding).toBe(true);
    expect((opts.minimap as { enabled: boolean } | undefined)?.enabled).toBe(
      false,
    );
    expect(opts.scrollBeyondLastLine).toBe(false);
  });

  test("calls applyVSCodeThemeToMonaco on mount with the monaco namespace", () => {
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    expect(mockApplyTheme).toHaveBeenCalledWith(monacoFake);
  });

  test("forwards chainId and elements signature to useElementsAsCode", () => {
    const elements = [mkElement("a", 100), mkElement("b", 200)];
    render(<ChainTextViewPanel chainId="chain-42" elements={elements} />);
    expect(mockUseElementsAsCode).toHaveBeenLastCalledWith(
      "chain-42",
      "a:100|b:200",
    );
  });

  test("signature does not change when elements array reference changes but content is identical", () => {
    const first = [mkElement("a", 1)];
    const { rerender } = render(
      <ChainTextViewPanel chainId="c" elements={first} />,
    );
    mockUseElementsAsCode.mockClear();
    const second = [mkElement("a", 1)];
    rerender(<ChainTextViewPanel chainId="c" elements={second} />);
    const calls = mockUseElementsAsCode.mock.calls;
    const signatures = calls.map((c) => c[1]);
    expect(signatures.every((s) => s === "a:1")).toBe(true);
  });

  test("forwards content to useElementCodeHighlight", () => {
    mockElementAsCode = { code: "some yaml" };
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    const opts = mockUseElementCodeHighlight.mock.calls.at(-1)?.[0];
    expect(opts?.content).toBe("some yaml");
  });

  test("forwards selected ids returned by the xyflow store selector", () => {
    mockUseStoreImpl = () => ["node-a", "node-b"];
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    const opts = mockUseElementCodeHighlight.mock.calls.at(-1)?.[0];
    expect(opts?.selectedIds).toEqual(["node-a", "node-b"]);
  });

  test("passes empty selection when the store returns an empty array", () => {
    mockUseStoreImpl = () => [] as string[];
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    const opts = mockUseElementCodeHighlight.mock.calls.at(-1)?.[0];
    expect(opts?.selectedIds).toEqual([]);
  });

  test("store selector collects ids of selected nodes in sorted order", () => {
    let capturedSelector: StoreSelector<unknown> | undefined;
    mockUseStoreImpl = (selector) => {
      capturedSelector = selector;
      return [] as string[];
    };
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    expect(capturedSelector).toBeDefined();
    const fakeState = {
      nodeLookup: new Map([
        ["n2", { id: "n2", selected: true }],
        ["n3", { id: "n3", selected: false }],
        ["n1", { id: "n1", selected: true }],
      ]),
    };
    expect(capturedSelector?.(fakeState)).toEqual(["n1", "n2"]);
  });

  test("uses a shallow-array equality comparator so identical selections don't re-render", () => {
    let capturedEquality: StoreEqualityFn<unknown> | undefined;
    mockUseStoreImpl = (_selector, equalityFn) => {
      capturedEquality = equalityFn;
      return [] as string[];
    };
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    expect(capturedEquality).toBeDefined();
    const eq = capturedEquality as StoreEqualityFn<string[]>;
    expect(eq(["a", "b"], ["a", "b"])).toBe(true);
    expect(eq(["a", "b"], ["a", "c"])).toBe(false);
    expect(eq(["a"], ["a", "b"])).toBe(false);
  });

  test("forwards the editor ref to the highlight hook after onMount", () => {
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    const opts = mockUseElementCodeHighlight.mock.calls.at(-1)?.[0];
    expect(opts?.editorRef.current).toBe(editorInstanceFake);
  });

  test("does not apply theme when onMount receives a nullish monaco", () => {
    mountMonacoOverride = null;
    render(<ChainTextViewPanel chainId="c" elements={[]} />);
    expect(mockApplyTheme).not.toHaveBeenCalled();
  });

  test("updates the highlight hook when elementAsCode changes", () => {
    mockElementAsCode = { code: "v1" };
    const { rerender } = render(
      <ChainTextViewPanel chainId="c" elements={[]} />,
    );
    mockUseElementCodeHighlight.mockClear();
    act(() => {
      mockElementAsCode = { code: "v2" };
    });
    rerender(<ChainTextViewPanel chainId="c" elements={[]} />);
    const opts = mockUseElementCodeHighlight.mock.calls.at(-1)?.[0];
    expect(opts?.content).toBe("v2");
  });
});
