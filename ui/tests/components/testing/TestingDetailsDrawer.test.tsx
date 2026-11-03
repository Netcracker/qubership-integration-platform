/**
 * @jest-environment jsdom
 */

import "@testing-library/jest-dom";
import { fireEvent, render, screen } from "@testing-library/react";
import { TestingAuditFields } from "../../../src/api/apiTypes";
import {
  auditSection,
  chainItem,
  DetailsLink,
  elementItem,
  idItem,
  TestingDetailsDrawer,
  TestingDetailsSection,
} from "../../../src/components/testing/TestingDetailsDrawer";

const mockNavigate = jest.fn();

jest.mock("react-router", () => ({
  useNavigate: () => mockNavigate,
}));

const AUDIT: TestingAuditFields = {
  createdBy: "alice",
  createdAt: "2026-01-02T03:04:05Z",
  updatedBy: "bob",
  updatedAt: "2026-01-03T03:04:05Z",
};

const STATUS_SECTION: TestingDetailsSection = {
  title: "status",
  items: [{ key: "status", label: "Status", children: "Finished" }],
};

function section(
  ...items: TestingDetailsSection["items"]
): TestingDetailsSection {
  return { title: "general info", items };
}

const onClose = jest.fn();

async function renderDrawer(sections: TestingDetailsSection[]): Promise<void> {
  render(
    <TestingDetailsDrawer
      title="Test Case Details"
      sections={sections}
      open
      onClose={onClose}
    />,
  );
  await screen.findByText("Test Case Details");
}

beforeEach(() => {
  mockNavigate.mockReset();
  onClose.mockReset();
});

describe("TestingDetailsDrawer", () => {
  it("should render the audit footer when the drawer supplies an audit section", async () => {
    await renderDrawer([
      section(idItem("case-1")),
      STATUS_SECTION,
      auditSection(AUDIT),
    ]);

    expect(screen.getByText("Created")).toBeInTheDocument();
    expect(screen.getByText(/by alice/)).toBeInTheDocument();
    expect(screen.getByText("Updated")).toBeInTheDocument();
    expect(screen.getByText(/by bob/)).toBeInTheDocument();
  });

  it("should render no audit footer when the drawer supplies none", async () => {
    await renderDrawer([section(idItem("run-1")), STATUS_SECTION]);

    expect(screen.getByText("Status")).toBeInTheDocument();
    expect(screen.queryByText("Created")).not.toBeInTheDocument();
    expect(screen.queryByText("Updated")).not.toBeInTheDocument();
  });

  it("should render no links when no section carries one", async () => {
    await renderDrawer([
      section(idItem("run-1"), {
        key: "cases",
        label: "Test cases",
        children: 7,
      }),
      auditSection(AUDIT),
    ]);

    expect(screen.getByText("7")).toBeInTheDocument();
    expect(document.querySelectorAll("a")).toHaveLength(0);
  });

  it("should head each section with its title", async () => {
    await renderDrawer([
      section(idItem("case-1")),
      STATUS_SECTION,
      auditSection(AUDIT),
    ]);

    const headers = [
      ...document.querySelectorAll(".details-sidebar-properties-group"),
    ].map((header) => header.textContent);
    expect(headers).toEqual(["general info", "status", "audit"]);
  });

  it("should label the id and offer it for copying", async () => {
    await renderDrawer([section(idItem("case-1"))]);

    expect(screen.getByText("ID")).toBeInTheDocument();
    expect(screen.getByText("case-1")).toBeInTheDocument();
    expect(document.querySelector(".ant-typography-copy")).toBeInTheDocument();
  });

  it("should put every label beside its value", async () => {
    await renderDrawer([
      section(
        { key: "status", label: "Status", children: "Finished" },
        { key: "cases", label: "Test cases", children: 7 },
      ),
    ]);

    const row = screen.getByText("Status").closest(".ant-row");
    expect(row).toHaveTextContent("StatusFinished");
    expect(screen.getByText("7").closest(".ant-row")).toHaveTextContent(
      "Test cases7",
    );
  });

  it("should close when the close control is used", async () => {
    await renderDrawer([section(idItem("case-1"))]);

    fireEvent.click(screen.getByLabelText("Close"));

    expect(onClose).toHaveBeenCalled();
  });

  it("should render no sections when the entity is absent", async () => {
    await renderDrawer([]);

    expect(
      document.querySelectorAll(".details-sidebar-properties-group"),
    ).toHaveLength(0);
  });

  it("should keep the content out of the document when the drawer is closed", () => {
    render(
      <TestingDetailsDrawer
        title="Test Case Details"
        sections={[section(idItem("case-1"))]}
        open={false}
        onClose={jest.fn()}
      />,
    );

    expect(screen.queryByText("ID")).not.toBeInTheDocument();
  });

  it("should navigate to the chain when the chain link is clicked", async () => {
    await renderDrawer([section(chainItem("chain-1", "First chain"))]);

    fireEvent.click(screen.getByText("First chain"));

    expect(mockNavigate).toHaveBeenCalledWith("/chains/chain-1");
  });

  it("should show the chain id when the chain name has not resolved", async () => {
    await renderDrawer([section(chainItem("chain-1", ""))]);

    expect(screen.getByText("chain-1")).toBeInTheDocument();
  });

  it("should show a placeholder when the entity names no chain", async () => {
    await renderDrawer([section(chainItem(null, "First chain"))]);

    expect(screen.getByText("—")).toBeInTheDocument();
    expect(document.querySelectorAll("a")).toHaveLength(0);
  });

  it("should navigate to the element on the chain graph when the element link is clicked", async () => {
    await renderDrawer([
      section(elementItem("Trigger", "chain-1", "element-1", "HTTP Trigger")),
    ]);

    expect(screen.getByText("Trigger")).toBeInTheDocument();
    fireEvent.click(screen.getByText("HTTP Trigger"));

    expect(mockNavigate).toHaveBeenCalledWith(
      "/chains/chain-1/graph/element-1",
    );
  });

  it("should show the element id when the element name has not resolved", async () => {
    await renderDrawer([
      section(elementItem("Endpoint", "chain-1", "element-1", "")),
    ]);

    expect(screen.getByText("Endpoint")).toBeInTheDocument();
    expect(screen.getByText("element-1")).toBeInTheDocument();
  });

  it("should show a placeholder when the entity names no element", async () => {
    await renderDrawer([
      section(elementItem("Trigger", "chain-1", null, "Trigger 1")),
    ]);

    expect(screen.getByText("—")).toBeInTheDocument();
    expect(document.querySelectorAll("a")).toHaveLength(0);
  });

  it("should navigate to the route a details link names", async () => {
    await renderDrawer([
      section({
        key: "errors",
        label: "Errors",
        children: <DetailsLink to="/errors/run-1">3</DetailsLink>,
      }),
    ]);

    fireEvent.click(screen.getByText("3"));

    expect(mockNavigate).toHaveBeenCalledWith("/errors/run-1");
  });
});
