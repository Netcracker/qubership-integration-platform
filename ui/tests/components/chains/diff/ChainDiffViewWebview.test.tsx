/**
 * @jest-environment jsdom
 */

import { act, render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { ChainDiffViewWebview } from "../../../../src/components/chains/diff/ChainDiffViewWebview.tsx";

jest.mock("../../../../src/components/chains/diff/ChainDiffView.tsx", () => ({
  ChainDiffView: ({
    item1,
    item2,
  }: {
    item1: { content: { name: string } };
    item2: { content: { name: string } };
  }) => (
    <div>
      {item1.content.name} vs {item2.content.name}
    </div>
  ),
}));

function postDocuments(name: string, origin = window.origin): void {
  act(() => {
    window.dispatchEvent(
      new MessageEvent("message", {
        data: {
          type: "comparedDocumentsResponse",
          payload: { original: { name }, modified: { name: `${name} v2` } },
        },
        origin,
      }),
    );
  });
}

describe("ChainDiffViewWebview", () => {
  it("should ignore documents when the message comes from another origin", () => {
    render(<ChainDiffViewWebview />);
    postDocuments("chain");
    expect(screen.getByText("chain vs chain v2")).toBeInTheDocument();

    postDocuments("other", "https://example.com");

    expect(screen.getByText("chain vs chain v2")).toBeInTheDocument();
  });
});
