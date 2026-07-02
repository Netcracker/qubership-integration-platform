/**
 * @jest-environment jsdom
 */
import { describe, it, expect, jest } from "@jest/globals";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";

jest.mock("../../../../src/api/api", () => ({
  api: {},
}));

jest.mock("../../../../src/api/rest/vscodeExtensionApi", () => ({
  isVsCode: false,
  VSCodeExtensionApi: class {},
}));

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`}>{name}</span>
  ),
}));

import { ChainColumn } from "../../../../src/components/services/ui/ChainColumn";

describe("ChainColumn", () => {
  it("should render an empty-state label when the chains array is empty", () => {
    render(<ChainColumn chains={[]} />);
    expect(screen.getByText("No chains")).toBeInTheDocument();
  });

  it("should build a menu item per chain when the chains array is not empty", async () => {
    render(
      <ChainColumn
        chains={[
          { id: "1", name: "Alpha" },
          { id: "2", name: "Beta" },
        ]}
      />,
    );
    // The trigger label reflects the mapped chains count (plural)...
    expect(screen.getByText(/2\s+chains/)).toBeInTheDocument();
    // ...and opening the dropdown renders one menu item per chain (name+key),
    // not just a length-derived count.
    fireEvent.click(screen.getByRole("button"));
    expect(await screen.findByText("Alpha")).toBeInTheDocument();
    expect(screen.getByText("Beta")).toBeInTheDocument();
  });

  it("should use the singular label when only one chain is present", () => {
    render(<ChainColumn chains={[{ id: "1", name: "Alpha" }]} />);
    expect(screen.getByText(/1\s+chain/)).toBeInTheDocument();
  });

  it("should open the chain in a new browser tab when a menu item is clicked", async () => {
    const openSpy = jest.spyOn(window, "open").mockImplementation(() => null);

    render(
      <ChainColumn
        chains={[
          { id: "chain-1", name: "Alpha" },
          { id: "chain-2", name: "Beta" },
        ]}
      />,
    );

    fireEvent.click(screen.getByRole("button"));

    const item = await screen.findByText("Alpha");
    fireEvent.click(item);

    await waitFor(() => {
      expect(openSpy).toHaveBeenCalledWith("/chains/chain-1/graph", "_blank");
    });

    openSpy.mockRestore();
  });
});
