/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { WidgetProps } from "@rjsf/utils";
import CopyableTextWidget from "../../../../../src/components/modal/chain_element/widget/CopyableTextWidget";

const mockCopyToClipboard = jest.fn<(text: string) => Promise<void>>();

jest.mock("../../../../../src/misc/clipboard-util.ts", () => ({
  copyToClipboard: (text: string) => mockCopyToClipboard(text),
}));

const makeProps = (overrides: Partial<WidgetProps>): WidgetProps =>
  ({
    id: "root_contextPath",
    name: "contextPath",
    value: "/chains/abc/retry",
    onChange: jest.fn(),
    options: {},
    schema: { type: "string" },
    ...overrides,
  }) as unknown as WidgetProps;

describe("CopyableTextWidget", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockCopyToClipboard.mockResolvedValue(undefined);
  });

  it("should not let a read-only value be retyped", () => {
    render(<CopyableTextWidget {...makeProps({ readonly: true })} />);

    expect(screen.getByDisplayValue("/chains/abc/retry")).toHaveAttribute(
      "readonly",
    );
  });

  it("should put the value on the clipboard", async () => {
    render(<CopyableTextWidget {...makeProps({ readonly: true })} />);

    fireEvent.click(screen.getByRole("button", { name: "Copy to clipboard" }));

    await waitFor(() =>
      expect(mockCopyToClipboard).toHaveBeenCalledWith("/chains/abc/retry"),
    );
  });
});
