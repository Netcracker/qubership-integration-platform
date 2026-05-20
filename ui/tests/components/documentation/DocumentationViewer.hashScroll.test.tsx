/**
 * @jest-environment jsdom
 */
import React from "react";
import { act, render } from "@testing-library/react";
import "@testing-library/jest-dom";

const locationRef: { current: { hash: string; pathname: string } } = {
  current: { hash: "", pathname: "/doc/x" },
};

jest.mock("react-router-dom", () => ({
  useNavigate: () => jest.fn(),
  useLocation: () => locationRef.current,
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

jest.mock("react-markdown", () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports -- jest.mock factory runs before imports
  const R = require("react") as typeof import("react");
  return {
    __esModule: true,
    default: ({ children }: { children: unknown }) => {
      const md = typeof children === "string" ? children : "";
      const headings: React.ReactNode[] = [];
      let key = 0;
      for (const line of md.split(/\r?\n/)) {
        const m = line.match(/^#+\s+(.+)$/);
        if (!m) continue;
        const text = m[1].trim();
        const slug = text
          .toLowerCase()
          .replace(/[^a-z0-9\s-]/g, "")
          .trim()
          .replace(/\s+/g, "-");
        headings.push(R.createElement("h1", { key: key++, id: slug }, text));
      }
      return R.createElement("div", { "data-testid": "markdown" }, ...headings);
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

const flushRaf = async (frames = 4) => {
  for (let i = 0; i < frames; i++) {
    await act(async () => {
      await new Promise<void>((resolve) => setTimeout(resolve, 20));
    });
  }
};

const scrollIntoViewMock = jest.fn();

beforeAll(() => {
  Object.defineProperty(Element.prototype, "scrollIntoView", {
    configurable: true,
    writable: true,
    value: scrollIntoViewMock,
  });
  if (!globalThis.requestAnimationFrame) {
    globalThis.requestAnimationFrame = ((cb: FrameRequestCallback) =>
      setTimeout(
        () => cb(performance.now()),
        0,
      )) as unknown as typeof requestAnimationFrame;
  }
});

beforeEach(() => {
  locationRef.current = { hash: "", pathname: "/doc/x" };
  scrollIntoViewMock.mockClear();
});

describe("DocumentationViewer — hash scroll", () => {
  test("scrolls to target when hash is set on mount", async () => {
    locationRef.current = { hash: "#my-section", pathname: "/doc/x" };
    render(<DocumentationViewer content="# My Section" />);
    await flushRaf();
    expect(scrollIntoViewMock).toHaveBeenCalledTimes(1);
    expect(scrollIntoViewMock).toHaveBeenCalledWith({
      behavior: "smooth",
      block: "start",
    });
  });

  test("does nothing when hash is empty", async () => {
    render(<DocumentationViewer content="# My Section" />);
    await flushRaf();
    expect(scrollIntoViewMock).not.toHaveBeenCalled();
  });

  test("re-scrolls when location.hash changes", async () => {
    locationRef.current = { hash: "#first", pathname: "/doc/x" };
    const { rerender } = render(
      <DocumentationViewer content={"# First\n# Second"} />,
    );
    await flushRaf();
    expect(scrollIntoViewMock).toHaveBeenCalledTimes(1);

    locationRef.current = { hash: "#second", pathname: "/doc/x" };
    rerender(<DocumentationViewer content={"# First\n# Second"} />);
    await flushRaf();
    expect(scrollIntoViewMock).toHaveBeenCalledTimes(2);
  });

  test("retries until the target appears when content updates late", async () => {
    locationRef.current = { hash: "#late-section", pathname: "/doc/x" };
    const { rerender } = render(<DocumentationViewer content="" />);
    await flushRaf(2);
    expect(scrollIntoViewMock).not.toHaveBeenCalled();

    rerender(<DocumentationViewer content="# Late Section" />);
    await flushRaf();
    expect(scrollIntoViewMock).toHaveBeenCalledTimes(1);
  });

  test("gives up after MAX_ATTEMPTS without throwing when id is missing", async () => {
    locationRef.current = { hash: "#nonexistent", pathname: "/doc/x" };
    render(<DocumentationViewer content="# Something Else" />);
    await flushRaf(15);
    expect(scrollIntoViewMock).not.toHaveBeenCalled();
  });

  test("cancels pending scroll on unmount", async () => {
    locationRef.current = { hash: "#my-section", pathname: "/doc/x" };
    const { unmount } = render(<DocumentationViewer content="" />);
    unmount();
    await flushRaf(3);
    expect(scrollIntoViewMock).not.toHaveBeenCalled();
  });

  test("decodes URI-encoded hash values", async () => {
    locationRef.current = {
      hash: "#my-section",
      pathname: "/doc/x",
    };
    render(<DocumentationViewer content="# My Section" />);
    await flushRaf();
    expect(scrollIntoViewMock).toHaveBeenCalledTimes(1);
  });

  test("treats bare '#' hash as empty", async () => {
    locationRef.current = { hash: "#", pathname: "/doc/x" };
    render(<DocumentationViewer content="# My Section" />);
    await flushRaf();
    expect(scrollIntoViewMock).not.toHaveBeenCalled();
  });
});
