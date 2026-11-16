/**
 * @jest-environment jsdom
 */

import { describe, it, expect } from "@jest/globals";
import "@testing-library/jest-dom";
import { render } from "@testing-library/react";
import { PodStatusValue } from "../../../../src/components/admin_tools/domains/PodStatusValue.tsx";

describe("PodStatusValue", () => {
  it.each([
    [true, "Ready", "ant-tag-green"],
    [false, "Not Ready", "ant-tag-red"],
  ])(
    "should show the label and color when ready is %s",
    (ready, text, colorClass) => {
      const { container } = render(<PodStatusValue ready={ready} />);
      const tag = container.querySelector(".ant-tag");
      expect(tag).toHaveTextContent(text);
      expect(tag).toHaveClass(colorClass);
    },
  );
});
