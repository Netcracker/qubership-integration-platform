/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import { getServiceActions } from "../../../src/components/services/serviceRowActions";
import {
  IntegrationSystemType,
  type IntegrationSystem,
  type ContextSystem,
} from "../../../src/api/apiTypes";

const integration: IntegrationSystem = {
  id: "s1",
  name: "Svc",
  type: IntegrationSystemType.EXTERNAL,
  description: "",
  labels: [],
} as unknown as IntegrationSystem;

const context: ContextSystem = {
  id: "c1",
  name: "Ctx",
  type: IntegrationSystemType.CONTEXT,
  description: "",
  labels: [],
};

describe("getServiceActions", () => {
  const noop = () => {};
  const isRoot = () => true;
  const noExpand = () => false;

  it("returns empty when not root entity", () => {
    const fn = getServiceActions({
      onEdit: noop,
      onDelete: noop,
      onExpandAll: noop,
      onCollapseAll: noop,
      isRootEntity: () => false,
      isExpandAvailable: noExpand,
    });
    expect(fn(integration)).toEqual([]);
  });

  it("includes addApiGroup for integration system when callback set", () => {
    const onAdd = jest.fn();
    const fn = getServiceActions({
      onEdit: noop,
      onDelete: noop,
      onExpandAll: noop,
      onCollapseAll: noop,
      isRootEntity: isRoot,
      isExpandAvailable: noExpand,
      onAddApiGroup: onAdd,
    });
    const keys = fn(integration).map((a) => a.key);
    expect(keys).toContain("addApiGroup");
    expect(keys.indexOf("addApiGroup")).toBeGreaterThan(keys.indexOf("delete"));
  });

  it("omits addApiGroup for context service", () => {
    const fn = getServiceActions({
      onEdit: noop,
      onDelete: noop,
      onExpandAll: noop,
      onCollapseAll: noop,
      isRootEntity: isRoot,
      isExpandAvailable: noExpand,
      onAddApiGroup: jest.fn(),
    });
    expect(fn(context).map((a) => a.key)).not.toContain("addApiGroup");
  });

  it("omits addApiGroup when callback absent", () => {
    const fn = getServiceActions({
      onEdit: noop,
      onDelete: noop,
      onExpandAll: noop,
      onCollapseAll: noop,
      isRootEntity: isRoot,
      isExpandAvailable: noExpand,
    });
    expect(fn(integration).map((a) => a.key)).not.toContain("addApiGroup");
  });

  it("appends expand and export actions when enabled", () => {
    const fn = getServiceActions({
      onEdit: noop,
      onDelete: noop,
      onExpandAll: noop,
      onCollapseAll: noop,
      isRootEntity: isRoot,
      isExpandAvailable: () => true,
      onExportSelected: jest.fn(),
    });
    const keys = fn(integration).map((a) => a.key);
    expect(keys).toEqual(
      expect.arrayContaining([
        "edit",
        "delete",
        "expandAll",
        "collapseAll",
        "export",
      ]),
    );
  });
});
