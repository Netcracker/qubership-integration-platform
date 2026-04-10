/**
 * @jest-environment jsdom
 */
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";

const mockNavigate = jest.fn();
const mockLoadPaths = jest.fn();
let mockDocPath: string | undefined = "01__Chains/page";
let mockLocationPathname = "/doc/01__Chains/page";

jest.mock("react-router-dom", () => ({
  useParams: () => ({ "*": mockDocPath }),
  useLocation: () => ({ pathname: mockLocationPathname }),
  useNavigate: () => mockNavigate,
}));

jest.mock("../../src/hooks/useDocumentation", () => ({
  useDocumentation: () => ({ loadPaths: mockLoadPaths }),
}));

jest.mock("../../src/appConfig", () => ({
  getConfig: () => ({ documentationBaseUrl: "/test-docs" }),
}));

jest.mock("../../src/services/documentation/documentationUrlUtils", () => ({
  getDocumentationAssetsBaseUrl: () => "/test-docs",
  DOCUMENTATION_ROUTE_BASE: "/doc",
  joinUrl: (...parts: string[]) => parts.join("/"),
  toDocMarkdownAssetPath: (p: string) => `${p}.md`,
  toDocRoutePath: (base: string, path: string) => `${base}/${path}`,
}));

jest.mock("../../src/components/documentation/DocumentationViewer", () => ({
  DocumentationViewer: ({
    content,
    docPath,
  }: {
    content: string;
    docPath?: string;
  }) => (
    <div data-testid="doc-viewer" data-doc-path={docPath}>
      {content}
    </div>
  ),
}));

jest.mock("../../src/components/documentation/DocumentationSidebar", () => ({
  DocumentationSidebar: () => <div data-testid="sidebar" />,
}));

jest.mock("../../src/components/documentation/DocumentationSearch", () => ({
  DocumentationSearch: () => <div data-testid="search" />,
}));

jest.mock("../../src/pages/PageWithSidebar", () => ({
  PageWithSidebar: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="page-with-sidebar">{children}</div>
  ),
}));

jest.mock("../../src/pages/DocumentationPage.module.css", () => ({
  __esModule: true,
  default: { loadingContainer: "loadingContainer" },
}));

jest.mock("antd", () => ({
  Spin: ({ size }: { size?: string }) => (
    <div data-testid="spin" data-size={size} />
  ),
  Result: ({
    title,
    subTitle,
    extra,
  }: {
    title?: React.ReactNode;
    subTitle?: React.ReactNode;
    extra?: React.ReactNode;
  }) => (
    <div data-testid="result">
      <span>{title}</span>
      <span>{subTitle}</span>
      {extra}
    </div>
  ),
  Button: ({
    children,
    onClick,
  }: {
    children: React.ReactNode;
    onClick?: () => void;
  }) => <button onClick={onClick}>{children}</button>,
}));

import { DocumentationPage } from "../../src/pages/DocumentationPage";

describe("DocumentationPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockDocPath = "01__Chains/page";
    mockLocationPathname = "/doc/01__Chains/page";
    global.fetch = jest.fn() as typeof fetch;
  });

  test("renders document content after successful fetch", async () => {
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: true,
      text: () => Promise.resolve("# Hello"),
      headers: new Map([["content-type", "text/markdown"]]),
    });

    render(<DocumentationPage />);

    await waitFor(() => {
      expect(screen.getByTestId("doc-viewer")).toHaveTextContent("# Hello");
    });
  });

  test("shows error on 404 response", async () => {
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: false,
      status: 404,
      statusText: "Not Found",
    });

    render(<DocumentationPage />);

    await waitFor(() => {
      expect(screen.getByTestId("result")).toBeInTheDocument();
      expect(screen.getByText("Document not found")).toBeInTheDocument();
    });
  });

  test("shows error with status text on non-404 failure", async () => {
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: false,
      status: 500,
      statusText: "Internal Server Error",
    });

    render(<DocumentationPage />);

    await waitFor(() => {
      expect(screen.getByTestId("result")).toBeInTheDocument();
      expect(
        screen.getByText("Failed to load document: Internal Server Error"),
      ).toBeInTheDocument();
    });
  });

  test("detects HTML response as not-found (Vite SPA fallback)", async () => {
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: true,
      text: () =>
        Promise.resolve("<!DOCTYPE html><html><body>SPA</body></html>"),
      headers: new Map([["content-type", "text/html"]]),
    });

    render(<DocumentationPage />);

    await waitFor(() => {
      expect(screen.getByTestId("result")).toBeInTheDocument();
      expect(screen.getByText("Document not found")).toBeInTheDocument();
    });
  });

  test("shows error on fetch failure", async () => {
    (global.fetch as jest.Mock).mockRejectedValue(new Error("Network error"));

    render(<DocumentationPage />);

    await waitFor(() => {
      expect(screen.getByTestId("result")).toBeInTheDocument();
      expect(screen.getByText("Network error")).toBeInTheDocument();
    });
  });

  test("renders not-found page content for docPath='not-found'", async () => {
    mockDocPath = "not-found";
    mockLocationPathname = "/doc/not-found";

    render(<DocumentationPage />);

    await waitFor(() => {
      expect(screen.getByTestId("doc-viewer")).toHaveTextContent(
        "# Page Not Found",
      );
    });

    expect(global.fetch).not.toHaveBeenCalled();
  });

  test("redirects to first doc when no docPath", async () => {
    mockDocPath = undefined;
    mockLocationPathname = "/doc";
    mockLoadPaths.mockResolvedValue(["00__Overview/overview.md"]);

    render(<DocumentationPage />);

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith(
        "/doc/00__Overview/overview",
        { replace: true },
      );
    });
  });

  test("error page button navigates to documentation home", async () => {
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: false,
      status: 404,
      statusText: "Not Found",
    });

    render(<DocumentationPage />);

    await waitFor(() => {
      expect(screen.getByTestId("result")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Go to Documentation Home"));
    expect(mockNavigate).toHaveBeenCalledWith("/doc");
  });

  test("does not fetch when on search page", async () => {
    mockDocPath = "search";
    mockLocationPathname = "/doc/search";

    render(<DocumentationPage />);

    await waitFor(() => {
      expect(screen.getByTestId("page-with-sidebar")).toBeInTheDocument();
    });

    expect(global.fetch).not.toHaveBeenCalled();
  });

  test("handles non-Error thrown from fetch", async () => {
    (global.fetch as jest.Mock).mockRejectedValue("string error");

    render(<DocumentationPage />);

    await waitFor(() => {
      expect(screen.getByTestId("result")).toBeInTheDocument();
      expect(screen.getByText("Unknown error")).toBeInTheDocument();
    });
  });
});
