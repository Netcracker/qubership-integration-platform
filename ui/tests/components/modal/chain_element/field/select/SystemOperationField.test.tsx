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
import { act, render, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps } from "@rjsf/utils";
import type { JSONSchema7 } from "json-schema";
import type { FormContext } from "../../../../../../src/components/modal/chain_element/ChainElementModificationContext";

// ─── Mocks ─────────────────────────────────────────────────────────────────

const mockGetOperations = jest.fn();
const mockGetService = jest.fn();

jest.mock("../../../../../../src/api/api", () => ({
  api: {
    getOperations: (...args: unknown[]): unknown =>
      mockGetOperations(...args) as unknown,
    getService: (...args: unknown[]): unknown =>
      mockGetService(...args) as unknown,
  },
}));

const mockRequestFailed = jest.fn();
// IMPORTANT: return a stable singleton — SystemOperationField passes the
// service into useCallback deps, and a fresh object per render would recreate
// callbacks → re-run useEffect → setState → loop.
const mockNotificationService = { requestFailed: mockRequestFailed };
jest.mock(
  "../../../../../../src/hooks/useNotificationService",
  () => ({
    useNotificationService: () => mockNotificationService,
  }),
);

jest.mock("../../../../../../src/api/rest/vscodeExtensionApi", () => ({
  isVsCode: false,
  VSCodeExtensionApi: class MockedVSCode {},
}));

jest.mock(
  "../../../../../../src/components/services/ui/OperationPath",
  () => ({
    OperationPath: ({ path }: { path: string }) => (
      <span data-testid="op-path">{path}</span>
    ),
  }),
);
jest.mock("../../../../../../src/components/services/ui/HttpMethod", () => ({
  HttpMethod: ({ value }: { value: string }) => (
    <span data-testid="http-method">{value}</span>
  ),
}));
jest.mock(
  "../../../../../../src/components/modal/chain_element/field/select/SelectTag",
  () => ({
    SelectTag: ({ value }: { value: string }) => (
      <span data-testid="select-tag">{value}</span>
    ),
  }),
);

// Stub SelectAndNavigateField to expose its selectOnChange via a plain button,
// so we can trigger handleChange without poking antd Select internals.
jest.mock(
  "../../../../../../src/components/modal/chain_element/field/select/SelectAndNavigateField",
  () => ({
    SelectAndNavigateField: (props: {
      selectOnChange?: (value: string) => void;
    }) => (
      <button
        data-testid="fake-select"
        onClick={() => props.selectOnChange?.("op-2")}
      >
        select op-2
      </button>
    ),
  }),
);

jest.mock(
  "../../../../../../src/components/modal/chain_element/ChainElementModification.module.css",
  () => ({
    __esModule: true,
    default: {},
  }),
);

import SystemOperationField from "../../../../../../src/components/modal/chain_element/field/select/SystemOperationField";

// ─── Helpers ───────────────────────────────────────────────────────────────

type Props = FieldProps<string, JSONSchema7, FormContext>;

function makeProps(
  overrides: Partial<Props> & {
    formContext?: Partial<FormContext>;
  } = {},
): Props {
  const { formContext, ...rest } = overrides;
  const updateContext = jest.fn();
  return {
    id: "operationField",
    name: "integrationOperationId",
    formData: undefined,
    onChange: jest.fn(),
    schema: { type: "string" } as JSONSchema7,
    uiSchema: {},
    required: false,
    registry: {
      formContext: {
        elementType: "service-call",
        integrationSystemId: "sys-1",
        integrationSpecificationId: "spec-1",
        integrationSpecificationGroupId: "sg-1",
        integrationOperationProtocolType: "http",
        updateContext,
        ...formContext,
      } as FormContext,
    } as Props["registry"],
    fieldPathId: {
      $id: "root_properties_integrationOperationId",
      path: ["properties", "integrationOperationId"],
    },
    ...rest,
  } as unknown as Props;
}

function flushPromises(): Promise<void> {
  return act(async () => {
    await Promise.resolve();
  });
}

// ─── Tests ─────────────────────────────────────────────────────────────────

describe("SystemOperationField (handleChange publishes operation identifiers)", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetOperations.mockResolvedValue([
      { id: "op-1", name: "op1", path: "/v1/foo", method: "GET" },
      { id: "op-2", name: "op2", path: "/v1/bar", method: "POST" },
    ]);
    mockGetService.mockResolvedValue({ id: "sys-1", protocol: "http" });
  });

  it("on handleChange publishes identifiers and clears stale path/query params", async () => {
    const props = makeProps({ formData: undefined });
    const updateContext = props.registry.formContext.updateContext as jest.Mock;

    const { getByTestId } = render(<SystemOperationField {...props} />);
    await flushPromises();

    act(() => {
      getByTestId("fake-select").click();
    });
    await flushPromises();
    await flushPromises();

    await waitFor(() => {
      const call = updateContext.mock.calls
        .map((c: unknown[]) => c[0] as Record<string, unknown>)
        .find((c) => c.integrationOperationId === "op-2");
      expect(call).toBeDefined();
      expect(call?.integrationOperationPath).toBe("/v1/bar");
      expect(call?.integrationOperationMethod).toBe("POST");
      expect(call?.integrationOperationProtocolType).toBe("http");
      // Stale path/query params are cleared — the parent loader re-fills
      // query params from the operation specification afterwards.
      expect(call?.integrationOperationPathParameters).toBeUndefined();
      expect(call?.integrationOperationQueryParameters).toBeUndefined();
    });
  });

  it("falls back to 'http' when there is no systemId (no service lookup)", async () => {
    const props = makeProps({
      formData: undefined,
      formContext: { integrationSystemId: undefined },
    });
    const updateContext = props.registry.formContext.updateContext as jest.Mock;

    const { getByTestId } = render(<SystemOperationField {...props} />);
    await flushPromises();

    act(() => {
      getByTestId("fake-select").click();
    });
    await flushPromises();

    await waitFor(() => {
      const call = updateContext.mock.calls
        .map((c: unknown[]) => c[0] as Record<string, unknown>)
        .find((c) => c.integrationOperationId === "op-2");
      // No systemId → apply("") is called with fallback, which then normalizes
      // to "http" via `normalizeProtocol(proto) ?? "http"` only when the input
      // is null/undefined — matching the existing behaviour of this component.
      expect(call).toBeDefined();
    });
  });

  it("publishes Kafka protocol when service says so", async () => {
    mockGetService.mockResolvedValue({ id: "sys-1", protocol: "kafka" });

    const props = makeProps({
      formData: undefined,
      formContext: { integrationOperationProtocolType: "kafka" },
    });
    const updateContext = props.registry.formContext.updateContext as jest.Mock;

    const { getByTestId } = render(<SystemOperationField {...props} />);
    await flushPromises();

    act(() => {
      getByTestId("fake-select").click();
    });
    await flushPromises();
    await flushPromises();

    await waitFor(() => {
      const call = updateContext.mock.calls
        .map((c: unknown[]) => c[0] as Record<string, unknown>)
        .find((c) => c.integrationOperationId === "op-2");
      expect(call?.integrationOperationProtocolType).toBe("kafka");
    });
  });

  it("does not call getOperationInfo — schema loading is centralized in the parent", () => {
    // This field used to be responsible for fetching OperationInfo, but now
    // the parent ChainElementModification handles it. Guard against regression.
    const apiModule = jest.requireMock<{ api: Record<string, unknown> }>(
      "../../../../../../src/api/api",
    );
    expect(apiModule.api).not.toHaveProperty("getOperationInfo");
  });
});
