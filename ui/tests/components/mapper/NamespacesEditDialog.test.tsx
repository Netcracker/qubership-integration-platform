/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import "@testing-library/jest-dom";
import {
  render,
  screen,
  waitFor,
  fireEvent,
  within,
  act,
} from "@testing-library/react";
import type { XmlNamespace } from "../../../src/mapper/model/metadata";

// --- containing-modal close callback (used by Cancel/Save) ---
const mockCloseContainingModal = jest.fn<() => void>();
jest.mock("../../../src/ModalContextProvider", () => ({
  useModalContext: () => ({ closeContainingModal: mockCloseContainingModal }),
}));

// --- context-aware message wrapper (duplicate-alias error) ---
const mockMessage = { error: jest.fn<(content: string) => void>() };
jest.mock("../../../src/misc/antd-app", () => ({ message: mockMessage }));

// --- overridable icons -> stable, queryable test ids ---
jest.mock("../../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

// --- antd (real, but with a lightweight Table) ---
jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

import { NamespacesEditDialog } from "../../../src/components/mapper/NamespacesEditDialog";

const twoNamespaces: XmlNamespace[] = [
  { alias: "a", uri: "u1" },
  { alias: "b", uri: "u2" },
];

function renderDialog(namespaces: XmlNamespace[] = twoNamespaces) {
  const onSubmit = jest.fn<(namespaces: XmlNamespace[]) => void>();
  const utils = render(
    <NamespacesEditDialog namespaces={namespaces} onSubmit={onSubmit} />,
  );
  return { onSubmit, ...utils };
}

// The lightweight Table renders every data row as a <tbody><tr>; the modal has
// no other tbody, so this counts exactly the namespace rows.
const dataRowCount = () => document.querySelectorAll("tbody tr").length;

describe("NamespacesEditDialog", () => {
  it("should render one row per namespace when namespaces are provided", async () => {
    renderDialog();

    expect(await screen.findByText("a")).toBeInTheDocument();
    expect(screen.getByText("b")).toBeInTheDocument();
    expect(screen.getByText("u1")).toBeInTheDocument();
    expect(screen.getByText("u2")).toBeInTheDocument();
    expect(dataRowCount()).toBe(2);
  });

  it("should add one empty row and ignore a second Add rule click while an empty row exists", async () => {
    renderDialog();
    await screen.findByText("a");
    expect(dataRowCount()).toBe(2);

    const addButton = screen.getByRole("button", { name: "Add rule" });
    fireEvent.click(addButton);
    expect(dataRowCount()).toBe(3);

    // data.some((r) => r.alias === "") dedup: no extra empty row is appended.
    fireEvent.click(addButton);
    expect(dataRowCount()).toBe(3);
  });

  it("should remove all rows when Clear rules is clicked", async () => {
    renderDialog();
    await screen.findByText("a");
    expect(dataRowCount()).toBe(2);

    fireEvent.click(screen.getByRole("button", { name: "Clear rules" }));

    expect(dataRowCount()).toBe(0);
    expect(screen.queryByText("a")).not.toBeInTheDocument();
    expect(screen.queryByText("b")).not.toBeInTheDocument();
  });

  it("should remove only the targeted row when its delete button is clicked", async () => {
    renderDialog();
    const rowA = (await screen.findByText("a")).closest("tr");
    if (!rowA) throw new Error("row for alias 'a' not found");

    const deleteButton = within(rowA)
      .getByTestId("icon-delete")
      .closest("button");
    if (!deleteButton) throw new Error("delete button not found in row 'a'");
    fireEvent.click(deleteButton);

    expect(screen.queryByText("a")).not.toBeInTheDocument();
    expect(screen.getByText("b")).toBeInTheDocument();
    expect(dataRowCount()).toBe(1);
  });

  it("should call onSubmit with the current rows and close the modal when Save is clicked", async () => {
    const { onSubmit } = renderDialog();
    await screen.findByText("a");

    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    expect(onSubmit).toHaveBeenCalledTimes(1);
    expect(onSubmit).toHaveBeenCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ alias: "a", uri: "u1" }),
        expect.objectContaining({ alias: "b", uri: "u2" }),
      ]),
    );
    expect(onSubmit.mock.calls[0][0]).toHaveLength(2);
    expect(mockCloseContainingModal).toHaveBeenCalledTimes(1);
  });

  it("should close the modal and not call onSubmit when Cancel is clicked", async () => {
    const { onSubmit } = renderDialog();
    await screen.findByText("a");

    fireEvent.click(screen.getByRole("button", { name: "Cancel" }));

    expect(mockCloseContainingModal).toHaveBeenCalledTimes(1);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("should show an error and keep the row unchanged when a Prefix is edited to a duplicate alias", async () => {
    const { onSubmit } = renderDialog();
    await screen.findByText("b");

    // Activate the Prefix inline editor of row "b" and retype it as "a".
    fireEvent.click(screen.getByText("b"));
    const input = await screen.findByRole("textbox");
    fireEvent.change(input, { target: { value: "a" } });
    fireEvent.keyDown(input, { key: "Enter" });

    await waitFor(() =>
      expect(mockMessage.error).toHaveBeenCalledWith("Already exists: a"),
    );

    // The duplicate was rejected: Save still submits the original aliases,
    // proving updateRecord did not run for row "b".
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(onSubmit).toHaveBeenCalledWith([
      { alias: "a", uri: "u1" },
      { alias: "b", uri: "u2" },
    ]);
  });

  it("should update a row when its URI cell is edited to a new value", async () => {
    const { onSubmit } = renderDialog();
    await screen.findByText("u1");

    fireEvent.click(screen.getByText("u1"));
    const input = await screen.findByRole("textbox");
    fireEvent.change(input, { target: { value: "newuri" } });

    // The Enter submit validates and runs onFinish asynchronously; flush that
    // whole chain inside act() so updateRecord's setState lands before Save
    // reads the current rows.
    await act(async () => {
      fireEvent.keyDown(input, { key: "Enter" });
    });

    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(onSubmit).toHaveBeenCalledWith([
      { alias: "a", uri: "newuri" },
      { alias: "b", uri: "u2" },
    ]);
    expect(mockMessage.error).not.toHaveBeenCalled();
  });

  it("should update the Prefix when it is edited to a new unique alias", async () => {
    const { onSubmit } = renderDialog();
    await screen.findByText("a");

    fireEvent.click(screen.getByText("a"));
    const input = await screen.findByRole("textbox");
    fireEvent.change(input, { target: { value: "c" } });
    await act(async () => {
      fireEvent.keyDown(input, { key: "Enter" });
    });

    // Unique alias -> no error, updateRecord rewrites the row's alias.
    expect(mockMessage.error).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(onSubmit).toHaveBeenCalledWith([
      { alias: "c", uri: "u1" },
      { alias: "b", uri: "u2" },
    ]);
  });
});
