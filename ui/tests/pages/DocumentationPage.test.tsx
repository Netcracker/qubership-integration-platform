/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";

const mockNavigate = jest.fn();
const mockLoadPaths = jest.fn();

jest.mock("react-router-dom", () => ({
  useParams: () => ({ "*": "01__Chains/page" }),
  useLocation: () => ({ pathname: "/doc/01__Chains/page" }),
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
  DocumentationViewer: ({ content, docPath }: any) => (
    <div data-testid="doc-viewer" data-doc-path={docPath}>{content}</div>
  ),
}));

jest.mock("../../src/components/documentation/DocumentationSidebar", () => ({
  DocumentationSidebar: () => <div data-testid="sidebar" />,
}));

jest.mock("../../src/components/documentation/DocumentationSearch", () => ({
  DocumentationSearch: () => <div data-testid="search" />,
}));

jest.mock("../../src/pages/PageWithSidebar", () => ({
  PageWithSidebar: ({ children }: any) => <div data-testid="page-with-sidebar">{children}</div>,
}));

jest.mock("../../src/pages/DocumentationPage.module.css", () => ({
  __esModule: true,
  default: { loadingContainer: "loadingContainer" },
}));

jest.mock("antd", () => ({
  Spin: ({ size }: any) => <div data-testid="spin" data-size={size} />,
  Result: ({ title, subTitle }: any) => (
    <div data-testid="result">
      <span>{title}</span>
      <span>{subTitle}</span>
    </div>
  ),
  Button: ({ children, onClick }: any) => (
    <button onClick={onClick}>{children}</button>
  ),
}));

import { DocumentationPage } from "../../src/pages/DocumentationPage";

describe("DocumentationPage", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (global as any).fetch = jest.fn();
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

  test("detects HTML response as not-found (Vite SPA fallback)", async () => {
    (global.fetch as jest.Mock).mockResolvedValue({
      ok: true,
      text: () => Promise.resolve("<!DOCTYPE html><html><body>SPA</body></html>"),
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
});
