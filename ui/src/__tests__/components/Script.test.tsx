/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import { render } from "@testing-library/react";

let lastEditorProps: Record<string, unknown> = {};

jest.mock("@monaco-editor/react", () => ({
  __esModule: true,
  Editor: (props: Record<string, unknown>) => {
    lastEditorProps = props;
    return <div data-testid="monaco-editor" data-language={props.language} />;
  },
}));

jest.mock("monaco-editor", () => ({
  languages: {
    CompletionItemKind: { Variable: 0, Method: 1 },
    CompletionItemInsertTextRule: { InsertAsSnippet: 4 },
    getLanguages: () => [],
    register: jest.fn(),
    setMonarchTokensProvider: jest.fn(),
    registerCompletionItemProvider: jest.fn(),
  },
  editor: {},
}));

jest.mock("../../hooks/useMonacoTheme", () => ({
  useMonacoTheme: () => "vs",
  useMonacoEditorOptions: () => ({
    fontSize: 14,
    lineHeight: 20,
    fontFamily: "monospace",
    fontWeight: "normal",
  }),
  applyVSCodeThemeToMonaco: jest.fn(),
}));

import { Script } from "../../components/Script";

describe("Script", () => {
  beforeEach(() => {
    lastEditorProps = {};
  });

  it("renders without crashing", () => {
    const { getByTestId } = render(<Script value="println 'hello'" />);
    expect(getByTestId("monaco-editor")).toBeTruthy();
  });

  it("uses groovy language by default", () => {
    render(<Script value="" />);
    expect(lastEditorProps.language).toBe("groovy");
  });

  it("uses json language when mode=json", () => {
    render(<Script value="{}" mode="json" />);
    expect(lastEditorProps.language).toBe("json");
  });

  it("passes readOnly option", () => {
    render(<Script value="" readOnly={true} />);
    const options = lastEditorProps.options as Record<string, unknown>;
    expect(options.readOnly).toBe(true);
  });

  it("passes value to editor", () => {
    render(<Script value="test code" />);
    expect(lastEditorProps.value).toBe("test code");
  });
});
