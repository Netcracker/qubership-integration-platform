/**
 * @jest-environment jsdom
 */
import { render, screen, fireEvent, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { TableOfContentNode } from "../../../src/services/documentation/documentationTypes";

const mockNavigate = jest.fn();
const mockLoadTOC = jest.fn();
const mockLoadPaths = jest.fn();
let mockPathname = "/doc/00__Overview/overview";

jest.mock("react-router-dom", () => ({
  useNavigate: () => mockNavigate,
  useLocation: () => ({ pathname: mockPathname }),
}));

jest.mock("../../../src/hooks/useDocumentation", () => ({
  useDocumentation: () => ({
    loadTOC: mockLoadTOC,
    loadPaths: mockLoadPaths,
  }),
}));

jest.mock("../../../src/services/documentation/documentationUrlUtils", () => ({
  DOCUMENTATION_ROUTE_BASE: "/doc",
  toDocRoutePath: (base: string, path: string) => `${base}/${path}`,
}));

jest.mock(
  "../../../src/components/documentation/DocumentationSidebar.module.css",
  () => ({
    __esModule: true,
    default: { sidebar: "sidebar" },
  }),
);

import { DocumentationSidebar } from "../../../src/components/documentation/DocumentationSidebar";

const buildTOC = (): TableOfContentNode => ({
  title: "",
  children: [
    {
      title: "Overview",
      documentId: 0,
      children: [],
    },
    {
      title: "Chains",
      children: [
        {
          title: "Service Call",
          documentId: 1,
          children: [],
        },
        {
          title: "HTTP Sender",
          documentId: 2,
          children: [],
        },
      ],
    },
    {
      title: "Services",
      documentId: 3,
      children: [],
    },
  ],
});

const mockPaths = [
  "00__Overview/overview.md",
  "01__Chains/service_call.md",
  "01__Chains/http_sender.md",
  "02__Services/services.md",
];

const renderAndFlush = async (
  props?: Partial<{ onSelect: jest.Mock; collapsed: boolean }>,
) => {
  const result = render(<DocumentationSidebar {...props} />);
  await act(async () => {
    await flushPromises();
  });
  return result;
};

const flushPromises = () => new Promise<void>((r) => setTimeout(r, 0));

beforeEach(() => {
  jest.clearAllMocks();
  mockLoadTOC.mockResolvedValue(buildTOC());
  mockLoadPaths.mockResolvedValue(mockPaths);
  mockPathname = "/doc/00__Overview/overview";
});

describe("DocumentationSidebar", () => {
  it("renders tree nodes from TOC", async () => {
    await renderAndFlush();

    expect(screen.getByText("Overview")).toBeInTheDocument();
    expect(screen.getByText("Chains")).toBeInTheDocument();
    expect(screen.getByText("Services")).toBeInTheDocument();
  });

  it("renders nothing when collapsed", () => {
    const { container } = render(<DocumentationSidebar collapsed={true} />);
    expect(container.innerHTML).toBe("");
  });

  it("selects current document based on location", async () => {
    await renderAndFlush();

    const overviewNode = screen.getByText("Overview");
    const wrapper = overviewNode.closest(".ant-tree-node-content-wrapper");
    expect(wrapper).toHaveClass("ant-tree-node-selected");
  });

  it("calls onSelect when a document node is clicked", async () => {
    const onSelect = jest.fn();
    await renderAndFlush({ onSelect });

    fireEvent.click(screen.getByText("Services"));

    expect(onSelect).toHaveBeenCalledWith("/doc/02__Services/services");
  });

  it("navigates when onSelect is not provided", async () => {
    await renderAndFlush();

    fireEvent.click(screen.getByText("Services"));

    expect(mockNavigate).toHaveBeenCalledWith("/doc/02__Services/services");
  });

  it("handles loading errors gracefully", async () => {
    const spy = jest.spyOn(console, "error").mockImplementation(() => {});
    mockLoadTOC.mockRejectedValue(new Error("Network error"));
    await renderAndFlush();

    expect(screen.queryByRole("tree")).toBeInTheDocument();
    expect(spy).toHaveBeenCalledWith(
      "Failed to load sidebar:",
      expect.any(Error),
    );
    spy.mockRestore();
  });

  it("expands path to current document on mount", async () => {
    mockPathname = "/doc/01__Chains/service_call";
    await renderAndFlush();

    expect(screen.getByText("Service Call")).toBeInTheDocument();
  });

  it("handles double-click on expandable document node", async () => {
    const tocWithDocGroup: TableOfContentNode = {
      title: "",
      children: [
        {
          title: "Chains",
          documentId: 0,
          children: [
            { title: "Child", documentId: 1, children: [] },
          ],
        },
      ],
    };
    mockLoadTOC.mockResolvedValue(tocWithDocGroup);
    mockLoadPaths.mockResolvedValue(["01__Chains/chains.md", "01__Chains/child.md"]);
    mockPathname = "/doc/01__Chains/chains";

    await renderAndFlush();

    fireEvent.doubleClick(screen.getByText("Chains"));
    await act(async () => {
      await flushPromises();
    });

    expect(mockNavigate).not.toHaveBeenCalled();
  });

  it("collapses expanded group when its switcher is clicked", async () => {
    mockPathname = "/doc/01__Chains/service_call";
    await renderAndFlush();

    expect(screen.getByText("Service Call")).toBeInTheDocument();

    const switchers = document.querySelectorAll(".ant-tree-switcher");
    const chainsSwitcher = Array.from(switchers).find((s) => {
      const sibling = s.nextElementSibling;
      return sibling?.textContent?.includes("Chains");
    });
    expect(chainsSwitcher).toBeTruthy();

    fireEvent.click(chainsSwitcher!);
    await act(async () => {
      await flushPromises();
    });

    expect(screen.queryByText("Service Call")).not.toBeInTheDocument();
  });
});
