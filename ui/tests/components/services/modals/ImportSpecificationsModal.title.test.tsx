/**
 * @jest-environment jsdom
 */
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";

Object.defineProperty(globalThis, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

jest.mock("../../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({ closeContainingModal: jest.fn() }),
}));

jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: jest.fn(),
    errorWithDetails: jest.fn(),
    info: jest.fn(),
    warning: jest.fn(),
  }),
}));

jest.mock("../../../../src/api/api", () => ({
  api: {},
}));

import { ImportSpecificationsModal } from "../../../../src/components/services/modals/ImportSpecificationsModal";

/**
 * The modal serves two levels of the services tree from one component, and the only thing telling a
 * user which one they are on is the title. Group mode is inferred when a service is given without a
 * group, so both the explicit flag and the inferred case are pinned here.
 */
describe("ImportSpecificationsModal title", () => {
  it("should title the dialog for a group when only a service is given", () => {
    render(<ImportSpecificationsModal systemId="sys-1" />);

    expect(screen.getByText("Import API Group")).toBeInTheDocument();
  });

  it("should title the dialog for a single API when a group is given", () => {
    render(
      <ImportSpecificationsModal systemId="sys-1" specificationGroupId="g1" />,
    );

    expect(screen.getByText("Import API")).toBeInTheDocument();
  });

  it("should honour an explicit groupMode over the inferred one", () => {
    render(
      <ImportSpecificationsModal
        systemId="sys-1"
        specificationGroupId="g1"
        groupMode
      />,
    );

    expect(screen.getByText("Import API Group")).toBeInTheDocument();
  });
});
