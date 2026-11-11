/** @jest-environment jsdom */
import { fireEvent, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MarkdownRenderer } from "../../../src/components/ai/AiMarkdownRenderer";

// Match the parser's pre/code boundary without loading its ESM dependency tree in Jest.
jest.mock("react-markdown", () => {
  const React = require("react") as typeof import("react");
  return {
    __esModule: true,
    default: ({
      children,
      components,
    }: {
      children: string;
      components: Record<string, import("react").ElementType>;
    }) => {
      if (children.startsWith("```")) {
        const lines = children.split("\n");
        const language = lines[0].slice(3);
        return React.createElement(
          components.pre ?? "pre",
          {},
          React.createElement(
            components.code ?? "code",
            {
              className: language ? "language-" + language : undefined,
            },
            lines.slice(1, -1).join("\n") + "\n",
          ),
        );
      }
      return React.createElement(
        components.code ?? "code",
        {},
        children.slice(1, -1),
      );
    },
  };
});
jest.mock("remark-gfm", () => ({ __esModule: true, default: jest.fn() }));
jest.mock("react-syntax-highlighter", () => ({
  Prism: ({ children }: { children: string }) => <pre>{children}</pre>,
}));
jest.mock("../../../src/components/ai/AiMermaidDiagram", () => ({
  AiMermaidDiagram: ({ code }: { code: string }) => (
    <div role="img" aria-label="Mermaid diagram">
      {code}
    </div>
  ),
}));

describe("MarkdownRenderer code boundaries", () => {
  const writeText = jest.fn();

  beforeEach(() => {
    writeText.mockClear();
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText },
    });
  });

  it.each(["return body;", "first line\nsecond line"])(
    "should render and copy an untagged block as a single block for %s",
    (code) => {
      const { container } = render(
        <MarkdownRenderer>{"```\n" + code + "\n```"}</MarkdownRenderer>,
      );
      expect(container.querySelectorAll(".ai-code-block")).toHaveLength(1);
      expect(container.querySelector(".ai-inline-code")).toBeNull();
      expect(container.querySelector(".ai-code-block pre")?.textContent).toBe(
        code,
      );
      fireEvent.click(screen.getByRole("button", { name: "Copy" }));
      expect(writeText).toHaveBeenCalledWith(code);
    },
  );

  it("should keep inline values separate from code blocks", () => {
    const { container } = render(
      <MarkdownRenderer>{"`script`"}</MarkdownRenderer>,
    );
    expect(container.querySelector("code.ai-inline-code")?.textContent).toBe(
      "script",
    );
    expect(container.querySelector(".ai-code-block")).toBeNull();
    expect(screen.queryByRole("button", { name: "Copy" })).toBeNull();
  });

  it("should preserve a tagged script block without an outer pre wrapper", () => {
    const { container } = render(
      <MarkdownRenderer>{"```groovy\nreturn body;\n```"}</MarkdownRenderer>,
    );
    expect(container.querySelector(".ai-code-block pre")?.textContent).toBe(
      "return body;",
    );
    expect(container.querySelector("pre .ai-code-block")).toBeNull();
  });

  it("should retain Mermaid rendering", () => {
    render(
      <MarkdownRenderer>{"```mermaid\nsequenceDiagram\n```"}</MarkdownRenderer>,
    );
    expect(
      screen.getByRole("img", { name: "Mermaid diagram" }),
    ).toHaveTextContent("sequenceDiagram");
  });

  it("should retain collapsible chain-plan JSON blocks", () => {
    render(
      <MarkdownRenderer>{"```chain-plan-json\n{}\n```"}</MarkdownRenderer>,
    );
    expect(
      screen.getByText("Chain implementation plan (JSON)").closest("button"),
    ).toHaveAttribute("aria-expanded", "false");
  });
});
