/**
 * @jest-environment jsdom
 */

import { describe, it, expect, beforeEach } from "@jest/globals";
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

// Render the options the field builds, in the order it builds them, so the
// test reads the same sequence a user sees in the open dropdown.
jest.mock(
  "../../../../../../src/components/modal/chain_element/field/select/SelectAndNavigateField",
  () => ({
    SelectAndNavigateField: (props: {
      selectOptions?: {
        value: string;
        labelString: string;
        label: React.ReactNode;
      }[];
    }) => (
      <ul>
        {props.selectOptions?.map((option) => (
          <li key={option.value} data-testid="option" data-value={option.value}>
            {option.label}
          </li>
        ))}
      </ul>
    ),
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
});
