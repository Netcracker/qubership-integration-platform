/**
 * @jest-environment jsdom
 */

import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import "@testing-library/jest-dom";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { DomainType } from "../../../src/api/apiTypes.ts";

const mockCloseContainingModal = jest.fn();

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

jest.mock("../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

jest.mock("../../../src/hooks/useDomains.tsx", () => ({
  useDomains: () => ({
    isLoading: false,
    domains: [{ id: "default", name: "default" }],
  }),
}));

import { SaveAndDeploy } from "../../../src/components/modal/SaveAndDeploy.tsx";
import type { Domain } from "../../../src/components/SelectDomains.tsx";

const DEFAULT_DOMAINS: Domain[] = [
  { name: "default", type: DomainType.CLASSIC },
];

function renderWithDefault(
  mockOnSubmit: (domains: Domain[]) => void | Promise<void>,
  chainId: string | undefined = "chain-1",
) {
  render(
    <SaveAndDeploy
      chainId={chainId}
      initialDomains={DEFAULT_DOMAINS}
      onSubmit={mockOnSubmit}
    />,
  );
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe("SaveAndDeploy", () => {
  it("renders form with fields and Cancel/Deploy buttons", () => {
    render(<SaveAndDeploy chainId="chain-1" />);

    expect(screen.getByText("Save and deploy")).toBeInTheDocument();
    expect(screen.getByText("Domains")).toBeInTheDocument();
    expect(screen.getByText("Cancel")).toBeInTheDocument();
    expect(screen.getByText("Deploy")).toBeInTheDocument();
  });

  it("Cancel calls closeContainingModal", () => {
    render(<SaveAndDeploy chainId="chain-1" />);
    fireEvent.click(screen.getByText("Cancel"));
    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  it("Deploy is disabled without initialDomains", () => {
    render(<SaveAndDeploy chainId="chain-1" />);

    expect(screen.getByText("Deploy").closest("button")).toBeDisabled();
  });

  it("Deploy calls onSubmit with selected domains and closes", async () => {
    const mockOnSubmit = jest.fn(() => {});
    renderWithDefault(mockOnSubmit);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      expect(mockOnSubmit).toHaveBeenCalledWith([
        { name: "default", type: DomainType.CLASSIC },
      ]);
    });
    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  it("Deploy waits for async onSubmit before closing", async () => {
    let resolveSubmit!: () => void;
    const mockOnSubmit = jest.fn(
      () =>
        new Promise<void>((resolve) => {
          resolveSubmit = resolve;
        }),
    );
    renderWithDefault(mockOnSubmit);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      expect(mockOnSubmit).toHaveBeenCalled();
    });
    expect(mockCloseContainingModal).not.toHaveBeenCalled();

    resolveSubmit();

    await waitFor(() => {
      expect(mockCloseContainingModal).toHaveBeenCalled();
    });
  });

  it("Deploy without chainId calls neither onSubmit nor close", () => {
    const mockOnSubmit = jest.fn(() => {});
    render(<SaveAndDeploy onSubmit={mockOnSubmit} />);

    fireEvent.click(screen.getByText("Deploy"));

    expect(mockOnSubmit).not.toHaveBeenCalled();
    expect(mockCloseContainingModal).not.toHaveBeenCalled();
  });

  it("Deploy closes when sync onSubmit throws", async () => {
    const mockOnSubmit = jest.fn(() => {
      throw new Error("Deploy failed");
    });
    renderWithDefault(mockOnSubmit);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      expect(mockCloseContainingModal).toHaveBeenCalled();
    });
  });
});
