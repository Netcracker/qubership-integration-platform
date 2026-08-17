/**
 * @jest-environment jsdom
 */

import React from "react";
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  MatcherEntityType,
  MatcherType,
  TestingMatcher,
} from "../../../src/api/apiTypes.ts";
import { MatchersTable } from "../../../src/components/testing/MatchersTable.tsx";
import type { JsonMatcherParametersModalProps } from "../../../src/components/modal/testing/JsonMatcherParametersModal.tsx";
import { querySelectOption } from "../../helpers/antdSelect.ts";

const showModal = jest.fn();

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

jest.mock("../../../src/Modals.tsx", () => ({
  useModalsContext: () => ({ showModal, closeModal: jest.fn() }),
}));

jest.mock("../../../src/components/Script.tsx", () => ({
  Script: () => <div data-testid="script" />,
}));

function matcher(overrides: Partial<TestingMatcher> = {}): TestingMatcher {
  return {
    id: "m1",
    name: "body equals",
    description: "",
    enabled: true,
    type: MatcherType.EQUAL,
    entityType: MatcherEntityType.BODY,
    entityName: null,
    parameters: [{ name: "value", value: "42" }],
    ...overrides,
  };
}

function renderTable(
  props: Partial<React.ComponentProps<typeof MatchersTable>> = {},
) {
  const onChange = jest.fn();
  const utils = render(
    <MatchersTable
      kind="response"
      matchers={[matcher()]}
      onChange={onChange}
      {...props}
    />,
  );
  return { ...utils, onChange };
}

function row(container: HTMLElement, key: string): HTMLElement {
  const element = container.querySelector<HTMLElement>(
    `[data-row-key="${key}"]`,
  );
  if (!element) {
    throw new Error(`row ${key} not rendered`);
  }
  return element;
}

/** Opens an inline editor by clicking the cell's viewer button. */
function activateEditor(cell: HTMLElement): void {
  fireEvent.click(within(cell).getAllByRole("button")[0]);
}

function cellOf(
  container: HTMLElement,
  key: string,
  index: number,
): HTMLElement {
  return row(container, key).querySelectorAll("td")[index];
}

describe("MatchersTable columns", () => {
  test("should render every column", () => {
    renderTable();
    for (const title of [
      "Name",
      "Description",
      "Condition",
      "Entity Type",
      "Entity Name",
      "Parameters",
      "Enabled",
    ]) {
      expect(screen.getByText(title)).toBeInTheDocument();
    }
  });

  test("should render the matcher name, condition label and parameter value", () => {
    renderTable();
    expect(screen.getByText("body equals")).toBeInTheDocument();
    expect(screen.getByText("Equals")).toBeInTheDocument();
    expect(screen.getByText("42")).toBeInTheDocument();
  });

  test("should mark the entity name inapplicable for a body matcher", () => {
    const { container } = renderTable();
    // Selection column shifts the data columns by one.
    expect(cellOf(container, "m1", 5)).toHaveTextContent("Not applicable");
  });

  test("should expand and collapse the description", () => {
    renderTable({
      matchers: [matcher({ description: "a long explanation" })],
    });
    const toggle = screen.getByLabelText("Expand description");
    fireEvent.click(toggle);
    expect(screen.getByLabelText("Collapse description")).toBeInTheDocument();
  });
});

describe("MatchersTable toolbar", () => {
  test("should append a new matcher scoped to the owner kind", () => {
    const { onChange } = renderTable({ matchers: [] });
    fireEvent.click(screen.getByLabelText("Add matcher"));

    expect(onChange).toHaveBeenCalledTimes(1);
    const added = (onChange.mock.calls[0][0] as TestingMatcher[])[0];
    expect(added).toEqual(
      expect.objectContaining({
        name: "",
        enabled: true,
        entityType: MatcherEntityType.BODY,
        parameters: [],
      }),
    );
    expect(added.id).toBeTruthy();
  });

  test("should filter rows by the local search term", () => {
    const { container } = renderTable({
      matchers: [matcher(), matcher({ id: "m2", name: "header exists" })],
    });

    fireEvent.change(screen.getByPlaceholderText("Search matchers..."), {
      target: { value: "header" },
    });

    expect(container.querySelector('[data-row-key="m1"]')).toBeNull();
    expect(container.querySelector('[data-row-key="m2"]')).not.toBeNull();
  });

  test("should disable the bulk actions until a row is selected", () => {
    const { container } = renderTable();

    expect(screen.getByLabelText("Delete selected matchers")).toBeDisabled();
    fireEvent.click(within(row(container, "m1")).getByRole("checkbox"));
    expect(
      screen.getByLabelText("Delete selected matchers"),
    ).not.toBeDisabled();
  });

  test("should enable and disable the selected matchers", () => {
    const { container, onChange } = renderTable({
      matchers: [matcher({ enabled: false }), matcher({ id: "m2" })],
    });

    fireEvent.click(within(row(container, "m1")).getByRole("checkbox"));
    fireEvent.click(screen.getByLabelText("Enable selected matchers"));
    expect(onChange).toHaveBeenLastCalledWith([
      expect.objectContaining({ id: "m1", enabled: true }),
      expect.objectContaining({ id: "m2", enabled: true }),
    ]);

    fireEvent.click(screen.getByLabelText("Disable selected matchers"));
    expect(onChange).toHaveBeenLastCalledWith([
      expect.objectContaining({ id: "m1", enabled: false }),
      expect.objectContaining({ id: "m2", enabled: true }),
    ]);
  });

  test("should delete the selected matchers", () => {
    const { container, onChange } = renderTable({
      matchers: [matcher(), matcher({ id: "m2" })],
    });

    fireEvent.click(within(row(container, "m1")).getByRole("checkbox"));
    fireEvent.click(screen.getByLabelText("Delete selected matchers"));

    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({ id: "m2" }),
    ]);
  });
});

describe("MatchersTable read-only mode", () => {
  test("should hide selection and the mutation actions but keep the search", () => {
    const { container } = renderTable({ readonly: true });

    expect(container.querySelectorAll('input[type="checkbox"]')).toHaveLength(
      0,
    );
    expect(screen.queryByLabelText("Add matcher")).toBeNull();
    expect(screen.queryByLabelText("Delete selected matchers")).toBeNull();
    expect(screen.queryByLabelText("Enable selected matchers")).toBeNull();
    expect(
      screen.getByPlaceholderText("Search matchers..."),
    ).toBeInTheDocument();
  });

  test("should leave the enabled switch untouchable", () => {
    renderTable({ readonly: true });
    expect(screen.getByLabelText("Enable body equals")).toBeDisabled();
  });
});

describe("MatchersTable entity type scoping", () => {
  test("should offer the response entity types", async () => {
    const { container } = renderTable();
    activateEditor(cellOf(container, "m1", 4));

    await waitFor(() => expect(querySelectOption("Body")).not.toBeNull());
    expect(querySelectOption("HTTP response status code")).not.toBeNull();
    expect(querySelectOption("Path parameter")).toBeNull();
  });

  test("should offer the request entity types", async () => {
    const { container } = renderTable({ kind: "request" });
    activateEditor(cellOf(container, "m1", 4));

    await waitFor(() => expect(querySelectOption("Body")).not.toBeNull());
    expect(querySelectOption("Path parameter")).not.toBeNull();
    expect(querySelectOption("Query parameter")).not.toBeNull();
    expect(querySelectOption("HTTP response status code")).toBeNull();
  });

  test("should clear the entity name when the entity type stops needing one", async () => {
    const { container, onChange } = renderTable({
      matchers: [
        matcher({
          entityType: MatcherEntityType.HEADER,
          entityName: "X-Trace",
        }),
      ],
    });
    activateEditor(cellOf(container, "m1", 4));

    await waitFor(() => expect(querySelectOption("Body")).not.toBeNull());
    fireEvent.click(querySelectOption("Body") as HTMLElement);

    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith([
        expect.objectContaining({
          entityType: MatcherEntityType.BODY,
          entityName: null,
        }),
      ]),
    );
  });
});

describe("MatchersTable condition changes", () => {
  test("should clear the parameters when the condition changes", async () => {
    const { container, onChange } = renderTable();
    activateEditor(cellOf(container, "m1", 3));

    await waitFor(() =>
      expect(querySelectOption("Matches pattern")).not.toBeNull(),
    );
    fireEvent.click(querySelectOption("Matches pattern") as HTMLElement);

    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith([
        expect.objectContaining({
          type: MatcherType.MATCH,
          parameters: [],
        }),
      ]),
    );
  });
});

describe("MatchersTable parameter editors", () => {
  test("should write value for an equal matcher", async () => {
    const { container, onChange } = renderTable({
      matchers: [matcher({ parameters: [] })],
    });
    activateEditor(cellOf(container, "m1", 6));

    const input = await screen.findByLabelText("value");
    fireEvent.change(input, { target: { value: "hello" } });
    fireEvent.keyDown(input, { key: "Enter", code: "Enter", keyCode: 13 });

    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith([
        expect.objectContaining({
          parameters: [{ name: "value", value: "hello" }],
        }),
      ]),
    );
  });

  test("should write pattern for a match matcher", async () => {
    const { container, onChange } = renderTable({
      matchers: [matcher({ type: MatcherType.MATCH, parameters: [] })],
    });
    activateEditor(cellOf(container, "m1", 6));

    const input = await screen.findByLabelText("pattern");
    fireEvent.change(input, { target: { value: "a+" } });
    fireEvent.keyDown(input, { key: "Enter", code: "Enter", keyCode: 13 });

    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith([
        expect.objectContaining({
          parameters: [{ name: "pattern", value: "a+" }],
        }),
      ]),
    );
  });

  test("should offer the status picker for an equal matcher over the status", async () => {
    const { container, onChange } = renderTable({
      matchers: [
        matcher({ entityType: MatcherEntityType.STATUS, parameters: [] }),
      ],
    });
    activateEditor(cellOf(container, "m1", 6));

    await waitFor(() => expect(querySelectOption("200 OK")).not.toBeNull());
    fireEvent.click(querySelectOption("200 OK") as HTMLElement);

    await waitFor(() =>
      expect(onChange).toHaveBeenCalledWith([
        expect.objectContaining({
          parameters: [{ name: "value", value: "200" }],
        }),
      ]),
    );
  });

  test("should open the JSON modal and write path plus schema", () => {
    const { container, onChange } = renderTable({
      matchers: [
        matcher({ type: MatcherType.MATCH_JSON_SCHEMA, parameters: [] }),
      ],
    });
    fireEvent.click(
      within(cellOf(container, "m1", 6)).getByLabelText("Edit schema"),
    );

    expect(showModal).toHaveBeenCalledTimes(1);
    const modal = (
      showModal.mock.calls[0][0] as { component: React.ReactElement }
    ).component as React.ReactElement<JsonMatcherParametersModalProps>;
    expect(modal.props.documentParameterName).toBe("schema");

    modal.props.onSubmit([
      { name: "path", value: "$" },
      { name: "schema", value: "{}" },
    ]);
    expect(onChange).toHaveBeenCalledWith([
      expect.objectContaining({
        parameters: [
          { name: "path", value: "$" },
          { name: "schema", value: "{}" },
        ],
      }),
    ]);
  });

  test("should open the JSON modal writing sample for a match_json matcher", () => {
    const { container } = renderTable({
      matchers: [matcher({ type: MatcherType.MATCH_JSON, parameters: [] })],
    });
    fireEvent.click(
      within(cellOf(container, "m1", 6)).getByLabelText("Edit sample"),
    );

    const modal = (
      showModal.mock.calls[0][0] as { component: React.ReactElement }
    ).component as React.ReactElement<JsonMatcherParametersModalProps>;
    expect(modal.props.documentParameterName).toBe("sample");
  });

  test("should show no editor for a matcher that takes no parameters", () => {
    const { container } = renderTable({
      matchers: [matcher({ type: MatcherType.EXIST, parameters: [] })],
    });
    expect(cellOf(container, "m1", 6)).toHaveTextContent("Not applicable");
  });

  test("should flag parameters that do not fit the matcher type", () => {
    renderTable({ matchers: [matcher({ parameters: [] })] });
    expect(
      screen.getByTestId("matcher-parameters-invalid"),
    ).toBeInTheDocument();
  });
});
