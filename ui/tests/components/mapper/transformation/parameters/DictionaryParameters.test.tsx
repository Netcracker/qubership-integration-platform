/**
 * @jest-environment jsdom
 */
import React from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { EditorProps } from "@monaco-editor/react";
import { Form } from "antd";
import { DictionaryParameters } from "../../../../../src/components/mapper/transformation/parameters/DictionaryParameters";

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

jest.mock("@monaco-editor/react", () => {
  const actualReact = jest.requireActual<typeof import("react")>("react");
  return {
    __esModule: true,
    Editor: (props: EditorProps) => {
      actualReact.useEffect(() => {
        props.onMount?.({ getModel: () => ({}) } as never, {
          MarkerSeverity: { Error: 8 },
          editor: { setModelMarkers: jest.fn() },
          languages: {
            getLanguages: () => [],
            register: jest.fn(),
            setLanguageConfiguration: jest.fn(),
            setMonarchTokensProvider: jest.fn(),
          },
        });
      }, []);
      return actualReact.createElement("textarea", {
        "data-testid": "monaco",
        value: props.value,
        onChange: (e: React.ChangeEvent<HTMLTextAreaElement>) =>
          props.onChange?.(e.target.value, undefined as never),
      });
    },
  };
});

jest.mock("../../../../../src/hooks/useMonacoTheme", () => ({
  useMonacoTheme: () => "vs",
  applyVSCodeThemeToMonaco: jest.fn(),
}));

describe("DictionaryParameters", () => {
  it("should keep rules text that does not parse when the default changes", () => {
    render(
      <Form initialValues={{ parameters: ["", "a=1"] }}>
        <DictionaryParameters />
      </Form>,
    );
    fireEvent.click(screen.getByText("Text"));
    const editor = screen.getByTestId("monaco");

    fireEvent.change(editor, { target: { value: "a=1;\nb" } });
    fireEvent.change(screen.getByLabelText("Default"), {
      target: { value: "x" },
    });

    expect(editor).toHaveValue("a=1;\nb");
  });
});
