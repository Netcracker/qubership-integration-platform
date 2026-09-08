/**
 * @jest-environment jsdom
 */

import { describe, it, expect } from "@jest/globals";
import { render } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps } from "@rjsf/utils";
import type { JSONSchema7 } from "json-schema";
import type { FormContext } from "../../../../../src/components/modal/chain_element/ChainElementModificationContext";

// ─── Mocks ─────────────────────────────────────────────────────────────────

const mockGetElementsByType = jest.fn();

jest.mock("../../../../../src/api/api", () => ({
  api: {
    getElementsByType: (...args: unknown[]): unknown =>
      mockGetElementsByType(...args) as unknown,
  },
}));

// A stable singleton: a fresh object per render would recreate the loader
// callback and re-run the effect.
const mockNotificationService = { requestFailed: jest.fn() };
jest.mock("../../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => mockNotificationService,
}));

// Render the chains the field collects, in the order it collects them, so the
// test reads the same sequence a user sees in the open Used By menu.
jest.mock("../../../../../src/components/services/ui/ChainColumn", () => ({
  ChainColumn: (props: { chains: { id: string; name: string }[] }) => (
    <ul>
      {props.chains.map((chain) => (
        <li key={chain.id} data-testid="used-by" data-id={chain.id}>
          {chain.name}
        </li>
      ))}
    </ul>
  ),
}));

import ChainTriggerUsedByField from "../../../../../src/components/modal/chain_element/field/ChainTriggerUsedByField";

// ─── Helpers ───────────────────────────────────────────────────────────────

type Props = FieldProps<string, JSONSchema7, FormContext>;

const TRIGGER_ID = "trigger-1";

const props = {
  id: "usedByField",
  formData: TRIGGER_ID,
  schema: { type: "string", readOnly: true } as JSONSchema7,
} as unknown as Props;

// Returned by the API in creation order, which is not alphabetical. The last
// one calls a different trigger and must not appear in the list.
const chainCallElements = [
  {
    id: "call-z",
    chainId: "chain-z",
    chainName: "Zulu",
    properties: { elementId: TRIGGER_ID },
  },
  {
    id: "call-a",
    chainId: "chain-a",
    chainName: "alpha",
    properties: { elementId: TRIGGER_ID },
  },
  {
    id: "call-m",
    chainId: "chain-m",
    chainName: "Mike",
    properties: { elementId: TRIGGER_ID },
  },
  {
    id: "call-other",
    chainId: "chain-other",
    chainName: "Bravo",
    properties: { elementId: "trigger-2" },
  },
];

// ─── Tests ─────────────────────────────────────────────────────────────────

describe("ChainTriggerUsedByField", () => {
  it("should order the calling chains by name and exclude chains that call another trigger", async () => {
    mockGetElementsByType.mockResolvedValue(chainCallElements);

    const { findAllByTestId } = render(<ChainTriggerUsedByField {...props} />);

    const entries = await findAllByTestId("used-by");

    expect(
      entries.map((li) => [li.getAttribute("data-id"), li.textContent]),
    ).toEqual([
      ["chain-a", "alpha"],
      ["chain-m", "Mike"],
      ["chain-z", "Zulu"],
    ]);
  });
});
