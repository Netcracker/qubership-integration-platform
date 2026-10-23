/**
 * @jest-environment jsdom
 */
import React, { useState } from "react";
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { EditorProps } from "@monaco-editor/react";
import { KeyValuePairsTextEditor } from "../../../../src/components/table/kv/KeyValuePairsTextEditor";
import { KeyValuePair } from "../../../../src/misc/key-value-text-util";

const mockSetModelMarkers = jest.fn();
const mockModel = {};

jest.mock("@monaco-editor/react", () => {
  const actualReact = jest.requireActual<typeof import("react")>("react");
  return {
    __esModule: true,
    Editor: (props: EditorProps) => {
      actualReact.useEffect(() => {
        props.onMount?.({ getModel: () => mockModel } as never, {
          MarkerSeverity: { Error: 8 },
          editor: { setModelMarkers: mockSetModelMarkers },
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

jest.mock("../../../../src/hooks/useMonacoTheme", () => ({
  useMonacoTheme: () => "vs",
  applyVSCodeThemeToMonaco: jest.fn(),
}));

describe("KeyValuePairsTextEditor", () => {
  it("should show pairs as escaped text", () => {
    render(
      <KeyValuePairsTextEditor
        value={[
          { key: "a", value: "1" },
          { key: "b=c", value: "x;y" },
        ]}
      />,
    );

    expect(screen.getByTestId("monaco")).toHaveValue("a=1;\nb\\=c=x\\;y;");
  });

  it("should parse the shown text when the last pair is edited", () => {
    const onChange = jest.fn();
    render(
      <KeyValuePairsTextEditor
        value={[
          { key: "a", value: "1" },
          { key: "b", value: "2" },
        ]}
        onChange={onChange}
      />,
    );
    const textArea = screen.getByTestId("monaco");

    fireEvent.change(textArea, {
      target: {
        value: (textArea as HTMLTextAreaElement).value.replace("2", "3"),
      },
    });

    expect(onChange).toHaveBeenCalledWith([
      { key: "a", value: "1" },
      { key: "b", value: "3" },
    ]);
  });

  it("should keep the typed text when the parsed value comes back", () => {
    const Controlled = () => {
      const [value, setValue] = useState<KeyValuePair[]>([]);
      return <KeyValuePairsTextEditor value={value} onChange={setValue} />;
    };
    render(<Controlled />);

    fireEvent.change(screen.getByTestId("monaco"), {
      target: { value: "a = 1;\n" },
    });

    expect(screen.getByTestId("monaco")).toHaveValue("a = 1;\n");
  });

  it("should replace the text when the value changes from outside", () => {
    const { rerender } = render(
      <KeyValuePairsTextEditor value={[{ key: "a", value: "1" }]} />,
    );
    fireEvent.change(screen.getByTestId("monaco"), {
      target: { value: "a=2;" },
    });

    rerender(<KeyValuePairsTextEditor value={[{ key: "b", value: "1" }]} />);

    expect(screen.getByTestId("monaco")).toHaveValue("b=1;");
  });

  it("should call onChange with parsed pairs and clear markers when text is valid", () => {
    const onChange = jest.fn();
    render(<KeyValuePairsTextEditor value={[]} onChange={onChange} />);

    fireEvent.change(screen.getByTestId("monaco"), {
      target: { value: "a=1;\nb\\;=2;" },
    });

    expect(onChange).toHaveBeenCalledWith([
      { key: "a", value: "1" },
      { key: "b;", value: "2" },
    ]);
    expect(mockSetModelMarkers).toHaveBeenLastCalledWith(
      mockModel,
      "cip-kv-pairs",
      [],
    );
  });

  it("should keep the first pair when the text repeats a key", () => {
    const onChange = jest.fn();
    render(<KeyValuePairsTextEditor value={[]} onChange={onChange} />);

    fireEvent.change(screen.getByTestId("monaco"), {
      target: { value: "client.id=first;\nclient.id=second;" },
    });

    expect(onChange).toHaveBeenCalledWith([
      { key: "client.id", value: "first" },
    ]);
  });

  it("should set an error marker and keep the value when text is invalid", () => {
    const onChange = jest.fn();
    render(<KeyValuePairsTextEditor value={[]} onChange={onChange} />);

    fireEvent.change(screen.getByTestId("monaco"), {
      target: { value: "a=1" },
    });

    expect(onChange).not.toHaveBeenCalled();
    expect(screen.getByTestId("monaco")).toHaveValue("a=1");
    expect(mockSetModelMarkers).toHaveBeenLastCalledWith(
      mockModel,
      "cip-kv-pairs",
      [
        expect.objectContaining({
          severity: 8,
          startLineNumber: 1,
          endLineNumber: 1,
        }),
      ],
    );
  });
});
