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
} as IntegrationSystem;

const context: ContextSystem = {
  id: "c1",
  name: "Ctx",
  type: IntegrationSystemType.CONTEXT,
  description: "",
  labels: [],
} as ContextSystem;

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

  it("includes addSpecificationGroup for integration system when callback set", () => {
    const onAdd = jest.fn();
    const fn = getServiceActions({
      onEdit: noop,
      onDelete: noop,
      onExpandAll: noop,
      onCollapseAll: noop,
      isRootEntity: isRoot,
      isExpandAvailable: noExpand,
      onAddSpecificationGroup: onAdd,
    });
    const keys = fn(integration).map((a) => a.key);
    expect(keys).toContain("addSpecificationGroup");
    expect(keys.indexOf("addSpecificationGroup")).toBeGreaterThan(
      keys.indexOf("delete"),
    );
  });

  it("omits addSpecificationGroup for context service", () => {
    const fn = getServiceActions({
      onEdit: noop,
      onDelete: noop,
      onExpandAll: noop,
      onCollapseAll: noop,
      isRootEntity: isRoot,
      isExpandAvailable: noExpand,
      onAddSpecificationGroup: jest.fn(),
    });
    expect(fn(context).map((a) => a.key)).not.toContain(
      "addSpecificationGroup",
    );
  });

  it("omits addSpecificationGroup when callback absent", () => {
    const fn = getServiceActions({
      onEdit: noop,
      onDelete: noop,
      onExpandAll: noop,
      onCollapseAll: noop,
      isRootEntity: isRoot,
      isExpandAvailable: noExpand,
    });
    expect(fn(integration).map((a) => a.key)).not.toContain(
      "addSpecificationGroup",
    );
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
