/**
 * @jest-environment jsdom
 */
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

const capturedPlugins: { value: unknown[] | null } = { value: null };
const capturedComponents: {
  value: { a?: unknown } | null;
} = { value: null };

jest.mock("react-markdown", () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports -- jest.mock factory runs before imports
  const R = require("react") as typeof import("react");
  return {
    __esModule: true,
    default: ({
      children,
      rehypePlugins,
      components,
    }: {
      children: unknown;
      rehypePlugins?: unknown[];
      components?: { a?: unknown };
    }) => {
      capturedPlugins.value = rehypePlugins ?? null;
      capturedComponents.value = components ?? null;
      return R.createElement(
        "div",
        { "data-testid": "markdown" },
        typeof children === "string" ? children : "",
      );
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
jest.mock("rehype-slug", () => ({
  __esModule: true,
  default: function rehypeSlug() {},
}));
jest.mock("rehype-autolink-headings", () => ({
  __esModule: true,
  default: function rehypeAutolinkHeadings() {},
}));

import rehypeRaw from "rehype-raw";
import rehypeSanitize from "rehype-sanitize";
import rehypeSlug from "rehype-slug";
import rehypeAutolinkHeadings from "rehype-autolink-headings";
import { DocumentationViewer } from "../../../src/components/documentation/DocumentationViewer";

type AttrList = readonly (string | readonly [string, ...unknown[]])[];
type SanitizeSchema = {
  clobber: readonly string[];
  attributes: Record<string, AttrList>;
};
type AutolinkOptions = {
  behavior: string;
  properties: {
    className: readonly string[];
    ariaLabel: string;
    tabIndex: number;
  };
  content: {
    type: string;
    tagName: string;
    properties: { ariaHidden: string };
    children: readonly unknown[];
  };
};

const renderAndGetPlugins = (): unknown[] => {
  capturedPlugins.value = null;
  render(<DocumentationViewer content="# Section" />);
  expect(capturedPlugins.value).not.toBeNull();
  return capturedPlugins.value!;
};

describe("DocumentationViewer — rehypePlugins", () => {
  test("plugins ordered: raw → slug → autolink → sanitize", () => {
    const plugins = renderAndGetPlugins();
    expect(plugins).toHaveLength(4);
    expect(plugins[0]).toBe(rehypeRaw);
    expect(plugins[1]).toBe(rehypeSlug);
    expect((plugins[2] as [unknown, unknown])[0]).toBe(rehypeAutolinkHeadings);
    expect((plugins[3] as [unknown, unknown])[0]).toBe(rehypeSanitize);
  });

  test("autolink options produce append-mode anchor with empty marker content", () => {
    // The visible icon is rendered in React via <OverridableIcon name="link" />
    // from the `a` component override; rehype-autolink-headings only needs to
    // emit a non-empty anchor element carrying className="anchor".
    const plugins = renderAndGetPlugins();
    const [, opts] = plugins[2] as [unknown, AutolinkOptions];
    expect(opts.behavior).toBe("append");
    expect(opts.properties.className).toEqual(["anchor"]);
    expect(opts.properties.ariaLabel).toBe("Permalink to this heading");
    expect(opts.properties.tabIndex).toBe(-1);
    expect(opts.content.tagName).toBe("span");
    expect(opts.content.properties.ariaHidden).toBe("true");
    expect(opts.content.children).toEqual([]);
  });
});

describe("DocumentationViewer — sanitize schema", () => {
  const getSchema = (): SanitizeSchema => {
    const plugins = renderAndGetPlugins();
    return (plugins[3] as [unknown, SanitizeSchema])[1];
  };

  test("clobber excludes id so heading slugs are not prefixed", () => {
    const { clobber } = getSchema();
    expect(clobber).not.toContain("id");
    expect(clobber).toEqual(
      expect.arrayContaining(["ariaDescribedBy", "ariaLabelledBy", "name"]),
    );
  });

  test("all heading levels allow id attribute", () => {
    const { attributes } = getSchema();
    for (const tag of ["h1", "h2", "h3", "h4", "h5", "h6"]) {
      expect(attributes[tag]).toBeDefined();
      expect(attributes[tag]).toContain("id");
    }
  });

  test("anchor tag allows autolink attributes", () => {
    const { attributes } = getSchema();
    for (const attr of [
      "className",
      "ariaLabel",
      "ariaHidden",
      "tabIndex",
      "id",
      "target",
      "rel",
    ]) {
      expect(attributes.a).toContain(attr);
    }
  });

  test("span allows ariaHidden and className for autolink icon", () => {
    const { attributes } = getSchema();
    expect(attributes.span).toContain("ariaHidden");
    expect(attributes.span).toContain("className");
  });
});

describe("DocumentationViewer — root ref", () => {
  test("root container uses viewer className", () => {
    const { container } = render(<DocumentationViewer content="hello" />);
    const root = container.firstChild as HTMLElement;
    expect(root.className).toContain("viewer");
    expect(screen.getByTestId("markdown")).toBeInTheDocument();
  });
});

describe("DocumentationViewer — anchor icon rendering", () => {
  // eslint-disable-next-line @typescript-eslint/no-explicit-any -- test harness
  type AComponent = (props: any) => React.ReactElement;
  const React = jest.requireActual<typeof import("react")>("react");

  const getAComponent = (): AComponent => {
    capturedComponents.value = null;
    render(<DocumentationViewer content="# Section" />);
    // Re-read through a wider type — CFA narrows value to `null` right
    // after the assignment above, even though Markdown's render populates
    // it via a side-effect during `render()`.
    const comps = capturedComponents.value as { a?: unknown } | null;
    const a = comps?.a as AComponent | undefined;
    expect(a).toBeDefined();
    return a!;
  };

  test("renders OverridableIcon inside <a> when className contains 'anchor'", () => {
    const AComp = getAComponent();
    const { container } = render(
      React.createElement(AComp, {
        href: "#section",
        className: "anchor",
        ariaLabel: "Permalink to this heading",
        tabIndex: -1,
      }),
    );
    const link = container.querySelector("a");
    expect(link).toBeInTheDocument();
    expect(link).toHaveAttribute("href", "#section");
    // OverridableIcon wraps the AntD icon in a <span role="img" class="anticon ...">
    const icon = link?.querySelector('[role="img"]');
    expect(icon).toBeInTheDocument();
  });

  test("does not inject icon for regular anchor links (no 'anchor' class)", () => {
    const AComp = getAComponent();
    const { container } = render(
      React.createElement(AComp, { href: "https://example.com" }, "example"),
    );
    const link = container.querySelector("a");
    expect(link).toBeInTheDocument();
    expect(link?.querySelector('[role="img"]')).toBeNull();
  });
});
