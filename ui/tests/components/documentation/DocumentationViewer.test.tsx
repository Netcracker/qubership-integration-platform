/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";

jest.mock("../../../src/components/documentation/DocumentationViewer.css", () => ({}));

jest.mock("../../../src/appConfig", () => ({
  getConfig: () => ({ documentationBaseUrl: "/test-docs" }),
}));

jest.mock("../../../src/hooks/useVSCodeTheme", () => ({
  useVSCodeTheme: () => ({ isDark: false }),
}));

jest.mock("@lightenna/react-mermaid-diagram", () => ({
  MermaidDiagram: ({ children }: { children: string }) => (
    <pre data-testid="mermaid">{children}</pre>
  ),
}), { virtual: true });

jest.mock("react-markdown", () => {
  const React = require("react");
  return {
    __esModule: true,
    default: ({ children, components }: any) => {
      // Simple markdown-like rendering that exercises the components callbacks
      const content = String(children || "");

      // Parse images: ![alt](src)
      const imgRegex = /!\[([^\]]*)\]\(([^)]+)\)/g;
      // Parse links: [text](href)
      const linkRegex = /\[([^\]]*)\]\(([^)]+)\)/g;
      // Parse headings: # text
      const headingRegex = /^#\s+(.+)$/m;

      const elements: React.ReactNode[] = [];
      let remaining = content;
      let key = 0;

      // Extract images
      let match;
      while ((match = imgRegex.exec(content)) !== null) {
        const imgProps = components?.img?.({ src: match[2], alt: match[1] })
          ? undefined : { src: match[2], alt: match[1] };
        if (components?.img) {
          elements.push(
            React.createElement("span", { key: key++ },
              components.img({ src: match[2], alt: match[1], node: {} })
            )
          );
        } else {
          elements.push(React.createElement("img", { key: key++, ...imgProps }));
        }
        remaining = remaining.replace(match[0], "");
      }

      // Extract links (but not images which start with !)
      const cleanContent = content.replace(imgRegex, "");
      while ((match = linkRegex.exec(cleanContent)) !== null) {
        if (components?.a) {
          elements.push(
            React.createElement("span", { key: key++ },
              components.a({ href: match[2], children: match[1], node: {} })
            )
          );
        } else {
          elements.push(
            React.createElement("a", { key: key++, href: match[2] }, match[1])
          );
        }
        remaining = remaining.replace(match[0], "");
      }

      // Extract headings
      const headingMatch = headingRegex.exec(remaining);
      if (headingMatch) {
        elements.push(
          React.createElement("h1", { key: key++ }, headingMatch[1])
        );
        remaining = remaining.replace(headingMatch[0], "");
      }

      // Any remaining text
      remaining = remaining.trim();
      if (remaining && elements.length === 0) {
        elements.push(React.createElement("span", { key: key++ }, remaining));
      }

      return React.createElement("div", { "data-testid": "markdown" }, ...elements);
    },
  };
});

jest.mock("remark-gfm", () => ({ __esModule: true, default: () => {} }));
jest.mock("rehype-raw", () => ({ __esModule: true, default: () => {} }));
jest.mock("rehype-sanitize", () => ({
  __esModule: true,
  default: () => {},
  defaultSchema: {},
}));

import { DocumentationViewer } from "../../../src/components/documentation/DocumentationViewer";

describe("DocumentationViewer", () => {
  test("renders markdown content", () => {
    render(<DocumentationViewer content="# Hello World" />);
    expect(screen.getByText("Hello World")).toBeInTheDocument();
  });

  test("resolves image src relative to doc path", () => {
    render(
      <DocumentationViewer
        content='![alt](img/diagram.png)'
        docPath="01__Chains/1__Graph/page"
      />,
    );
    const img = screen.getByAltText("alt");
    expect(img).toHaveAttribute(
      "src",
      "/test-docs/01__Chains/1__Graph/img/diagram.png",
    );
  });

  test("resolves image src with absolute docs-relative path", () => {
    render(
      <DocumentationViewer
        content='![alt](00__Overview/img/arch.svg)'
        docPath="01__Chains/chains"
      />,
    );
    const img = screen.getByAltText("alt");
    expect(img).toHaveAttribute(
      "src",
      "/test-docs/00__Overview/img/arch.svg",
    );
  });

  test("keeps absolute URLs unchanged", () => {
    render(
      <DocumentationViewer
        content='![ext](https://example.com/image.png)'
      />,
    );
    const img = screen.getByAltText("ext");
    expect(img).toHaveAttribute("src", "https://example.com/image.png");
  });

  test("uses baseUrl prop over config value", () => {
    render(
      <DocumentationViewer
        content='![alt](img/test.png)'
        baseUrl="/custom-base"
        docPath="01__Chains/page"
      />,
    );
    const img = screen.getByAltText("alt");
    expect(img).toHaveAttribute(
      "src",
      "/custom-base/01__Chains/img/test.png",
    );
  });

  test("resolves fallback path without docDir", () => {
    render(
      <DocumentationViewer content='![alt](some-file.png)' />,
    );
    const img = screen.getByAltText("alt");
    expect(img).toHaveAttribute("src", "/test-docs/some-file.png");
  });

  test("resolves relative link as doc route", () => {
    render(
      <DocumentationViewer
        content='[link](01__Chains/chains)'
      />,
    );
    const link = screen.getByText("link");
    expect(link).toHaveAttribute("href", "/doc/01__Chains/chains");
    expect(link).toHaveAttribute("target", "_blank");
  });
});
