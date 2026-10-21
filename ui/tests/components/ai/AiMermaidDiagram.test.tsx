/** @jest-environment jsdom */
import { render, screen, waitFor } from "@testing-library/react";
import { AiMermaidDiagram } from "../../../src/components/ai/AiMermaidDiagram";
import mermaid from "mermaid";

jest.mock("mermaid", () => ({
  __esModule: true,
  default: { initialize: jest.fn(), render: jest.fn() },
}));
jest.mock("../../../src/hooks/useVSCodeTheme", () => ({
  useVSCodeTheme: () => ({ isDark: false }),
}));

it("should show source after a render failure and recover when streamed code changes", async () => {
  jest.mocked(mermaid.render).mockRejectedValueOnce(new Error("Incomplete"));
  const { rerender } = render(
    <AiMermaidDiagram code="sequenceDiagram\nClient->>" />,
  );
  await waitFor(() => expect(mermaid.render).toHaveBeenCalledTimes(1));
  expect(screen.getByText(/Client->>/)).toBeInTheDocument();
  expect(screen.queryByRole("img")).not.toBeInTheDocument();

  jest.mocked(mermaid.render).mockResolvedValueOnce({
    svg: "<svg><text>Request</text><script>alert(1)</script></svg>",
    diagramType: "sequence",
  });
  rerender(<AiMermaidDiagram code="sequenceDiagram\nClient->>CIP: Request" />);
  const diagram = await screen.findByRole("img", { name: "Mermaid diagram" });
  expect(diagram.querySelector("svg")).toBeInTheDocument();
  expect(diagram.querySelector("script")).toBeNull();
  expect(diagram).toHaveTextContent("Request");
  expect(mermaid.initialize).toHaveBeenLastCalledWith(
    expect.objectContaining({
      securityLevel: "strict",
      suppressErrorRendering: true,
    }),
  );
});
