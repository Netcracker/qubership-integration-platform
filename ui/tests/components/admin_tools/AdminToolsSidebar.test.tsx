/**
 * @jest-environment jsdom
 */
import { render, screen, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter } from "react-router-dom";

const mockAvailability = { isAvailable: true, isLoading: false };

jest.mock("../../../src/hooks/useTestingServiceAvailability", () => ({
  useTestingServiceAvailability: () => mockAvailability,
}));

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => <span data-icon={name} />,
}));

import { AdminToolsSidebar } from "../../../src/components/admin_tools/AdminToolsSidebar";

function renderSidebar(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <AdminToolsSidebar collapsed={false} />
    </MemoryRouter>,
  );
}

describe("AdminToolsSidebar", () => {
  beforeEach(() => {
    mockAvailability.isAvailable = true;
    mockAvailability.isLoading = false;
  });

  it("should show the Testing group when the service is available", () => {
    renderSidebar("/admintools/domains");

    expect(screen.getByText("Testing")).toBeInTheDocument();
  });

  it("should hide the Testing group when the service is unavailable", () => {
    mockAvailability.isAvailable = false;

    renderSidebar("/admintools/domains");

    expect(screen.queryByText("Testing")).not.toBeInTheDocument();
  });

  it("should open the Testing submenu on a testing route", () => {
    renderSidebar("/admintools/testing/test-runs");

    expect(screen.getByText("Test Cases")).toBeInTheDocument();
    expect(screen.getByText("Endpoint Mocks")).toBeInTheDocument();
    expect(screen.getByText("Test Runs")).toBeInTheDocument();
  });

  it("should keep the Variables submenu opening on a variables route", () => {
    const { container } = renderSidebar("/admintools/variables/secured");

    const selected = container.querySelector(".ant-menu-item-selected");
    expect(
      within(selected as HTMLElement).getByText("Secured"),
    ).toBeInTheDocument();
  });

  it("should leave both submenus closed on an unrelated route", () => {
    renderSidebar("/admintools/audit");

    expect(screen.queryByText("Test Runs")).not.toBeInTheDocument();
    expect(screen.queryByText("Secured")).not.toBeInTheDocument();
  });
});
