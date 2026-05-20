/**
 * @jest-environment jsdom
 */
import { tableFilterPopupContainer } from "../../../src/components/table/tableFilterPopupContainer";

describe("tableFilterPopupContainer", () => {
  it("returns parent element when present", () => {
    const parent = document.createElement("div");
    const node = document.createElement("span");
    parent.appendChild(node);
    expect(tableFilterPopupContainer(node)).toBe(parent);
  });

  it("falls back to document.body when parentElement is null", () => {
    const node = document.createElement("span");
    expect(tableFilterPopupContainer(node)).toBe(document.body);
  });
});
