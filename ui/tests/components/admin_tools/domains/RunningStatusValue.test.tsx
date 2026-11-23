/**
 * @jest-environment jsdom
 */

import { describe, it, expect } from "@jest/globals";
import "@testing-library/jest-dom";
import { render } from "@testing-library/react";
import { RunningStatusValue } from "../../../../src/components/admin_tools/domains/RunningStatusValue.tsx";
import { RunningStatus } from "../../../../src/api/apiTypes.ts";

describe("RunningStatusValue", () => {
  it.each([
    [RunningStatus.RUNNING, "Running", "ant-tag-green"],
    [RunningStatus.PENDING, "Pending", "ant-tag-gold"],
    [RunningStatus.FAILED, "Failed", "ant-tag-red"],
  ])(
    "should show the label and color when status is %s",
    (status, text, colorClass) => {
      const { container } = render(<RunningStatusValue status={status} />);
      const tag = container.querySelector(".ant-tag");
      expect(tag).toHaveTextContent(text);
      expect(tag).toHaveClass(colorClass);
    },
  );

  it("should show a neutral tag when status is UNKNOWN", () => {
    const { container } = render(
      <RunningStatusValue status={RunningStatus.UNKNOWN} />,
    );
    const tag = container.querySelector(".ant-tag");
    expect(tag).toHaveTextContent("Unknown");
    expect(tag?.className).not.toMatch(/ant-tag-(green|gold|red)\b/);
  });
});
