/**
 * @jest-environment jsdom
 */

import { describe, it, expect } from "@jest/globals";
import { render } from "@testing-library/react";
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

// Expose what the field hands to the select: the text antd filters on, and the
// property it is told to filter by.
jest.mock(
  "../../../../../../src/components/modal/chain_element/field/select/SelectAndNavigateField",
  () => ({
    SelectAndNavigateField: (props: {
      selectOptions?: { value: string; labelString: string }[];
      selectOptionFilterProp?: string;
    }) => (
      <ul data-testid="select" data-filter-prop={props.selectOptionFilterProp}>
        {props.selectOptions?.map((option) => (
          <li key={option.value} data-testid="option">
            {option.labelString}
          </li>
        ))}
      </ul>
    ),
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

// Triggers keep their default name, so the chain name is the only thing that
// tells these two apart.
const triggerElements = [
  { id: "t-1", name: "Chain Trigger", chainId: "c-1", chainName: "Payments" },
  { id: "t-2", name: "Chain Trigger", chainId: "c-2", chainName: "Billing" },
  { id: "t-3", name: "Intake trigger", chainId: "c-3", chainName: "Orders" },
];

// ─── Tests ─────────────────────────────────────────────────────────────────

describe("ChainTriggerSelectField", () => {
  it("should search options by chain name as well as by trigger name", async () => {
    mockGetElementsByType.mockResolvedValue(triggerElements);

    const { findAllByTestId, getByTestId } = render(
      <ChainTriggerSelectField {...props} />,
    );

    const options = await findAllByTestId("option");
    const searchable = options.map((li) => li.textContent);

    expect(searchable).toEqual([
      "Payments Chain Trigger",
      "Billing Chain Trigger",
      "Orders Intake trigger",
    ]);
    // The searched text only reaches antd through this property.
    expect(getByTestId("select")).toHaveAttribute(
      "data-filter-prop",
      "labelString",
    );
  });
});
