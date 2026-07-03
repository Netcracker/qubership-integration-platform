/** @jest-environment jsdom */
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";

// Run the confirm dialog's onOk immediately so destructive actions fire.
jest.mock("../../../src/misc/confirm-utils", () => ({
  confirmAndRun: jest.fn(({ onOk }: { onOk?: () => void }) => onOk?.()),
}));

// The component reads the modals context; stub it with a value that stays
// referentially stable, or the changing showModal would loop the useEffect.
jest.mock("../../../src/Modals", () => {
  const value = { showModal: jest.fn(), closeModal: jest.fn() };
  return { useModalsContext: () => value };
});

// ChainPage owns a huge module graph; only its context is used here, so stub it.
jest.mock("../../../src/pages/ChainPage", () => {
  const react = jest.requireActual<typeof import("react")>("react");
  return { ChainContext: react.createContext(undefined) };
});

// IconProvider pulls in hundreds of SVG icon modules; a trivial icon is enough.
jest.mock("../../../src/icons/IconProvider", () => {
  const react = jest.requireActual<typeof import("react")>("react");
  return {
    OverridableIcon: ({ name }: { name?: string }) =>
      react.createElement("span", { "data-icon": name }),
  };
});

// MappingTableView drags in an ESM markdown/yaml chain Jest cannot transform.
// Only the item type guards are needed here, so stub the module with them.
jest.mock("../../../src/components/mapper/MappingTableView", () => {
  const hasType = (obj: unknown, type: string) =>
    typeof obj === "object" &&
    obj !== null &&
    "itemType" in obj &&
    obj.itemType === type;
  return {
    getXmlNamespaces: () => [],
    isConstantItem: (obj: unknown) => hasType(obj, "constant"),
    isAttributeItem: (obj: unknown) => hasType(obj, "attribute"),
    isConstantGroup: (obj: unknown) => hasType(obj, "constant-group"),
    isHeaderGroup: (obj: unknown) => hasType(obj, "header-group"),
    isPropertyGroup: (obj: unknown) => hasType(obj, "property-group"),
    isBodyGroup: (obj: unknown) => hasType(obj, "body-group"),
  };
});

// These dialogs pull in monaco-editor (UMD) and only render inside showModal
// callbacks the tests never trigger, so stub them out to keep the graph light.
jest.mock("../../../src/components/mapper/LoadSchemaDialog", () => ({
  LoadSchemaDialog: () => null,
}));
jest.mock("../../../src/components/mapper/NamespacesEditDialog", () => ({
  NamespacesEditDialog: () => null,
}));

import { confirmAndRun } from "../../../src/misc/confirm-utils";
import { MappingTableItemActionButton } from "../../../src/components/mapper/MappingTableItemActionButton";
import type { MappingTableItem } from "../../../src/components/mapper/MappingTableView";
import { SchemaKind } from "../../../src/mapper/model/model";

const confirmAndRunMock = confirmAndRun as jest.MockedFunction<
  typeof confirmAndRun
>;

const constantGroup = {
  id: "group-1",
  itemType: "constant-group",
  children: [],
} as unknown as MappingTableItem;

const constantItem = {
  id: "constant-1",
  itemType: "constant",
  constant: { id: "constant-1", name: "c1" },
  actions: [],
} as unknown as MappingTableItem;

function openMenu(): void {
  fireEvent.click(screen.getByRole("button"));
}

describe("MappingTableItemActionButton", () => {
  it("should render the dropdown trigger button when given a mapping item", () => {
    render(
      <MappingTableItemActionButton
        elementId="element-1"
        item={constantItem}
        readonly={false}
        enableEdit
        enableXmlNamespaces={false}
        schemaKind={SchemaKind.SOURCE}
      />,
    );

    expect(screen.getByRole("button")).toBeInTheDocument();
  });

  it("should clear the tree through confirmAndRun when the Clear item is clicked", () => {
    const onClear = jest.fn();
    render(
      <MappingTableItemActionButton
        elementId="element-1"
        item={constantGroup}
        readonly={false}
        enableEdit={false}
        enableXmlNamespaces={false}
        schemaKind={SchemaKind.SOURCE}
        onClear={onClear}
      />,
    );

    openMenu();
    fireEvent.click(screen.getByText("Clear"));

    expect(confirmAndRunMock).toHaveBeenCalledWith(
      expect.objectContaining({ title: "Clear tree" }),
    );
    expect(onClear).toHaveBeenCalledTimes(1);
  });

  it("should delete the constant through confirmAndRun when the Delete item is clicked", () => {
    const onDelete = jest.fn();
    render(
      <MappingTableItemActionButton
        elementId="element-1"
        item={constantItem}
        readonly={false}
        enableEdit={false}
        enableXmlNamespaces={false}
        schemaKind={SchemaKind.SOURCE}
        onDelete={onDelete}
      />,
    );

    openMenu();
    fireEvent.click(screen.getByText("Delete"));

    expect(confirmAndRunMock).toHaveBeenCalledWith(
      expect.objectContaining({ title: "Delete constant" }),
    );
    expect(onDelete).toHaveBeenCalledTimes(1);
  });
});
