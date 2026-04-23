/**
 * @jest-environment jsdom
 */
import { render, act } from "@testing-library/react";
import {
  describe,
  it,
  expect,
  jest,
  beforeEach,
  afterEach,
} from "@jest/globals";

const applyVSCodeThemeToMonaco = jest.fn();
const useMonacoThemeMock = jest.fn(() => "vs-dark");
const useMonacoEditorOptionsMock = jest.fn(() => ({
  fontSize: 12,
  lineHeight: 18,
  fontFamily: "monospace",
  fontWeight: "normal",
}));

jest.mock("../../src/hooks/useMonacoTheme", () => ({
  useMonacoTheme: () => useMonacoThemeMock(),
  useMonacoEditorOptions: () => useMonacoEditorOptionsMock(),
  applyVSCodeThemeToMonaco: (monaco: unknown) =>
    applyVSCodeThemeToMonaco(monaco),
}));

jest.mock("monaco-editor", () => ({
  editor: {},
  languages: {
    CompletionItemKind: { Variable: 4, Method: 0 },
    CompletionItemInsertTextRule: { InsertAsSnippet: 4 },
  },
}));

type EditorProps = {
  value?: string;
  language?: string;
  theme?: string;
  height?: number | string;
  className?: string;
  beforeMount?: (monaco: FakeMonaco) => void;
  onMount?: (editor: unknown, monaco: FakeMonaco) => void;
  onChange?: (value: string | undefined) => void;
  options?: Record<string, unknown>;
};

type FakeMonaco = {
  languages: {
    getLanguages: jest.Mock;
    register: jest.Mock;
    setMonarchTokensProvider: jest.Mock;
    registerCompletionItemProvider: jest.Mock;
  };
};

let capturedEditorProps: EditorProps | null = null;
let latestFakeMonaco: FakeMonaco | null = null;
const initialLanguagesRef: { value: { id: string }[] } = { value: [] };

function createFakeMonaco(initial: { id: string }[] = []): FakeMonaco {
  const registered = [...initial];
  const register = jest.fn();
  register.mockImplementation((lang: unknown) => {
    registered.push(lang as { id: string });
  });
  return {
    languages: {
      getLanguages: jest.fn(() => registered),
      register,
      setMonarchTokensProvider: jest.fn(),
      registerCompletionItemProvider: jest.fn(),
    },
  };
}

jest.mock("@monaco-editor/react", () => {
  const actualReact = jest.requireActual<typeof import("react")>("react");
  return {
    __esModule: true,
    Editor: (props: EditorProps) => {
      capturedEditorProps = props;
      actualReact.useEffect(() => {
        latestFakeMonaco = createFakeMonaco(initialLanguagesRef.value);
        props.beforeMount?.(latestFakeMonaco);
        props.onMount?.({}, latestFakeMonaco);
      }, []);
      return actualReact.createElement("div", { "data-testid": "editor" });
    },
  };
});

import { Script } from "../../src/components/Script";

function cleanBody(): void {
  while (document.body.firstChild) {
    document.body.removeChild(document.body.firstChild);
  }
}

describe("Script", () => {
  beforeEach(() => {
    applyVSCodeThemeToMonaco.mockClear();
    useMonacoThemeMock.mockReturnValue("vs-dark");
    useMonacoEditorOptionsMock.mockReturnValue({
      fontSize: 12,
      lineHeight: 18,
      fontFamily: "monospace",
      fontWeight: "normal",
    });
    capturedEditorProps = null;
    latestFakeMonaco = null;
    initialLanguagesRef.value = [];
  });

  afterEach(() => {
    cleanBody();
  });

  describe("rendering", () => {
    it("renders an Editor element", () => {
      const { getByTestId } = render(<Script value="" />);
      expect(getByTestId("editor")).toBeTruthy();
    });

    it("uses groovy language by default", () => {
      render(<Script value="" />);
      expect(capturedEditorProps?.language).toBe("groovy");
    });

    it("uses json language when mode='json'", () => {
      render(<Script value='{"a":1}' mode="json" />);
      expect(capturedEditorProps?.language).toBe("json");
    });

    it("passes value to editor", () => {
      render(<Script value="hello" />);
      expect(capturedEditorProps?.value).toBe("hello");
    });

    it("passes theme from useMonacoTheme", () => {
      useMonacoThemeMock.mockReturnValue("hc-black");
      render(<Script value="" />);
      expect(capturedEditorProps?.theme).toBe("hc-black");
    });

    it("applies readOnly=false by default", () => {
      render(<Script value="" />);
      expect(capturedEditorProps?.options?.readOnly).toBe(false);
    });

    it("applies readOnly=true when requested", () => {
      render(<Script value="" readOnly />);
      expect(capturedEditorProps?.options?.readOnly).toBe(true);
    });

    it("propagates font options from useMonacoEditorOptions", () => {
      useMonacoEditorOptionsMock.mockReturnValue({
        fontSize: 20,
        lineHeight: 28,
        fontFamily: "Consolas",
        fontWeight: "bold",
      });
      render(<Script value="" />);
      expect(capturedEditorProps?.options?.fontSize).toBe(20);
      expect(capturedEditorProps?.options?.lineHeight).toBe(28);
      expect(capturedEditorProps?.options?.fontFamily).toBe("Consolas");
      expect(capturedEditorProps?.options?.fontWeight).toBe("bold");
    });

    it("spreads extra HTML attributes onto the container", () => {
      const { container } = render(<Script value="" data-custom-attr="wrap" />);
      expect(container.querySelector('[data-custom-attr="wrap"]')).toBeTruthy();
    });
  });

  describe("onChange", () => {
    it("invokes onChange with value when not readOnly", () => {
      const onChange = jest.fn();
      render(<Script value="" onChange={onChange} />);
      act(() => {
        capturedEditorProps?.onChange?.("new value");
      });
      expect(onChange).toHaveBeenCalledWith("new value");
    });

    it("does not invoke onChange when readOnly", () => {
      const onChange = jest.fn();
      render(<Script value="" onChange={onChange} readOnly />);
      act(() => {
        capturedEditorProps?.onChange?.("new value");
      });
      expect(onChange).not.toHaveBeenCalled();
    });

    it("substitutes empty string when value is undefined", () => {
      const onChange = jest.fn();
      render(<Script value="" onChange={onChange} />);
      act(() => {
        capturedEditorProps?.onChange?.(undefined);
      });
      expect(onChange).toHaveBeenCalledWith("");
    });

    it("does not throw when onChange is omitted", () => {
      render(<Script value="" />);
      expect(() => {
        act(() => {
          capturedEditorProps?.onChange?.("x");
        });
      }).not.toThrow();
    });
  });

  describe("beforeMount / onMount", () => {
    it("registers groovy language when not already registered", () => {
      render(<Script value="" />);
      expect(latestFakeMonaco?.languages.register).toHaveBeenCalledWith({
        id: "groovy",
      });
      expect(
        latestFakeMonaco?.languages.setMonarchTokensProvider,
      ).toHaveBeenCalledTimes(1);
      expect(
        latestFakeMonaco?.languages.registerCompletionItemProvider,
      ).toHaveBeenCalledWith("groovy", expect.anything());
    });

    it("skips registration if groovy is already registered", () => {
      initialLanguagesRef.value = [{ id: "groovy" }];
      render(<Script value="" />);
      expect(latestFakeMonaco?.languages.register).not.toHaveBeenCalled();
      expect(
        latestFakeMonaco?.languages.setMonarchTokensProvider,
      ).not.toHaveBeenCalled();
      expect(
        latestFakeMonaco?.languages.registerCompletionItemProvider,
      ).not.toHaveBeenCalled();
    });

    it("applies VSCode theme on mount", () => {
      render(<Script value="" />);
      expect(applyVSCodeThemeToMonaco).toHaveBeenCalledWith(latestFakeMonaco);
    });

    it("reapplies VSCode theme when theme changes", () => {
      const { rerender } = render(<Script value="" />);
      const before = applyVSCodeThemeToMonaco.mock.calls.length;

      useMonacoThemeMock.mockReturnValue("hc-black");
      rerender(<Script value="" />);

      expect(applyVSCodeThemeToMonaco.mock.calls.length).toBeGreaterThan(
        before,
      );
    });
  });

  describe("overflow widgets DOM node", () => {
    it("creates a div with monaco-editor class appended to body", () => {
      render(<Script value="" />);
      const node = capturedEditorProps?.options
        ?.overflowWidgetsDomNode as HTMLDivElement;
      expect(node).toBeInstanceOf(HTMLDivElement);
      expect(node.className).toBe("monaco-editor");
      expect(node.style.zIndex).toBe("10000");
      expect(node.parentElement).toBe(document.body);
    });

    it("enables fixedOverflowWidgets", () => {
      render(<Script value="" />);
      expect(capturedEditorProps?.options?.fixedOverflowWidgets).toBe(true);
    });

    it("removes the node from body on unmount", () => {
      const { unmount } = render(<Script value="" />);
      const node = capturedEditorProps?.options
        ?.overflowWidgetsDomNode as HTMLDivElement;
      expect(document.body.contains(node)).toBe(true);
      unmount();
      expect(document.body.contains(node)).toBe(false);
    });
  });

  describe("height calculation", () => {
    function mockRect(el: Element, rect: Partial<DOMRect>): void {
      el.getBoundingClientRect = () =>
        ({
          top: 0,
          bottom: 0,
          left: 0,
          right: 0,
          width: 0,
          height: 0,
          x: 0,
          y: 0,
          toJSON: () => ({}),
          ...rect,
        }) as DOMRect;
    }

    it("defaults to 300px height", () => {
      render(<Script value="" />);
      expect(capturedEditorProps?.height).toBe(300);
    });

    it("updates height on window resize when available space > 300", () => {
      const modalBody = document.createElement("div");
      modalBody.className = "ant-modal-body";
      document.body.appendChild(modalBody);

      const { container } = render(<Script value="" />, {
        container: modalBody,
      });
      const flexDiv = container.firstElementChild as HTMLElement;

      mockRect(modalBody, { top: 0, bottom: 1000 });
      mockRect(flexDiv, { top: 100, bottom: 200 });

      act(() => {
        window.dispatchEvent(new Event("resize"));
      });

      expect(capturedEditorProps?.height).toBe(1000 - 100 - 60);
    });

    it("does not update height when available space <= 300", () => {
      const modalBody = document.createElement("div");
      modalBody.className = "ant-modal-body";
      document.body.appendChild(modalBody);

      const { container } = render(<Script value="" />, {
        container: modalBody,
      });
      const flexDiv = container.firstElementChild as HTMLElement;

      mockRect(modalBody, { top: 0, bottom: 300 });
      mockRect(flexDiv, { top: 50, bottom: 100 });

      act(() => {
        window.dispatchEvent(new Event("resize"));
      });

      expect(capturedEditorProps?.height).toBe(300);
    });

    it("uses parentElement when no ant-modal-body ancestor exists", () => {
      const parent = document.createElement("div");
      document.body.appendChild(parent);

      const { container } = render(<Script value="" />, { container: parent });
      const flexDiv = container.firstElementChild as HTMLElement;

      mockRect(parent, { top: 0, bottom: 900 });
      mockRect(flexDiv, { top: 0, bottom: 100 });

      act(() => {
        window.dispatchEvent(new Event("resize"));
      });

      expect(capturedEditorProps?.height).toBe(900 - 60);
    });

    it("recalculates height on ant-modal-wrap transitionend", () => {
      const modalWrap = document.createElement("div");
      modalWrap.className = "ant-modal-wrap";
      document.body.appendChild(modalWrap);
      const modalBody = document.createElement("div");
      modalBody.className = "ant-modal-body";
      modalWrap.appendChild(modalBody);

      const { container } = render(<Script value="" />, {
        container: modalBody,
      });
      const flexDiv = container.firstElementChild as HTMLElement;

      mockRect(modalBody, { top: 0, bottom: 800 });
      mockRect(flexDiv, { top: 0, bottom: 50 });

      act(() => {
        modalWrap.dispatchEvent(new Event("transitionend"));
      });

      expect(capturedEditorProps?.height).toBe(800 - 60);
    });

    it("does not update height when container has no scroll parent", () => {
      const parent = document.createElement("div");
      document.body.appendChild(parent);

      const { container } = render(<Script value="" />, { container: parent });
      const flexDiv = container.firstElementChild as HTMLElement;

      Object.defineProperty(flexDiv, "parentElement", {
        configurable: true,
        get: () => null,
      });
      flexDiv.closest = () => null;

      act(() => {
        window.dispatchEvent(new Event("resize"));
      });

      expect(capturedEditorProps?.height).toBe(300);
    });

    it("removes window resize listener on unmount", () => {
      const removeSpy = jest.spyOn(window, "removeEventListener");
      const { unmount } = render(<Script value="" />);
      unmount();
      expect(removeSpy).toHaveBeenCalledWith("resize", expect.any(Function));
      removeSpy.mockRestore();
    });

    it("removes transitionend listener on unmount when ant-modal-wrap exists", () => {
      const modalWrap = document.createElement("div");
      modalWrap.className = "ant-modal-wrap";
      document.body.appendChild(modalWrap);
      const modalBody = document.createElement("div");
      modalBody.className = "ant-modal-body";
      modalWrap.appendChild(modalBody);

      const removeSpy = jest.spyOn(modalWrap, "removeEventListener");
      const { unmount } = render(<Script value="" />, {
        container: modalBody,
      });
      unmount();
      expect(removeSpy).toHaveBeenCalledWith(
        "transitionend",
        expect.any(Function),
      );
    });
  });

  describe("Groovy completion provider", () => {
    type Provider = {
      provideCompletionItems: (
        model: {
          getWordUntilPosition: (pos: unknown) => {
            startColumn: number;
            endColumn: number;
            word: string;
          };
        },
        position: { lineNumber: number; column: number },
      ) => { suggestions: Array<Record<string, unknown>> };
    };

    function getProvider(): Provider {
      const calls =
        latestFakeMonaco?.languages.registerCompletionItemProvider.mock.calls ??
        [];
      const [, provider] = calls[0] as [string, Provider];
      return provider;
    }

    it("returns the expected suggestions", () => {
      render(<Script value="" />);
      const provider = getProvider();
      const result = provider.provideCompletionItems(
        {
          getWordUntilPosition: () => ({
            startColumn: 1,
            endColumn: 4,
            word: "exc",
          }),
        },
        { lineNumber: 2, column: 4 },
      );

      expect(result.suggestions).toHaveLength(6);
      expect(result.suggestions.map((s) => s.label as string)).toEqual([
        "exchange",
        "getMessage",
        "setMessage",
        "getProperty",
        "setProperty",
        "removeProperty",
      ]);
    });

    it("computes range based on current word and position", () => {
      render(<Script value="" />);
      const provider = getProvider();
      const result = provider.provideCompletionItems(
        {
          getWordUntilPosition: () => ({
            startColumn: 5,
            endColumn: 9,
            word: "some",
          }),
        },
        { lineNumber: 7, column: 9 },
      );

      const expectedRange = {
        startLineNumber: 7,
        endLineNumber: 7,
        startColumn: 5,
        endColumn: 9,
      };
      for (const suggestion of result.suggestions) {
        expect(suggestion.range).toEqual(expectedRange);
      }
    });

    it("sets kind, insertText and insertTextRules correctly", () => {
      render(<Script value="" />);
      const provider = getProvider();
      const result = provider.provideCompletionItems(
        {
          getWordUntilPosition: () => ({
            startColumn: 1,
            endColumn: 1,
            word: "",
          }),
        },
        { lineNumber: 1, column: 1 },
      );

      const byLabel = Object.fromEntries(
        result.suggestions.map((s) => [s.label as string, s]),
      );
      expect(byLabel.exchange.kind).toBe(4);
      expect(byLabel.exchange.insertText).toBe("exchange");
      expect(byLabel.getMessage.kind).toBe(0);
      expect(byLabel.getMessage.insertText).toBe("getMessage()");
      expect(byLabel.setMessage.insertText).toBe("setMessage($0)");
      expect(byLabel.setMessage.insertTextRules).toBe(4);
      expect(byLabel.getProperty.insertText).toBe("getProperty($0)");
      expect(byLabel.setProperty.insertText).toBe("setProperty($0)");
      expect(byLabel.removeProperty.insertText).toBe("removeProperty($0)");
    });
  });
});
