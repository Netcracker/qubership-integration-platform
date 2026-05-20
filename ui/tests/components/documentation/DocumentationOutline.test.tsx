/**
 * @jest-environment jsdom
 */
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { useRef } from "react";

jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
  useLocation: () => ({ pathname: "/doc/page", hash: "", search: "", state: null }),
}));

jest.mock(
  "../../../src/components/documentation/DocumentationOutline.module.css",
  () => ({
    __esModule: true,
    default: {
      outline: "outline",
      title: "title",
      list: "list",
      item: "item",
      link: "link",
      active: "active",
    },
  }),
);

const mockNavigate = jest.fn();

// Control headings/activeId returned by the hook
let mockHeadings: { id: string; text: string; level: number }[] = [];
let mockActiveId: string | null = null;

jest.mock("../../../src/hooks/useDocumentOutline", () => ({
  useDocumentOutline: () => ({
    headings: mockHeadings,
    activeId: mockActiveId,
  }),
}));

import { DocumentationOutline } from "../../../src/components/documentation/DocumentationOutline";

const renderOutline = () => {
  const Wrapper = () => {
    const ref = useRef<HTMLDivElement | null>(null);
    return <DocumentationOutline viewerRef={ref} content="markdown" />;
  };
  return render(<Wrapper />);
};

beforeEach(() => {
  mockNavigate.mockClear();
  mockHeadings = [];
  mockActiveId = null;
});

describe("DocumentationOutline", () => {
  test("renders null when fewer than 2 headings", () => {
    mockHeadings = [{ id: "only", text: "Only Heading", level: 1 }];
    const { container } = renderOutline();
    expect(container.firstChild).toBeNull();
  });

  test("renders null when no headings", () => {
    mockHeadings = [];
    const { container } = renderOutline();
    expect(container.firstChild).toBeNull();
  });

  test("renders list of heading links when 2+ headings present", () => {
    mockHeadings = [
      { id: "intro", text: "Introduction", level: 1 },
      { id: "setup", text: "Setup", level: 2 },
      { id: "usage", text: "Usage", level: 2 },
    ];
    renderOutline();

    expect(screen.getByText("Introduction")).toBeInTheDocument();
    expect(screen.getByText("Setup")).toBeInTheDocument();
    expect(screen.getByText("Usage")).toBeInTheDocument();
  });

  test("each link has correct href", () => {
    mockHeadings = [
      { id: "alpha", text: "Alpha", level: 2 },
      { id: "beta", text: "Beta", level: 2 },
    ];
    renderOutline();

    expect(screen.getByText("Alpha").closest("a")).toHaveAttribute("href", "#alpha");
    expect(screen.getByText("Beta").closest("a")).toHaveAttribute("href", "#beta");
  });

  test("active heading link has active class", () => {
    mockHeadings = [
      { id: "first", text: "First", level: 1 },
      { id: "second", text: "Second", level: 2 },
    ];
    mockActiveId = "second";
    renderOutline();

    const activeLink = screen.getByText("Second").closest("a");
    expect(activeLink?.className).toContain("active");

    const inactiveLink = screen.getByText("First").closest("a");
    expect(inactiveLink?.className).not.toContain("active");
  });

  test("click navigates to heading hash via navigate()", () => {
    mockHeadings = [
      { id: "sec-a", text: "Section A", level: 2 },
      { id: "sec-b", text: "Section B", level: 2 },
    ];
    renderOutline();

    fireEvent.click(screen.getByText("Section B"));

    expect(mockNavigate).toHaveBeenCalledWith("/doc/page#sec-b");
  });

  test("indentation increases with heading depth relative to min level", () => {
    mockHeadings = [
      { id: "h2", text: "H2 Heading", level: 2 },
      { id: "h3", text: "H3 Heading", level: 3 },
      { id: "h4", text: "H4 Heading", level: 4 },
    ];
    renderOutline();

    const items = screen.getAllByRole("listitem");
    expect(items[0]).toHaveStyle("padding-left: 0px");
    expect(items[1]).toHaveStyle("padding-left: 12px");
    expect(items[2]).toHaveStyle("padding-left: 24px");
  });

  test("renders 'On this page' title", () => {
    mockHeadings = [
      { id: "a", text: "A", level: 1 },
      { id: "b", text: "B", level: 2 },
    ];
    renderOutline();
    expect(screen.getByText("On this page")).toBeInTheDocument();
  });

  test("nav element has accessible label", () => {
    mockHeadings = [
      { id: "x", text: "X", level: 1 },
      { id: "y", text: "Y", level: 2 },
    ];
    renderOutline();
    expect(screen.getByRole("navigation", { name: "Page outline" })).toBeInTheDocument();
  });
});
