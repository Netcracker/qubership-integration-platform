/**
 * @jest-environment jsdom
 */
import { tableFilterPopupContainer } from "../../../src/components/table/tableFilterPopupContainer";

describe("tableFilterPopupContainer", () => {
  it("should return the closest Ant Design dropdown when the trigger is nested in a table filter popup", () => {
    const dropdown = document.createElement("div");
    dropdown.className = "ant-dropdown";
    const tableFilterDropdown = document.createElement("div");
    tableFilterDropdown.className = "ant-table-filter-dropdown";
    const triggerContainer = document.createElement("div");
    const node = document.createElement("span");

    dropdown.appendChild(tableFilterDropdown);
    tableFilterDropdown.appendChild(triggerContainer);
    triggerContainer.appendChild(node);

    expect(tableFilterPopupContainer(node)).toBe(dropdown);
  });

  it("should fall back to document.body when no Ant Design dropdown is found", () => {
    const container = document.createElement("div");
    const node = document.createElement("span");
    container.appendChild(node);

    expect(tableFilterPopupContainer(node)).toBe(document.body);
  });
});
