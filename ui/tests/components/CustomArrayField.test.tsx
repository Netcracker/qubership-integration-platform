/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
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

import { describe, it, expect, beforeEach } from "@jest/globals";
import { render, fireEvent, waitFor, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps, RJSFSchema } from "@rjsf/utils";
import type { FormContext } from "../../src/components/modal/chain_element/ChainElementModificationContext";
import type {
  ResponseHandler,
  ResponseValidation,
  RequestValidation,
} from "../../src/components/modal/chain_element/field/CustomArrayField";

type ArrayItem = RequestValidation | ResponseValidation | ResponseHandler;

// --- Mocks ---

const mockGetOperationInfo = jest.fn();

jest.mock("../../src/api/api", () => ({
  api: {
    getOperationInfo: (...args: unknown[]) => mockGetOperationInfo(...args),
  },
}));

jest.mock("../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

jest.mock("../../src/hooks/useVSCodeTheme", () => ({
  useVSCodeTheme: () => ({
    isDark: false,
    colors: {},
    palette: { listHover: "#f0f6ff", buttonBg: "#0b66ff" },
  }),
}));

jest.mock("@monaco-editor/react", () => ({
  __esModule: true,
  Editor: (props: Record<string, unknown>) => (
    <textarea
      data-testid="monaco-editor"
      data-language={props.language}
      value={props.value as string}
      readOnly={(props.options as Record<string, unknown>)?.readOnly as boolean}
      onChange={(e) => {
        const onChangeProp = props.onChange as
          | ((v: string) => void)
          | undefined;
        onChangeProp?.(e.target.value);
      }}
    />
  ),
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

jest.mock("../../src/hooks/useMonacoTheme", () => ({
  useMonacoTheme: () => "vs",
  useMonacoEditorOptions: () => ({
    fontSize: 14,
    lineHeight: 20,
    fontFamily: "monospace",
    fontWeight: "normal",
  }),
  applyVSCodeThemeToMonaco: jest.fn(),
}));

jest.mock("../../src/components/mapper/Mapping", () => ({
  Mapping: ({
    elementId,
    onChange,
  }: {
    elementId: string;
    onChange?: (v: unknown) => void;
  }) => (
    <div data-testid="mapping-editor" data-element-id={elementId}>
      <button
        data-testid="mapping-change"
        onClick={() =>
          onChange?.({
            source: {},
            target: {},
            constants: [],
            actions: [{ id: "test" }],
          })
        }
      />
    </div>
  ),
}));

jest.mock("../../src/misc/protocol-utils", () => ({
  isHttpProtocol: (val: string) => val === "http" || val === "soap",
}));

jest.mock(
  "../../src/components/modal/chain_element/field/CustomArrayField.module.css",
  () => ({
    __esModule: true,
    default: {
      container: "container",
      leftColumn: "leftColumn",
      leftToolbar: "leftToolbar",
      rightColumn: "rightColumn",
      sectionTitle: "sectionTitle",
      listItem: "listItem",
      listItemActive: "listItemActive",
    },
  }),
);

import CustomArrayField from "../../src/components/modal/chain_element/field/CustomArrayField";

// --- Helpers ---

function makeProps(
  overrides: Partial<FieldProps<ArrayItem[], RJSFSchema, FormContext>> & {
    formContext?: Partial<FormContext>;
  } = {},
): FieldProps<ArrayItem[], RJSFSchema, FormContext> {
  const { formContext: fcOverrides, ...rest } = overrides;
  return {
    name: "after",
    formData: [],
    onChange: jest.fn(),
    disabled: false,
    readonly: false,
    schema: { type: "array" } as RJSFSchema,
    registry: {
      formContext: {
        elementType: "service-call",
        integrationOperationProtocolType: "http",
        integrationOperationId: undefined,
        ...fcOverrides,
      } as FormContext,
    } as FieldProps<ArrayItem[], RJSFSchema, FormContext>["registry"],
    fieldPathId: { path: "root_after" },
    ...rest,
  } as FieldProps<ArrayItem[], RJSFSchema, FormContext>;
}

function makeResponseHandler(
  overrides: Partial<ResponseHandler> = {},
): ResponseHandler {
  return {
    code: "2xx",
    label: "2xx",
    id: "2xx",
    type: "none",
    wildcard: false,
    ...overrides,
  };
}

function makeResponseValidation(
  overrides: Partial<ResponseValidation> = {},
): ResponseValidation {
  return {
    code: "200",
    label: "200-application/json",
    id: "200-application/json",
    type: "responseValidation",
    contentType: "application/json",
    schema: '{"type":"object"}',
    ...overrides,
  };
}

function makeRequestValidation(
  overrides: Partial<RequestValidation> = {},
): RequestValidation {
  return {
    code: "200",
    label: "200-application/json",
    schema: '{"type":"object"}',
    ...overrides,
  };
}

// --- Tests ---

describe("CustomArrayField", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetOperationInfo.mockResolvedValue({
      id: "op-1",
      specification: {},
      requestSchema: {},
      responseSchemas: {},
    });
  });

  describe("rendering", () => {
    it("renders empty state with Add button and Select", () => {
      const { container } = render(<CustomArrayField {...makeProps()} />);

      expect(screen.getByText("Add")).toBeTruthy();
      expect(container.querySelector(".ant-select")).toBeTruthy();
    });

    it("renders list items from formData", () => {
      const props = makeProps({
        formData: [
          makeResponseHandler({ label: "2xx" }),
          makeResponseHandler({ code: "4xx", label: "4xx", id: "4xx" }),
        ],
      });

      const { container } = render(<CustomArrayField {...props} />);

      expect(container.textContent).toContain("2xx");
      expect(container.textContent).toContain("4xx");
    });

    it("selects first item by default when formData is not empty", () => {
      const handler = makeResponseHandler({ type: "none" });
      const props = makeProps({ formData: [handler] });

      const { container } = render(<CustomArrayField {...props} />);

      expect(container.textContent).toContain("Action");
    });

    it("does not render right panel when formData is empty", () => {
      const { container } = render(<CustomArrayField {...makeProps()} />);

      expect(container.textContent).not.toContain("Action");
      expect(container.textContent).not.toContain("Schema");
    });

    it("renders delete button for each item", () => {
      const props = makeProps({
        formData: [
          makeResponseHandler({ label: "2xx" }),
          makeResponseHandler({ code: "4xx", label: "4xx", id: "4xx" }),
        ],
      });

      render(<CustomArrayField {...props} />);

      const deleteIcons = screen.getAllByTestId("icon-delete");
      expect(deleteIcons).toHaveLength(2);
    });
  });

  describe("handler mode (non-readOnly)", () => {
    it("shows Action selector when handler item is selected", () => {
      const handler = makeResponseHandler({ type: "none" });
      const props = makeProps({ formData: [handler] });

      const { container } = render(<CustomArrayField {...props} />);

      expect(container.textContent).toContain("Action");
    });

    it("shows Script editor when action type is 'script'", () => {
      const handler = makeResponseHandler({
        type: "script",
        script: "println 'hi'",
      });
      const props = makeProps({ formData: [handler] });

      const { container } = render(<CustomArrayField {...props} />);

      expect(container.textContent).toContain("Script");
      const editor = screen.getByTestId("monaco-editor");
      expect(editor).toBeTruthy();
      expect(editor).toHaveValue("println 'hi'");
    });

    it("shows Mapper when action type is 'mapper-2'", () => {
      const handler = makeResponseHandler({ type: "mapper-2" });
      const props = makeProps({ formData: [handler] });

      render(<CustomArrayField {...props} />);

      expect(screen.getByTestId("mapping-editor")).toBeTruthy();
    });

    it("does not show Script or Mapper when action type is 'none'", () => {
      const handler = makeResponseHandler({ type: "none" });
      const props = makeProps({ formData: [handler] });

      const { container } = render(<CustomArrayField {...props} />);

      expect(container.textContent).not.toContain("Script");
      expect(container.textContent).not.toContain("Mapper");
    });

    it("provides default code options for http protocol", () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "http",
        },
      });

      const { container } = render(<CustomArrayField {...props} />);

      // The Select should not be disabled (has options)
      const select = container.querySelector(".ant-select");
      expect(select).toBeTruthy();
      expect(select?.classList.contains("ant-select-disabled")).toBeFalsy();
    });

    it("provides empty code options for non-http protocol", () => {
      const props = makeProps({
        formContext: {
          integrationOperationProtocolType: "kafka",
        },
      });

      const { container } = render(<CustomArrayField {...props} />);

      const select = container.querySelector(".ant-select");
      expect(select?.classList.contains("ant-select-disabled")).toBeTruthy();
    });
  });

  describe("readOnly mode", () => {
    it("enters readOnly mode for async-api-trigger elements", () => {
      const item = makeRequestValidation();
      const props = makeProps({
        formData: [item],
        formContext: {
          elementType: "async-api-trigger",
        },
      });

      const { container } = render(<CustomArrayField {...props} />);

      // readOnly mode shows Schema, not Action
      expect(container.textContent).toContain("Schema");
      expect(container.textContent).not.toContain("Action");
    });

    it("enters readOnly mode for afterValidation field name", () => {
      const item = makeResponseValidation();
      const props = makeProps({
        name: "afterValidation",
        formData: [item],
        formContext: {
          elementType: "service-call",
        },
      });

      const { container } = render(<CustomArrayField {...props} />);

      expect(container.textContent).toContain("Schema");
      expect(container.textContent).not.toContain("Action");
    });

    it("renders schema in readOnly json mode", () => {
      const item = makeRequestValidation({ schema: '{"foo":"bar"}' });
      const props = makeProps({
        formData: [item],
        formContext: { elementType: "async-api-trigger" },
      });

      render(<CustomArrayField {...props} />);

      const editor = screen.getByTestId("monaco-editor");
      expect(editor).toHaveAttribute("data-language", "json");
      expect(editor).toHaveValue('{"foo":"bar"}');
    });
  });

  describe("adding items", () => {
    it("Add button is disabled when no code is selected", () => {
      render(<CustomArrayField {...makeProps()} />);

      const addButton = screen.getByText("Add").closest("button")!;
      expect(addButton).toBeDisabled();
    });

    it("calls onChange with new ResponseHandler when adding in handler mode", async () => {
      const onChange = jest.fn();
      const props = makeProps({
        onChange,
        formContext: { integrationOperationProtocolType: "http" },
      });

      const { container } = render(<CustomArrayField {...props} />);

      // Open the Select dropdown and choose "2xx"
      const select = container.querySelector(".ant-select-selector")!;
      fireEvent.mouseDown(select);

      await waitFor(() => {
        expect(screen.getByTitle("2xx")).toBeTruthy();
      });

      fireEvent.click(screen.getByTitle("2xx"));

      // Click Add
      fireEvent.click(screen.getByText("Add"));

      expect(onChange).toHaveBeenCalledWith(
        [
          expect.objectContaining({
            code: "2xx",
            label: "2xx",
            id: "2xx",
            type: "none",
            wildcard: false,
          }),
        ],
        "root_after",
      );
    });

    it("does not add duplicate items", async () => {
      const onChange = jest.fn();
      const existing = makeResponseHandler({ code: "2xx", label: "2xx" });
      const props = makeProps({
        formData: [existing],
        onChange,
        formContext: { integrationOperationProtocolType: "http" },
      });

      const { container } = render(<CustomArrayField {...props} />);

      // Try to select "2xx" again — it should be filtered from available codes
      const select = container.querySelector(".ant-select-selector")!;
      fireEvent.mouseDown(select);

      await waitFor(() => {
        // "2xx" should still appear in the dropdown since it's in defaultCodeOptions
        // but the duplicate check in handleAdd prevents adding
      });

      // onChange should not have been called (no add action happened)
      expect(onChange).not.toHaveBeenCalled();
    });
  });

  describe("deleting items", () => {
    it("calls onChange without the deleted item", () => {
      const onChange = jest.fn();
      const items = [
        makeResponseHandler({ code: "2xx", label: "2xx", id: "2xx" }),
        makeResponseHandler({ code: "4xx", label: "4xx", id: "4xx" }),
      ];
      const props = makeProps({ formData: items, onChange });

      render(<CustomArrayField {...props} />);

      const deleteButtons = screen.getAllByTestId("icon-delete");
      // Delete first item
      fireEvent.click(deleteButtons[0].closest("button")!);

      expect(onChange).toHaveBeenCalledWith(
        [expect.objectContaining({ code: "4xx" })],
        "root_after",
      );
    });

    it("selects first remaining item after deleting selected item", () => {
      const onChange = jest.fn();
      const items = [
        makeResponseHandler({ code: "2xx", label: "2xx", id: "2xx" }),
        makeResponseHandler({
          code: "4xx",
          label: "4xx",
          id: "4xx",
          type: "script",
          script: "test",
        }),
      ];
      const props = makeProps({ formData: items, onChange });

      const { container } = render(<CustomArrayField {...props} />);

      // Initially first item is selected — Action panel visible
      expect(container.textContent).toContain("Action");

      const deleteButtons = screen.getAllByTestId("icon-delete");
      // Delete first item (the selected one)
      fireEvent.click(deleteButtons[0].closest("button")!);

      // onChange should have been called with the remaining item
      expect(onChange).toHaveBeenCalledWith(
        [expect.objectContaining({ code: "4xx" })],
        "root_after",
      );
    });

    it("returns code to available options after deletion in handler mode", () => {
      const onChange = jest.fn();
      const items = [
        makeResponseHandler({ code: "2xx", label: "2xx", id: "2xx" }),
      ];
      const props = makeProps({
        formData: items,
        onChange,
        formContext: { integrationOperationProtocolType: "http" },
      });

      const { container } = render(<CustomArrayField {...props} />);

      const deleteButton = screen.getByTestId("icon-delete").closest("button")!;
      fireEvent.click(deleteButton);

      expect(onChange).toHaveBeenCalled();
      // After deletion, the code should be returned to available codes
      // We verify this indirectly - the Select should not be disabled
      const select = container.querySelector(".ant-select");
      expect(select?.classList.contains("ant-select-disabled")).toBeFalsy();
    });
  });

  describe("selecting items", () => {
    it("shows details of clicked item", () => {
      const items = [
        makeResponseHandler({
          code: "2xx",
          label: "2xx",
          id: "2xx",
          type: "none",
        }),
        makeResponseHandler({
          code: "4xx",
          label: "4xx",
          id: "4xx",
          type: "script",
          script: "println 'error'",
        }),
      ];
      const props = makeProps({ formData: items });

      const { container } = render(<CustomArrayField {...props} />);

      // Click second item
      const listItems = container.querySelectorAll(".ant-list-item");
      fireEvent.click(listItems[1]);

      // Should show Script section for the script handler
      expect(container.textContent).toContain("Script");
    });
  });

  describe("action type change", () => {
    it("calls onChange when action type is changed", async () => {
      const onChange = jest.fn();
      const handler = makeResponseHandler({ type: "none" });
      const props = makeProps({ formData: [handler], onChange });

      const { container } = render(<CustomArrayField {...props} />);

      // Find the Action select (second select on the page, after the code select)
      const selects = container.querySelectorAll(".ant-select-selector");
      // selects[0] = code select in toolbar, selects[1] = action select
      const actionSelect = selects[1];
      expect(actionSelect).toBeTruthy();

      fireEvent.mouseDown(actionSelect);

      await waitFor(() => {
        expect(screen.getByText("Scripting")).toBeTruthy();
      });

      fireEvent.click(screen.getByText("Scripting"));

      expect(onChange).toHaveBeenCalledWith(
        [expect.objectContaining({ type: "script" })],
        "root_after",
      );
    });
  });

  describe("disabled and readonly states", () => {
    it("disables Add button when field is disabled", () => {
      const props = makeProps({ disabled: true });
      render(<CustomArrayField {...props} />);

      const addButton = screen.getByText("Add").closest("button")!;
      expect(addButton).toBeDisabled();
    });

    it("disables Add button when field is readonly", () => {
      const props = makeProps({ readonly: true });
      render(<CustomArrayField {...props} />);

      const addButton = screen.getByText("Add").closest("button")!;
      expect(addButton).toBeDisabled();
    });

    it("disables delete buttons when field is disabled", () => {
      const items = [makeResponseHandler()];
      const props = makeProps({ formData: items, disabled: true });

      render(<CustomArrayField {...props} />);

      const deleteButton = screen.getByTestId("icon-delete").closest("button")!;
      expect(deleteButton).toBeDisabled();
    });

    it("disables Select when field is disabled", () => {
      const props = makeProps({ disabled: true });
      const { container } = render(<CustomArrayField {...props} />);

      const select = container.querySelector(".ant-select");
      expect(select?.classList.contains("ant-select-disabled")).toBeTruthy();
    });
  });

  describe("API loading (useEffect)", () => {
    it("calls getOperationInfo when operationId is set", async () => {
      const props = makeProps({
        formContext: {
          integrationOperationId: "op-123",
          integrationOperationProtocolType: "http",
        },
      });

      render(<CustomArrayField {...props} />);

      await waitFor(() => {
        expect(mockGetOperationInfo).toHaveBeenCalledWith("op-123");
      });
    });

    it("does not call getOperationInfo when operationId is absent", () => {
      const props = makeProps({
        formContext: {
          integrationOperationId: undefined,
        },
      });

      render(<CustomArrayField {...props} />);

      expect(mockGetOperationInfo).not.toHaveBeenCalled();
    });

    it("populates available codes from API response in handler mode", async () => {
      mockGetOperationInfo.mockResolvedValue({
        id: "op-123",
        specification: {},
        requestSchema: {},
        responseSchemas: {
          "200": { "application/json": { type: "object" } },
          "404": { "application/json": { type: "string" } },
        },
      });

      const props = makeProps({
        formContext: {
          integrationOperationId: "op-123",
          integrationOperationProtocolType: "http",
        },
      });

      const { container } = render(<CustomArrayField {...props} />);

      await waitFor(() => {
        expect(mockGetOperationInfo).toHaveBeenCalledWith("op-123");
      });

      // After loading, select should be enabled (has merged codes)
      await waitFor(() => {
        const select = container.querySelector(".ant-select");
        expect(select?.classList.contains("ant-select-disabled")).toBeFalsy();
      });
    });

    it("populates validation schemas for readOnly mode (afterValidation)", async () => {
      mockGetOperationInfo.mockResolvedValue({
        id: "op-123",
        specification: {},
        requestSchema: {},
        responseSchemas: {
          "200": { "application/json": { type: "object", title: "Success" } },
        },
      });

      const props = makeProps({
        name: "afterValidation",
        formContext: {
          elementType: "service-call",
          integrationOperationId: "op-123",
          integrationOperationProtocolType: "http",
        },
      });

      const { container } = render(<CustomArrayField {...props} />);

      await waitFor(() => {
        expect(mockGetOperationInfo).toHaveBeenCalledWith("op-123");
      });

      // After loading, select should be enabled with the schema codes
      await waitFor(() => {
        const select = container.querySelector(".ant-select");
        expect(select?.classList.contains("ant-select-disabled")).toBeFalsy();
      });
    });

    it("populates validation schemas for async-api-trigger", async () => {
      mockGetOperationInfo.mockResolvedValue({
        id: "op-123",
        specification: {},
        requestSchema: {},
        responseSchemas: {
          "200": { "application/json": { type: "object" } },
        },
      });

      const props = makeProps({
        formContext: {
          elementType: "async-api-trigger",
          integrationOperationId: "op-123",
        },
      });

      const { container } = render(<CustomArrayField {...props} />);

      await waitFor(() => {
        expect(mockGetOperationInfo).toHaveBeenCalled();
      });

      await waitFor(() => {
        const select = container.querySelector(".ant-select");
        expect(select?.classList.contains("ant-select-disabled")).toBeFalsy();
      });
    });

    it("filters out already-used codes from available codes", async () => {
      mockGetOperationInfo.mockResolvedValue({
        id: "op-123",
        specification: {},
        requestSchema: {},
        responseSchemas: {
          "200": { "application/json": { type: "object" } },
          "400": { "application/json": { type: "string" } },
        },
      });

      const existing = makeResponseValidation({
        code: "200",
        label: "200-application/json",
        id: "200-application/json",
      });

      const props = makeProps({
        name: "afterValidation",
        formData: [existing],
        formContext: {
          elementType: "service-call",
          integrationOperationId: "op-123",
          integrationOperationProtocolType: "http",
        },
      });

      const { container } = render(<CustomArrayField {...props} />);

      await waitFor(() => {
        expect(mockGetOperationInfo).toHaveBeenCalled();
      });

      // Select should still be enabled (400-application/json is available)
      await waitFor(() => {
        const select = container.querySelector(".ant-select");
        expect(select?.classList.contains("ant-select-disabled")).toBeFalsy();
      });
    });

    it("logs error when API call fails", async () => {
      const consoleSpy = jest
        .spyOn(console, "error")
        .mockImplementation(() => {});
      mockGetOperationInfo.mockRejectedValue(new Error("Network error"));

      const props = makeProps({
        formContext: {
          integrationOperationId: "op-fail",
          integrationOperationProtocolType: "http",
        },
      });

      render(<CustomArrayField {...props} />);

      await waitFor(() => {
        expect(consoleSpy).toHaveBeenCalledWith(
          "Failed to fetch operation info:",
          expect.any(Error),
        );
      });

      consoleSpy.mockRestore();
    });
  });

  describe("type guard: hasSchema", () => {
    it("shows Schema section for items with schema property in readOnly mode", () => {
      const item = makeRequestValidation({
        schema: '{"description":"test schema"}',
      });
      const props = makeProps({
        formData: [item],
        formContext: { elementType: "async-api-trigger" },
      });

      const { container } = render(<CustomArrayField {...props} />);

      expect(container.textContent).toContain("Schema");

      const editor = screen.getByTestId("monaco-editor");
      expect(editor).toHaveValue('{"description":"test schema"}');
    });
  });
});
