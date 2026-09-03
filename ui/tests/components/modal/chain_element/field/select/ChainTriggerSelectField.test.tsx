/**
 * @jest-environment jsdom
 */

import { describe, it, expect, beforeEach } from "@jest/globals";
import { render, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps } from "@rjsf/utils";
import type { JSONSchema7 } from "json-schema";
import type { ReactNode } from "react";
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
  selectOptions?: { value: string; label: ReactNode }[];
  selectFilterOption?: (
    input: string,
    option?: { value?: string | number | null },
  ) => boolean;
};

// Capture what the field hands to the select, so the test can run the very
// filter antd would run, and render the options in the order the field builds
// them, so the test reads the same sequence a user sees in the open dropdown.
let captured: CapturedProps = {};
jest.mock(
  "../../../../../../src/components/modal/chain_element/field/select/SelectAndNavigateField",
  () => ({
    SelectAndNavigateField: (props: CapturedProps) => {
      captured = props;
      return (
        <ul>
          {props.selectOptions?.map((option) => (
            <li
              key={option.value}
              data-testid="option"
              data-value={option.value}
            >
              {option.label}
            </li>
          ))}
        </ul>
      );
    },
  }),
);

import ChainTriggerSelectField from "../../../../../../src/components/modal/chain_element/field/select/ChainTriggerSelectField";

// ─── Helpers ───────────────────────────────────────────────────────────────

type Props = FieldProps<string, JSONSchema7, FormContext>;

function makeProps(): Props {
  return {
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
}

// Returned by the API in creation order, which is not alphabetical.
const unorderedElements = [
  { id: "z-1", name: "Chain Trigger", chainId: "c-z", chainName: "Zulu" },
  { id: "a-1", name: "Chain Trigger", chainId: "c-a", chainName: "alpha" },
  { id: "m-1", name: "Chain Trigger", chainId: "c-m", chainName: "Mike" },
  { id: "b-zed", name: "Zed trigger", chainId: "c-b", chainName: "Bravo" },
  { id: "b-able", name: "Able trigger", chainId: "c-b", chainName: "Bravo" },
];

// The first two keep the default trigger name, so only the chain name tells
// them apart.
const triggerElements = [
  { id: "t-1", name: "Chain Trigger", chainId: "c-1", chainName: "Payments" },
  { id: "t-2", name: "Chain Trigger", chainId: "c-2", chainName: "Billing" },
  { id: "t-3", name: "Intake trigger", chainId: "c-3", chainName: "Orders" },
];

async function renderAndSearch(): Promise<(query: string) => string[]> {
  mockGetElementsByType.mockResolvedValue(triggerElements);
  render(<ChainTriggerSelectField {...makeProps()} />);
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
  beforeEach(() => {
    mockGetElementsByType.mockResolvedValue(unorderedElements);
  });

  it("should order options by chain name, then by trigger name", async () => {
    const { findAllByTestId } = render(
      <ChainTriggerSelectField {...makeProps()} />,
    );

    const options = await findAllByTestId("option");

    expect(options.map((li) => li.textContent)).toEqual([
      "alphaChain Trigger",
      "BravoAble trigger",
      "BravoZed trigger",
      "MikeChain Trigger",
      "ZuluChain Trigger",
    ]);
  });

  it("should order option values to match the rendered labels", async () => {
    const { findAllByTestId } = render(
      <ChainTriggerSelectField {...makeProps()} />,
    );

    const options = await findAllByTestId("option");

    expect(options.map((li) => li.getAttribute("data-value"))).toEqual([
      "a-1",
      "b-able",
      "b-zed",
      "m-1",
      "z-1",
    ]);
  });

  it("should match the chain name as well as the trigger name", async () => {
    const search = await renderAndSearch();

    expect(search("payments")).toEqual(["t-1"]);
    expect(search("Intake")).toEqual(["t-3"]);
    // In the order the options are listed: Billing before Payments.
    expect(search("chain trigger")).toEqual(["t-2", "t-1"]);
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
