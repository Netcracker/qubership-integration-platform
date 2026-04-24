/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";

jest.mock("react-router-dom", () => ({
  useNavigate: () => jest.fn(),
  useLocation: () => ({
    hash: "",
    pathname: "/doc/x",
    search: "",
    state: null,
  }),
}));

jest.mock(
  "../../../src/components/documentation/DocumentationViewer.module.css",
  () => ({
    __esModule: true,
    default: { viewer: "viewer", dark: "dark" },
  }),
);

jest.mock("../../../src/appConfig", () => ({
  getConfig: () => ({ documentationBaseUrl: "/test-docs" }),
}));

jest.mock("../../../src/theme/context", () => {
  const React = jest.requireActual<typeof import("react")>("react");
  return {
    ThemeContext: {
      ...React.createContext(null),
      _currentValue: {
        theme: "light",
        onThemeChange: jest.fn(),
        showThemeSwitcher: true,
      },
    },
  };
});

jest.mock("../../../src/hooks/useSyntaxHighlighterTheme", () => ({
  useSyntaxHighlighterTheme: () => ({}),
}));

jest.mock(
  "@lightenna/react-mermaid-diagram",
  () => ({
    MermaidDiagram: ({ children }: { children: string }) => (
      <pre data-testid="mermaid">{children}</pre>
    ),
  }),
  { virtual: true },
);

/* Lightweight react-markdown mock: extracts images, links, and headings
   from markdown syntax and renders them through the components prop,
   matching how the real Markdown component delegates to custom renderers. */
jest.mock("react-markdown", () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports -- jest.mock factory runs before imports
  const R = require("react") as typeof import("react");

  interface Components {
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    img?: (p: any) => React.ReactNode;
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    a?: (p: any) => React.ReactNode;
  }

  return {
    __esModule: true,
    default: ({
      children,
      components,
    }: {
      children: unknown;
      components?: Components;
    }) => {
      const md = typeof children === "string" ? children : "";
      const els: React.ReactNode[] = [];
      let key = 0;
      let text = md;

      // Images: ![alt](src)
      for (const m of md.matchAll(/!\[([^\]]*)\]\(([^)]+)\)/g)) {
        const node = components?.img
          ? components.img({ src: m[2], alt: m[1], node: {} })
          : R.createElement("img", { src: m[2], alt: m[1] });
        els.push(R.createElement("span", { key: key++ }, node));
        text = text.replace(m[0], "");
      }

      // Links: [text](href) — only after images are stripped
      for (const m of text.matchAll(/\[([^\]]*)\]\(([^)]+)\)/g)) {
        const node = components?.a
          ? components.a({ href: m[2], children: m[1], node: {} })
          : R.createElement("a", { href: m[2] }, m[1]);
        els.push(R.createElement("span", { key: key++ }, node));
      }

      // Heading: # Title
      const h = /^#\s+(.+)$/m.exec(text);
      if (h) els.push(R.createElement("h1", { key: key++ }, h[1]));

      if (els.length === 0)
        els.push(R.createElement("span", { key: 0 }, text.trim()));

      return R.createElement("div", { "data-testid": "markdown" }, ...els);
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
jest.mock("rehype-slug", () => ({ __esModule: true, default: () => {} }));
jest.mock("rehype-autolink-headings", () => ({
  __esModule: true,
  default: () => {},
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
        content="![alt](img/diagram.png)"
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
        content="![alt](00__Overview/img/arch.svg)"
        docPath="01__Chains/chains"
      />,
    );
    const img = screen.getByAltText("alt");
    expect(img).toHaveAttribute("src", "/test-docs/00__Overview/img/arch.svg");
  });

  test("keeps absolute URLs unchanged", () => {
    render(
      <DocumentationViewer content="![ext](https://example.com/image.png)" />,
    );
    const img = screen.getByAltText("ext");
    expect(img).toHaveAttribute("src", "https://example.com/image.png");
  });

  test("uses baseUrl prop over config value", () => {
    render(
      <DocumentationViewer
        content="![alt](img/test.png)"
        baseUrl="/custom-base"
        docPath="01__Chains/page"
      />,
    );
    const img = screen.getByAltText("alt");
    expect(img).toHaveAttribute("src", "/custom-base/01__Chains/img/test.png");
  });

  test("resolves fallback path without docDir", () => {
    render(<DocumentationViewer content="![alt](some-file.png)" />);
    const img = screen.getByAltText("alt");
    expect(img).toHaveAttribute("src", "/test-docs/some-file.png");
  });

  test("renders Mermaid diagram for .mermaid src", () => {
    render(<DocumentationViewer content="![mermaid](graph LR; A-->B)" />);
    expect(screen.getByTestId("mermaid")).toHaveTextContent("graph LR; A-->B");
  });

  test("strips unsafe javascript: hrefs", () => {
    render(<DocumentationViewer content="[click me](javascript:alert(1))" />);
    // Link should be rendered as plain text, not as <a>
    const el = screen.getByText("click me");
    expect(el.tagName).not.toBe("A");
  });

  test("adds target=_blank to external http links", () => {
    render(<DocumentationViewer content="[docs](https://example.com/docs)" />);
    const link = screen.getByText("docs");
    expect(link).toHaveAttribute("target", "_blank");
    expect(link).toHaveAttribute("rel", "noopener noreferrer");
  });

  test("keeps anchor links as-is", () => {
    render(<DocumentationViewer content="[section](#overview)" />);
    const link = screen.getByText("section");
    expect(link).toHaveAttribute("href", "#overview");
    expect(link).not.toHaveAttribute("target");
  });

  test("applies pathNormalizers to image src", () => {
    render(
      <DocumentationViewer
        content="![alt](Legacy_v1/img/diagram.png)"
        pathNormalizers={[/^Legacy_v1\//]}
        docPath="01__Chains/page"
      />,
    );
    const img = screen.getByAltText("alt");
    expect(img).toHaveAttribute("src", "/test-docs/01__Chains/img/diagram.png");
  });

  test("resolves relative link as doc route", () => {
    render(<DocumentationViewer content="[link](01__Chains/chains)" />);
    const link = screen.getByText("link");
    expect(link).toHaveAttribute("href", "/doc/01__Chains/chains");
  });

  test("resolves relative link with parent segments (../) from nested doc", () => {
    render(
      <DocumentationViewer
        content="[HTTP Trigger](../../01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md)"
        docPath="00__Overview/1__Token_Processing"
      />,
    );
    const link = screen.getByText("HTTP Trigger");
    expect(link).toHaveAttribute(
      "href",
      "/doc/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger",
    );
  });

  test("treats doc-root paths (01__Chains/...) as doc-root-relative from 00__Overview", () => {
    render(
      <DocumentationViewer
        content="[Kafka Trigger](01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/8__Kafka_Trigger/kafka_trigger.md)"
        docPath="00__Overview"
      />,
    );
    const link = screen.getByText("Kafka Trigger");
    expect(link).toHaveAttribute(
      "href",
      "/doc/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/8__Kafka_Trigger/kafka_trigger",
    );
  });

  test("fixes 01__Chains/02__Services/... when doc roots are wrongly nested", () => {
    render(
      <DocumentationViewer
        content="[Services](../02__Services/services.md)"
        docPath="01__Chains/chains"
      />,
    );
    const link = screen.getByText("Services");
    expect(link).toHaveAttribute("href", "/doc/02__Services/services");
  });

  test("fixes 6__Triggers/1__HTTP_Trigger/2__Chain_Trigger when trigger siblings wrongly nested", () => {
    render(
      <DocumentationViewer
        content="[Chain Trigger](2__Chain_Trigger/chain_trigger.md)"
        docPath="01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger"
      />,
    );
    const link = screen.getByText("Chain Trigger");
    expect(link).toHaveAttribute(
      "href",
      "/doc/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger",
    );
  });

  test("fixes 01__Chains/3__Deployments/2__Snapshots when sibling sections wrongly nested", () => {
    render(
      <DocumentationViewer
        content="[Snapshots](2__Snapshots/snapshots.md)"
        docPath="01__Chains/3__Deployments"
      />,
    );
    const link = screen.getByText("Snapshots");
    expect(link).toHaveAttribute(
      "href",
      "/doc/01__Chains/2__Snapshots/snapshots",
    );
  });

  test("fixes 00__Overview/01__Chains/... when single ../ produces wrong resolution", () => {
    render(
      <DocumentationViewer
        content="[HTTP Sender](../01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/4__HTTP_Sender/http_sender.md)"
        docPath="00__Overview/5__Switch_To_Maas"
      />,
    );
    const link = screen.getByText("HTTP Sender");
    expect(link).toHaveAttribute(
      "href",
      "/doc/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/4__HTTP_Sender/http_sender",
    );
  });

  test("fixes Chain Call link from Chain Trigger page (6__Triggers/1__Routing -> 1__Routing)", () => {
    render(
      <DocumentationViewer
        content="[Chain Call](../../1__Routing/6__Chain_Call/chain_call.md)"
        docPath="01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/2__Chain_Trigger/chain_trigger"
      />,
    );
    const link = screen.getByText("Chain Call");
    expect(link).toHaveAttribute(
      "href",
      "/doc/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/6__Chain_Call/chain_call",
    );
  });

  test("fixes trigger/sender links from Sessions page (4__Sessions/1__Graph -> 1__Graph)", () => {
    render(
      <DocumentationViewer
        content="[HTTP Trigger](../1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger.md)"
        docPath="01__Chains/4__Sessions/sessions"
      />,
    );
    const link = screen.getByText("HTTP Trigger");
    expect(link).toHaveAttribute(
      "href",
      "/doc/01__Chains/1__Graph/1__QIP_Elements_Library/6__Triggers/1__HTTP_Trigger/http_trigger",
    );
  });

  test("fixes local links from Services page (services/1__External -> 1__External)", () => {
    render(
      <DocumentationViewer
        content="[External](1__External/external.md)"
        docPath="02__Services/services"
      />,
    );
    const link = screen.getByText("External");
    expect(link).toHaveAttribute(
      "href",
      "/doc/02__Services/1__External/external",
    );
  });

  test("fixes cross-doc links from Services page to Service Call", () => {
    render(
      <DocumentationViewer
        content="[Service Call](../01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call.md)"
        docPath="02__Services/services"
      />,
    );
    const link = screen.getByText("Service Call");
    expect(link).toHaveAttribute(
      "href",
      "/doc/01__Chains/1__Graph/1__QIP_Elements_Library/7__Senders/6__Service_Call/service_call",
    );
  });

  test("fixes QIP Elements Library links from graph page (graph/1__QIP_Elements_Library -> 1__QIP_Elements_Library)", () => {
    render(
      <DocumentationViewer
        content="[Loop](1__QIP_Elements_Library/1__Routing/8__Loop/loop.md)"
        docPath="01__Chains/1__Graph/graph"
      />,
    );
    const link = screen.getByText("Loop");
    expect(link).toHaveAttribute(
      "href",
      "/doc/01__Chains/1__Graph/1__QIP_Elements_Library/1__Routing/8__Loop/loop",
    );
  });
});
