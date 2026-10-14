/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter, useNavigate } from "react-router-dom";

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

/** Drives a route change from outside the menu, the way a page link would. */
const NavigateTo = ({ to }: { to: string }) => {
  const navigate = useNavigate();
  return (
    <button type="button" onClick={() => void navigate(to)}>
      navigate
    </button>
  );
};

function submenuTitle(label: string): HTMLElement {
  return screen.getByText(label).closest('[role="menuitem"]') as HTMLElement;
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

  it("should open the Testing submenu when availability resolves after mount", () => {
    mockAvailability.isAvailable = false;
    mockAvailability.isLoading = true;
    const sidebar = () => (
      <MemoryRouter initialEntries={["/admintools/testing/test-runs"]}>
        <AdminToolsSidebar collapsed={false} />
      </MemoryRouter>
    );

    const { rerender } = render(sidebar());
    expect(screen.queryByText("Testing")).not.toBeInTheDocument();

    mockAvailability.isAvailable = true;
    mockAvailability.isLoading = false;
    rerender(sidebar());

    expect(screen.getByText("Test Runs")).toBeInTheDocument();
  });

  it("should keep a submenu closed when the user closed it before navigating", () => {
    render(
      <MemoryRouter initialEntries={["/admintools/variables/common"]}>
        <AdminToolsSidebar collapsed={false} />
        <NavigateTo to="/admintools/variables/secured" />
      </MemoryRouter>,
    );
    expect(submenuTitle("Variables")).toHaveAttribute("aria-expanded", "true");

    fireEvent.click(screen.getByText("Variables"));
    expect(submenuTitle("Variables")).toHaveAttribute("aria-expanded", "false");

    fireEvent.click(screen.getByRole("button", { name: "navigate" }));

    expect(submenuTitle("Variables")).toHaveAttribute("aria-expanded", "false");
  });

  it("should open no submenu when the sidebar is collapsed", () => {
    const { container } = render(
      <MemoryRouter initialEntries={["/admintools/variables/common"]}>
        <AdminToolsSidebar collapsed={true} />
      </MemoryRouter>,
    );

    expect(container.querySelector(".ant-menu-submenu-open")).toBeNull();
  });
});
