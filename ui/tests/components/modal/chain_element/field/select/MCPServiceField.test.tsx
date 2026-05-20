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

globalThis.ResizeObserver = class ResizeObserver {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
};

import React from "react";
import { beforeEach, describe, expect, it } from "@jest/globals";
import { act, render, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps } from "@rjsf/utils";
import type { JSONSchema7 } from "json-schema";
import type { MCPSystem } from "../../../../../../src/api/apiTypes";
import type { FormContext } from "../../../../../../src/components/modal/chain_element/ChainElementModificationContext";
import { MCPServiceField } from "../../../../../../src/components/modal/chain_element/field/select/MCPServiceField";

// ---------------------------------------------------------------------------
// Captured props store — updated on every re-render of the Select stub
// ---------------------------------------------------------------------------

type CapturedSelectProps = {
  mode?: string;
  value: string[] | undefined;
  options:
    | Array<{ labelString: string; label: string; value: string }>
    | undefined;
  onChange: (value: string[]) => void;
  loading: boolean;
  showSearch?: boolean;
  optionFilterProp?: string;
};

let capturedSelectProps: CapturedSelectProps | null = null;

// ---------------------------------------------------------------------------
// Mocks
//
// IMPORTANT: useNotificationService must return a *stable* object reference.
// MCPServiceField uses it as a useCallback dependency; a new object on every
// render causes loadSystems to be recreated every render, which re-triggers
// the useEffect and causes an infinite update loop that blocks act/waitFor.
// ---------------------------------------------------------------------------

const mockGetMcpSystems: jest.MockedFunction<() => Promise<MCPSystem[]>> =
  jest.fn();
const mockUpdateContext = jest.fn();
const mockRequestFailed = jest.fn();
const mockNotificationService = { requestFailed: mockRequestFailed };

jest.mock("../../../../../../src/api/api", () => ({
  api: {
    getMcpSystems: (onlyEnabled: boolean): Promise<MCPSystem[]> =>
      mockGetMcpSystems(onlyEnabled),
  },
}));

jest.mock("../../../../../../src/hooks/useNotificationService", () => ({
  // Arrow function returns the same object on every call — no re-render loop.
  useNotificationService: () => mockNotificationService,
}));

jest.mock("antd", () => ({
  Select: (props: CapturedSelectProps) => {
    capturedSelectProps = props;
    return <div data-testid="antd-select" />;
  },
}));

jest.mock(
  "../../../../../../src/components/modal/chain_element/ChainElementModification.module.css",
  () => ({
    "field-label": "field-label",
    "field-required": "field-required",
  }),
);

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const makeMcpSystem = (id: string, name: string): MCPSystem =>
  ({
    id,
    name,
    description: "",
    identifier: `identifier-${id}`,
    instructions: "",
    labels: [],
  }) as MCPSystem;

type RegistryProp = FieldProps<string[], JSONSchema7, FormContext>["registry"];

function makeProps(
  overrides: Partial<FieldProps<string[], JSONSchema7, FormContext>> = {},
): FieldProps<string[], JSONSchema7, FormContext> {
  return {
    id: "test-field",
    formData: [],
    schema: {},
    required: false,
    uiSchema: {},
    registry: {
      formContext: {
        updateContext: mockUpdateContext,
      },
    } as unknown as RegistryProp,
    errorSchema: {},
    onChange: jest.fn(),
    onBlur: jest.fn(),
    onFocus: jest.fn(),
    ...overrides,
  } as FieldProps<string[], JSONSchema7, FormContext>;
}

// ---------------------------------------------------------------------------
// Component under test (imported after mocks)
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe("MCPServiceField", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    capturedSelectProps = null;
    mockGetMcpSystems.mockResolvedValue([]);
  });

  // -------------------------------------------------------------------------
  // Group 1 — Initial render
  // -------------------------------------------------------------------------

  describe("initial render", () => {
    it("should render without crashing when given minimal valid props", async () => {
      const { getByTestId } = render(<MCPServiceField {...makeProps()} />);
      expect(getByTestId("antd-select")).toBeInTheDocument();
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });

    it("should pass loading as true to Select when data is loading", async () => {
      mockGetMcpSystems.mockReturnValue(new Promise<MCPSystem[]>(() => {}));
      render(<MCPServiceField {...makeProps()} />);
      await waitFor(() => expect(capturedSelectProps?.loading).toBe(true));
    });

    it("should render a label element with htmlFor matching the id prop", async () => {
      const { container } = render(
        <MCPServiceField {...makeProps({ id: "my-field" })} />,
      );
      const label = container.querySelector("label");
      expect(label).toBeInTheDocument();
      expect(label).toHaveAttribute("for", "my-field");
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });
  });

  // -------------------------------------------------------------------------
  // Group 2 — Successful data load
  // -------------------------------------------------------------------------

  describe("successful data load", () => {
    it("should call getMcpSystems with true when component mounts", async () => {
      render(<MCPServiceField {...makeProps()} />);
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalledWith(true));
    });

    it("should pass loading as false to Select when data has loaded", async () => {
      render(<MCPServiceField {...makeProps()} />);
      await waitFor(() => {
        expect(mockGetMcpSystems).toHaveBeenCalledWith(true);
        expect(capturedSelectProps?.loading).toBe(false);
      });
    });

    it("should pass correctly shaped options to Select when systems are returned by API", async () => {
      mockGetMcpSystems.mockResolvedValue([
        makeMcpSystem("sys-1", "System One"),
      ]);
      render(<MCPServiceField {...makeProps()} />);
      await waitFor(() =>
        expect(capturedSelectProps?.options).toEqual([
          { labelString: "System One", label: "System One", value: "sys-1" },
        ]),
      );
    });

    it("should preserve system order in options when systems are returned by API", async () => {
      mockGetMcpSystems.mockResolvedValue([
        makeMcpSystem("sys-1", "Alpha"),
        makeMcpSystem("sys-2", "Beta"),
        makeMcpSystem("sys-3", "Gamma"),
      ]);
      render(<MCPServiceField {...makeProps()} />);
      await waitFor(() => {
        const values = capturedSelectProps?.options?.map((o) => o.value);
        expect(values).toEqual(["sys-1", "sys-2", "sys-3"]);
      });
    });
  });

  // -------------------------------------------------------------------------
  // Group 3 — Failed data load
  // -------------------------------------------------------------------------

  describe("failed data load", () => {
    beforeEach(() => {
      mockGetMcpSystems.mockRejectedValue(new Error("Network error"));
    });

    it('should call requestFailed with "Failed to load MCP services" when API throws', async () => {
      render(<MCPServiceField {...makeProps()} />);
      await waitFor(() =>
        expect(mockRequestFailed).toHaveBeenCalledWith(
          "Failed to load MCP services",
          expect.any(Error),
        ),
      );
    });

    it("should pass empty options to Select when API call fails", async () => {
      render(<MCPServiceField {...makeProps()} />);
      await waitFor(() => {
        expect(mockRequestFailed).toHaveBeenCalled();
        expect(capturedSelectProps?.options).toEqual([]);
      });
    });

    it("should pass loading as false to Select when API call fails", async () => {
      render(<MCPServiceField {...makeProps()} />);
      // isLoading is reset to false in the finally block even after an error.
      await waitFor(() => {
        expect(mockRequestFailed).toHaveBeenCalled();
        expect(capturedSelectProps?.loading).toBe(false);
      });
    });
  });

  // -------------------------------------------------------------------------
  // Group 4 — Title resolution
  // -------------------------------------------------------------------------

  describe("title resolution", () => {
    it("should use uiSchema ui:title as label text when it is provided", async () => {
      const { container } = render(
        <MCPServiceField
          {...makeProps({
            uiSchema: { "ui:title": "UI Schema Title" },
            schema: { title: "Schema Title" },
          })}
        />,
      );
      const label = container.querySelector("label");
      expect(label?.textContent).toContain("UI Schema Title");
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });

    it("should fall back to schema title as label text when uiSchema ui:title is absent", async () => {
      const { container } = render(
        <MCPServiceField
          {...makeProps({
            uiSchema: {},
            schema: { title: "Schema Title" },
          })}
        />,
      );
      const label = container.querySelector("label");
      expect(label?.textContent).toContain("Schema Title");
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });

    it("should render an empty label when neither uiSchema nor schema title is provided", async () => {
      const { container } = render(
        <MCPServiceField
          {...makeProps({
            uiSchema: {},
            schema: {},
          })}
        />,
      );
      const label = container.querySelector("label");
      expect(label?.textContent).toBe("");
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });
  });

  // -------------------------------------------------------------------------
  // Group 5 — Selection / handleChange wiring
  // -------------------------------------------------------------------------

  describe("selection handling", () => {
    it("should call updateContext with mcpServiceIds when systems are selected", async () => {
      render(<MCPServiceField {...makeProps()} />);
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
      act(() => {
        capturedSelectProps?.onChange(["sys-1", "sys-2"]);
      });
      expect(mockUpdateContext).toHaveBeenCalledWith({
        mcpServiceIds: ["sys-1", "sys-2"],
      });
    });

    it("should call updateContext with empty array when all systems are deselected", async () => {
      render(<MCPServiceField {...makeProps({ formData: ["sys-1"] })} />);
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
      act(() => {
        capturedSelectProps?.onChange([]);
      });
      expect(mockUpdateContext).toHaveBeenCalledWith({ mcpServiceIds: [] });
    });
  });

  // -------------------------------------------------------------------------
  // Group 6 — formData as value
  // -------------------------------------------------------------------------

  describe("formData as value", () => {
    it("should pass formData prop as value to Select when formData is set", async () => {
      render(
        <MCPServiceField {...makeProps({ formData: ["pre-existing-id"] })} />,
      );
      expect(capturedSelectProps?.value).toEqual(["pre-existing-id"]);
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });

    it("should pass multiple pre-selected values to Select when formData contains multiple ids", async () => {
      render(
        <MCPServiceField
          {...makeProps({ formData: ["id-1", "id-2", "id-3"] })}
        />,
      );
      expect(capturedSelectProps?.value).toEqual(["id-1", "id-2", "id-3"]);
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });
  });

  // -------------------------------------------------------------------------
  // Group 7 — Null-safety
  // -------------------------------------------------------------------------

  describe("null-safety", () => {
    it("should not throw when registry formContext is undefined when a selection is made", async () => {
      render(
        <MCPServiceField
          {...makeProps({
            registry: {
              formContext: undefined,
            } as unknown as RegistryProp,
          })}
        />,
      );
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
      // Optional chaining on formContext/updateContext must not throw.
      await act(async () => {
        capturedSelectProps?.onChange(["sys-1"]);
        await Promise.resolve();
      });
    });

    it("should not throw when formContext updateContext is undefined when a selection is made", async () => {
      render(
        <MCPServiceField
          {...makeProps({
            registry: {
              formContext: {},
            } as unknown as RegistryProp,
          })}
        />,
      );
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
      // Optional chaining on formContext/updateContext must not throw.
      await act(async () => {
        capturedSelectProps?.onChange(["sys-1"]);
        await Promise.resolve();
      });
    });
  });

  // -------------------------------------------------------------------------
  // Group 8 — required prop
  // -------------------------------------------------------------------------

  describe("required prop", () => {
    it("should render required asterisk indicator when required prop is true", async () => {
      const { container } = render(
        <MCPServiceField {...makeProps({ required: true })} />,
      );
      const requiredIndicator = container.querySelector(".field-required");
      expect(requiredIndicator).toBeInTheDocument();
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });

    it("should not render required asterisk indicator when required prop is false", async () => {
      const { container } = render(
        <MCPServiceField {...makeProps({ required: false })} />,
      );
      const requiredIndicator = container.querySelector(".field-required");
      expect(requiredIndicator).not.toBeInTheDocument();
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });
  });

  // -------------------------------------------------------------------------
  // Group 9 — Multiple selection mode
  // -------------------------------------------------------------------------

  describe("multiple selection mode", () => {
    it("should render Select with multiple mode", async () => {
      render(<MCPServiceField {...makeProps()} />);
      expect(capturedSelectProps?.mode).toBe("multiple");
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });

    it("should render Select with showSearch enabled", async () => {
      render(<MCPServiceField {...makeProps()} />);
      expect(capturedSelectProps?.showSearch).toBe(true);
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });

    it("should render Select with optionFilterProp set to labelString", async () => {
      render(<MCPServiceField {...makeProps()} />);
      expect(capturedSelectProps?.optionFilterProp).toBe("labelString");
      await waitFor(() => expect(mockGetMcpSystems).toHaveBeenCalled());
    });
  });
});
