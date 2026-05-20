/**
 * @jest-environment jsdom
 */

import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import "@testing-library/jest-dom";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import {
  BulkDeploymentResult,
  BulkDeploymentSnapshotAction,
  BulkDeploymentStatus,
  DomainType,
} from "../../../src/api/apiTypes.ts";

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

import { DeployChains } from "../../../src/components/modal/DeployChains.tsx";

beforeEach(() => {
  jest.clearAllMocks();
});

describe("DeployChains", () => {
  it("renders form with fields and Cancel/Deploy buttons", () => {
    render(<DeployChains chainCount={3} />);

    expect(screen.getByText("Deploy chains")).toBeInTheDocument();
    expect(screen.getByText("Engine domains")).toBeInTheDocument();
    expect(screen.getByText("Snapshot action")).toBeInTheDocument();
    expect(screen.getByText("Cancel")).toBeInTheDocument();
    expect(screen.getByText("Deploy")).toBeInTheDocument();
  });

  it("shows chain count text", () => {
    render(<DeployChains chainCount={5} />);
    expect(screen.getByText("5 chain(s) to deploy")).toBeInTheDocument();
  });

  it("Cancel calls closeContainingModal", () => {
    render(<DeployChains chainCount={3} />);
    fireEvent.click(screen.getByText("Cancel"));
    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  it("Deploy calls onSubmit with correct options", async () => {
    const mockOnSubmit = jest
      .fn<() => Promise<BulkDeploymentResult[]>>()
      .mockResolvedValue([]);
    render(<DeployChains chainCount={3} onSubmit={mockOnSubmit} />);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      expect(mockOnSubmit).toHaveBeenCalledWith({
        domains: [{ name: "default", type: DomainType.CLASSIC }],
        snapshotAction: BulkDeploymentSnapshotAction.CREATE_NEW,
      });
    });
  });

  it("shows results table after successful deploy", async () => {
    const results: BulkDeploymentResult[] = [
      {
        chainId: "chain-1",
        chainName: "Test Chain",
        status: BulkDeploymentStatus.CREATED,
        errorMessage: "",
      },
      {
        chainId: "chain-2",
        chainName: "Failed Chain",
        status: BulkDeploymentStatus.FAILED_DEPLOY,
        errorMessage: "Something went wrong",
      },
    ];
    const mockOnSubmit = jest
      .fn<() => Promise<BulkDeploymentResult[]>>()
      .mockResolvedValue(results);
    render(<DeployChains chainCount={2} onSubmit={mockOnSubmit} />);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      expect(screen.getByText("Test Chain")).toBeInTheDocument();
      expect(screen.getByText("Failed Chain")).toBeInTheDocument();
    });

    expect(screen.getByText("Chain Id")).toBeInTheDocument();
    expect(screen.getByText("Chain Name")).toBeInTheDocument();
    expect(screen.getByText("Status")).toBeInTheDocument();
    expect(screen.getByText("Message")).toBeInTheDocument();
  });

  it("shows Close instead of Cancel/Deploy in results step", async () => {
    const mockOnSubmit = jest
      .fn<() => Promise<BulkDeploymentResult[]>>()
      .mockResolvedValue([]);
    render(<DeployChains chainCount={1} onSubmit={mockOnSubmit} />);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      expect(screen.getByText("Close")).toBeInTheDocument();
    });

    expect(screen.queryByText("Cancel")).not.toBeInTheDocument();
    expect(screen.queryByText("Deploy")).not.toBeInTheDocument();
  });

  it("Close calls closeContainingModal in results step", async () => {
    const mockOnSubmit = jest
      .fn<() => Promise<BulkDeploymentResult[]>>()
      .mockResolvedValue([]);
    render(<DeployChains chainCount={1} onSubmit={mockOnSubmit} />);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      expect(screen.getByText("Close")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByText("Close"));
    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  it("stays on form when onSubmit rejects", async () => {
    const mockOnSubmit = jest
      .fn<() => Promise<BulkDeploymentResult[]>>()
      .mockRejectedValue(new Error("Deploy failed"));
    render(<DeployChains chainCount={1} onSubmit={mockOnSubmit} />);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      expect(mockOnSubmit).toHaveBeenCalled();
    });

    expect(screen.getByText("Deploy")).toBeInTheDocument();
    expect(screen.getByText("Cancel")).toBeInTheDocument();
    expect(screen.queryByText("Close")).not.toBeInTheDocument();
  });

  it("renders CREATED status as success tag", async () => {
    const results: BulkDeploymentResult[] = [
      {
        chainId: "c1",
        chainName: "C1",
        status: BulkDeploymentStatus.CREATED,
        errorMessage: "",
      },
    ];
    const mockOnSubmit = jest
      .fn<() => Promise<BulkDeploymentResult[]>>()
      .mockResolvedValue(results);
    render(<DeployChains chainCount={1} onSubmit={mockOnSubmit} />);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      expect(screen.getByText("Created")).toBeInTheDocument();
    });
  });

  it("renders Chain Name as link for CREATED status", async () => {
    const results: BulkDeploymentResult[] = [
      {
        chainId: "chain-123",
        chainName: "My Chain",
        status: BulkDeploymentStatus.CREATED,
        errorMessage: "",
      },
    ];
    const mockOnSubmit = jest
      .fn<() => Promise<BulkDeploymentResult[]>>()
      .mockResolvedValue(results);
    render(<DeployChains chainCount={1} onSubmit={mockOnSubmit} />);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      const link = screen.getByText("My Chain");
      expect(link.tagName).toBe("A");
      expect(link).toHaveAttribute("href", "/chains/chain-123");
    });
  });

  it("renders Chain Name as plain text for FAILED status", async () => {
    const results: BulkDeploymentResult[] = [
      {
        chainId: "chain-456",
        chainName: "Bad Chain",
        status: BulkDeploymentStatus.FAILED_DEPLOY,
        errorMessage: "error",
      },
    ];
    const mockOnSubmit = jest
      .fn<() => Promise<BulkDeploymentResult[]>>()
      .mockResolvedValue(results);
    render(<DeployChains chainCount={1} onSubmit={mockOnSubmit} />);

    fireEvent.click(screen.getByText("Deploy"));

    await waitFor(() => {
      const element = screen.getByText("Bad Chain");
      expect(element.tagName).not.toBe("A");
    });
  });
});
