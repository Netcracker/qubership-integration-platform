/**
 * @jest-environment jsdom
 */

import {
  describe,
  it,
  expect,
  beforeEach,
  afterEach,
  jest,
} from "@jest/globals";
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { ActionLog, EntityType, LogOperation } from "../../../src/api/apiTypes";

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

import { ActionLogSidebar } from "../../../src/components/admin_tools/ActionLogSidebar";

function makeActionLog(overrides: Partial<ActionLog> = {}): ActionLog {
  return {
    id: "1",
    actionTime: 1680000000000,
    username: "alice",
    operation: LogOperation.CREATE_OR_UPDATE,
    entityType: EntityType.CHAIN_RUNTIME_PROPERTIES,
    entityId: "e1",
    entityName: "Chain1",
    parentType: EntityType.FOLDER,
    parentId: "p1",
    parentName: "Folder1",
    requestId: "r1",
    ...overrides,
  };
}

function renderSidebar(
  value: ActionLog | null,
  open = true,
  onClose: () => void = jest.fn(),
) {
  return render(
    <ActionLogSidebar
      value={value}
      open={open}
      onClose={onClose}
      entityLinkRenderer={(v) => <a>{v.entityName}</a>}
      parentLinkRenderer={(v) => <a>{v.parentName}</a>}
    />,
  );
}

let pageContent: HTMLElement;

describe("ActionLogSidebar", () => {
  beforeEach(() => {
    pageContent = document.createElement("div");
    pageContent.className = "page-content";
    document.body.appendChild(pageContent);
  });

  afterEach(() => {
    pageContent.remove();
  });

  it("should render nothing when closed", () => {
    renderSidebar(makeActionLog(), false);

    expect(screen.queryByText("Action Details")).not.toBeInTheDocument();
  });

  it("should render the drawer inside the page content when open", () => {
    renderSidebar(makeActionLog());

    expect(pageContent).toContainElement(screen.getByText("Action Details"));
  });

  it("should render labeled details of the action", () => {
    renderSidebar(makeActionLog());

    expect(screen.getByText("Initiator")).toBeInTheDocument();
    expect(screen.getByText("alice")).toBeInTheDocument();
    expect(screen.getByText("Operation")).toBeInTheDocument();
    expect(screen.getByText("Create or update")).toBeInTheDocument();
    expect(screen.getByText("Entity ID")).toBeInTheDocument();
    expect(screen.getByText("e1")).toBeInTheDocument();
    expect(screen.getByText("Entity Type")).toBeInTheDocument();
    expect(screen.getByText("Chain runtime properties")).toBeInTheDocument();
    expect(screen.getByText("Parent ID")).toBeInTheDocument();
    expect(screen.getByText("p1")).toBeInTheDocument();
    expect(screen.getByText("Request ID")).toBeInTheDocument();
    expect(screen.getByText("r1")).toBeInTheDocument();
  });

  it("should render entity and parent names through the given renderers", () => {
    renderSidebar(makeActionLog());

    expect(screen.getByText("Chain1").tagName).toBe("A");
    expect(screen.getByText("Folder1").tagName).toBe("A");
  });

  it("should show placeholders when optional fields are missing", () => {
    renderSidebar(
      makeActionLog({
        username: undefined,
        entityId: undefined,
        parentId: undefined,
        requestId: undefined,
      }),
    );

    expect(screen.getAllByText("—")).toHaveLength(4);
  });

  it("should show placeholders when no action is selected", () => {
    renderSidebar(null);

    expect(screen.getByText("Action Details")).toBeInTheDocument();
    expect(screen.getAllByText("—")).toHaveLength(9);
  });

  it("should call onClose when the close button is clicked", () => {
    const onClose = jest.fn();
    renderSidebar(makeActionLog(), true, onClose);

    fireEvent.click(screen.getByRole("button", { name: /close/i }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
