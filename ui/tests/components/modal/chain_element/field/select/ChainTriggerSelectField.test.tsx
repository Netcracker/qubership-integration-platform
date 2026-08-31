/**
 * @jest-environment jsdom
 */

import { describe, it, expect } from "@jest/globals";
import { render, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps } from "@rjsf/utils";
import type { JSONSchema7 } from "json-schema";
import type { FormContext } from "../../../../../../src/components/modal/chain_element/ChainElementModificationContext";

// ─── Mocks ─────────────────────────────────────────────────────────────────

const mockGetElementsByType = jest.fn();

jest.mock("../../../../../../src/api/api", () => ({
  api: {
    getElementsByType: (...args: unknown[]): unknown =>
      mockGetElementsByType(...args) as unknown,
  },
}));

// A stable singleton: a fresh object per render would recreate the loader
// callback and re-run the effect.
const mockNotificationService = { requestFailed: jest.fn() };
jest.mock("../../../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => mockNotificationService,
}));

type CapturedProps = {
  selectOptions?: { value: string }[];
  selectFilterOption?: (
    input: string,
    option?: { value?: string | number | null },
  ) => boolean;
};

// Capture what the field hands to the select, so the test can run the very
// filter antd would run.
let captured: CapturedProps = {};
jest.mock(
  "../../../../../../src/components/modal/chain_element/field/select/SelectAndNavigateField",
  () => ({
    SelectAndNavigateField: (props: CapturedProps) => {
      captured = props;
      return <div data-testid="select" />;
    },
  }),
);

import ChainTriggerSelectField from "../../../../../../src/components/modal/chain_element/field/select/ChainTriggerSelectField";

// ─── Helpers ───────────────────────────────────────────────────────────────

type Props = FieldProps<string, JSONSchema7, FormContext>;

const props = {
  id: "chainTriggerField",
  formData: undefined,
  onChange: jest.fn(),
  schema: { type: "string", title: "Chain trigger" } as JSONSchema7,
  uiSchema: {},
  required: true,
  fieldPathId: {
    $id: "root_properties_elementId",
    path: ["properties", "elementId"],
  },
} as unknown as Props;

// The first two keep the default trigger name, so only the chain name tells
// them apart.
const triggerElements = [
  { id: "t-1", name: "Chain Trigger", chainId: "c-1", chainName: "Payments" },
  { id: "t-2", name: "Chain Trigger", chainId: "c-2", chainName: "Billing" },
  { id: "t-3", name: "Intake trigger", chainId: "c-3", chainName: "Orders" },
];

async function renderAndSearch(): Promise<(query: string) => string[]> {
  mockGetElementsByType.mockResolvedValue(triggerElements);
  render(<ChainTriggerSelectField {...props} />);
  await waitFor(() => {
    expect(captured.selectOptions).toHaveLength(triggerElements.length);
  });

  return (query: string) =>
    (captured.selectOptions ?? [])
      .filter((option) => captured.selectFilterOption?.(query, option))
      .map((option) => option.value);
}

// ─── Tests ─────────────────────────────────────────────────────────────────

describe("ChainTriggerSelectField", () => {
  it("should match the chain name as well as the trigger name", async () => {
    const search = await renderAndSearch();

    expect(search("payments")).toEqual(["t-1"]);
    expect(search("Intake")).toEqual(["t-3"]);
    expect(search("chain trigger")).toEqual(["t-1", "t-2"]);
  });

  it("should not match a query spanning the chain name and the trigger name", async () => {
    const search = await renderAndSearch();

    expect(search("payments chain")).toEqual([]);
    expect(search("orders intake")).toEqual([]);
  });

  it("should match nothing for an option it cannot resolve", async () => {
    await renderAndSearch();

    expect(captured.selectFilterOption?.("payments", { value: "gone" })).toBe(
      false,
    );
  });
});
