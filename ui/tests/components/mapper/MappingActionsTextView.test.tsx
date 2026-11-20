/**
 * @jest-environment jsdom
 */
import "@testing-library/jest-dom";
import { act, render, screen } from "@testing-library/react";
import { MappingActionsTextView } from "../../../src/components/mapper/MappingActionsTextView";
import { MappingActions } from "../../../src/mapper/actions-text/util";
import { MappingUtil } from "../../../src/mapper/util/mapping";
import { MappingDescription } from "../../../src/mapper/model/model";

jest.mock("monaco-editor", () => ({
  MarkerSeverity: { Error: 8 },
  editor: {},
  languages: {},
}));

jest.mock("../../../src/hooks/useMonacoTheme", () => ({
  useMonacoTheme: () => "vs",
  applyVSCodeThemeToMonaco: jest.fn(),
}));

let editorText = "";
let onContentChange: () => void = () => {};

const fakeEditor = {
  getModel: () => ({}),
  getValue: () => editorText,
  onDidDispose: jest.fn(),
  onDidChangeModelContent: (listener: () => void) => {
    onContentChange = listener;
  },
};

const fakeMonaco = {
  languages: {
    getLanguages: () => [],
    register: jest.fn(),
    setLanguageConfiguration: jest.fn(),
    setMonarchTokensProvider: jest.fn(),
    registerCompletionItemProvider: () => ({ dispose: jest.fn() }),
  },
  editor: { setModelMarkers: jest.fn() },
};

jest.mock("@monaco-editor/react", () => ({
  Editor: (props: {
    value?: string;
    onMount?: (editor: unknown, monaco: unknown) => void;
  }) => {
    const r = jest.requireActual<typeof import("react")>("react");
    r.useEffect(() => {
      props.onMount?.(fakeEditor, fakeMonaco);
    }, []);
    return r.createElement("pre", { "data-testid": "editor" }, props.value);
  },
}));

// Monaco reports the change itself; the component only sees the listener fire.
function type(text: string) {
  editorText = text;
  act(() => onContentChange());
}

function parse(text: string): MappingDescription {
  return MappingActions.updateFromString(text, MappingUtil.emptyMapping())
    .mapping;
}

function shownText(): string {
  return screen.getByTestId("editor").textContent ?? "";
}

describe("MappingActionsTextView", () => {
  beforeEach(() => {
    editorText = "";
  });

  it("should keep the typed text when the form returns a mapping the editor emitted before", () => {
    const onChange = jest.fn();
    const { rerender } = render(
      <MappingActionsTextView
        mapping={MappingUtil.emptyMapping()}
        onChange={onChange}
      />,
    );

    type("header.in -> header.ou");
    type("header.in -> header.out");
    const [[olderMapping]] = onChange.mock.calls as [[MappingDescription]];

    rerender(
      <MappingActionsTextView mapping={olderMapping} onChange={onChange} />,
    );

    expect(shownText()).not.toBe(MappingActions.toString(olderMapping));
  });

  it("should show a mapping from outside the editor when the user has typed", () => {
    const onChange = jest.fn();
    const { rerender } = render(
      <MappingActionsTextView
        mapping={MappingUtil.emptyMapping()}
        onChange={onChange}
      />,
    );
    type("header.in -> header.out");

    const external = parse("header.a -> header.b");
    rerender(<MappingActionsTextView mapping={external} onChange={onChange} />);

    expect(shownText()).toBe(MappingActions.toString(external));
  });
});
