/**
 * @jest-environment jsdom
 */

import { describe, it, expect, jest, beforeEach } from "@jest/globals";
import "@testing-library/jest-dom";
import { render, screen } from "@testing-library/react";
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

jest.mock("../../../src/hooks/useSnapshots.tsx", () => ({
  useSnapshots: () => ({
    isLoading: false,
    snapshots: [{ id: "snap-1", name: "snap-1" }],
  }),
}));

import { DeploymentCreate } from "../../../src/components/modal/DeploymentCreate.tsx";

beforeEach(() => {
  jest.clearAllMocks();
});

describe("DeploymentCreate", () => {
  it("should keep Deploy disabled when initialDomains are omitted", () => {
    render(<DeploymentCreate chainId="chain-1" />);

    expect(screen.getByText("Deploy").closest("button")).toBeDisabled();
  });

  it("should enable Deploy when initialDomains are provided", () => {
    render(
      <DeploymentCreate
        chainId="chain-1"
        initialDomains={[{ name: "default", type: DomainType.CLASSIC }]}
      />,
    );

    expect(screen.getByText("Deploy").closest("button")).toBeEnabled();
  });
});
