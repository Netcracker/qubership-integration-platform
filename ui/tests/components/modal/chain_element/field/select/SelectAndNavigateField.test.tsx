/**
 * @jest-environment jsdom
 */

import { describe, it, expect } from "@jest/globals";
import { render } from "@testing-library/react";
import "@testing-library/jest-dom";

// ─── Mocks ─────────────────────────────────────────────────────────────────

type CapturedSelectProps = {
  showSearch?: boolean;
  optionFilterProp?: string;
  filterOption?: (input: string, option?: { value?: string }) => boolean;
};

// Capture what reaches antd: this component is the only thing standing between
// a field's search configuration and the Select that has to honor it.
let captured: CapturedSelectProps = {};
jest.mock("antd", () => {
  const actual = jest.requireActual<Record<string, unknown>>("antd");
  return {
    ...actual,
    Select: (props: CapturedSelectProps) => {
      captured = props;
      return <div data-testid="antd-select" />;
    },
  };
});

jest.mock("../../../../../../src/api/api", () => ({ api: {} }));
jest.mock("../../../../../../src/api/rest/vscodeExtensionApi", () => ({
  isVsCode: false,
  VSCodeExtensionApi: class MockedVSCode {},
}));

import { SelectAndNavigateField } from "../../../../../../src/components/modal/chain_element/field/select/SelectAndNavigateField";

// ─── Helpers ───────────────────────────────────────────────────────────────

const baseProps = {
  title: "Chain trigger",
  selectValue: undefined,
  selectOptions: [{ value: "t-1", label: "Trigger" }],
  selectOnChange: jest.fn(),
  selectDisabled: false,
  buttonTitle: "Go to chain",
  buttonDisabled: true,
  buttonOnClick: "/chains/c-1",
};

// ─── Tests ─────────────────────────────────────────────────────────────────

describe("SelectAndNavigateField", () => {
  it("should turn search on and hand a custom filter to the select", () => {
    const selectFilterOption = jest.fn(() => true);

    render(
      <SelectAndNavigateField
        {...baseProps}
        selectFilterOption={selectFilterOption}
      />,
    );

    expect(captured.showSearch).toBe(true);
    expect(captured.filterOption).toBe(selectFilterOption);
  });

  it("should turn search on and filter by option property when given one", () => {
    render(
      <SelectAndNavigateField
        {...baseProps}
        selectOptionFilterProp="labelString"
      />,
    );

    expect(captured.showSearch).toBe(true);
    expect(captured.optionFilterProp).toBe("labelString");
    expect(captured.filterOption).toBeUndefined();
  });

  it("should leave search off when neither is given", () => {
    render(<SelectAndNavigateField {...baseProps} />);

    expect(captured.showSearch).toBeUndefined();
    expect(captured.filterOption).toBeUndefined();
    expect(captured.optionFilterProp).toBeUndefined();
  });
});
