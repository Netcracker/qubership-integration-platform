import {
  ProtectedMenuItem,
  protectMenuItems,
  squashSubsequentDividers,
} from "../../src/permissions/ProtectedDropdown";

describe("protectMenuItems", () => {
  it("should filter out or disable items that are not permitted with respect of onDenied strategy", () => {
    const items: ProtectedMenuItem[] = [
      { key: "a" },
      { key: "b", require: { chain: ["update"] } },
      { key: "c", require: { chain: ["read"] } },
      { key: "d", onDenied: "disable", require: { chain: ["update"] } },
    ];
    expect(protectMenuItems(items, { chain: ["read"] }, "hide")).toEqual([
      items[0],
      items[2],
      { disabled: true, ...items[3] },
    ]);
  });
});

describe("squashSubsequentDividers", () => {
  it("should squash subsequent dividers", () => {
    const items: ProtectedMenuItem[] = [
      { type: "divider" },
      { type: "divider" },
      { key: "b" },
      { type: "divider" },
      { type: "divider" },
      { key: "c" },
      { type: "divider" },
    ];
    expect(squashSubsequentDividers(items)).toEqual([
      { key: "b" },
      { type: "divider" },
      { key: "c" },
    ]);
  });
});
