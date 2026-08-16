/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  MemoryRouter,
  Navigate,
  Route,
  Routes,
  useLocation,
} from "react-router";

const mockAvailability = { isAvailable: true, isLoading: false };

jest.mock("../../../src/hooks/useTestingServiceAvailability", () => ({
  useTestingServiceAvailability: () => mockAvailability,
}));

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => <span data-icon={name} />,
}));

import {
  TestingGuard,
  TestingLayout,
  TestingSidebar,
} from "../../../src/pages/testing/TestingLayout";

const CurrentPath = () => (
  <div data-testid="path">{useLocation().pathname}</div>
);

function renderSidebar(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <Routes>
        <Route
          path="/chains/:chainId/testing/*"
          element={
            <>
              <TestingSidebar collapsed={false} />
              <CurrentPath />
            </>
          }
        />
      </Routes>
    </MemoryRouter>,
  );
}

function renderGuardedSubtree(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <Routes>
        <Route path="/chains/:chainId/testing" element={<TestingGuard />}>
          <Route element={<TestingLayout />}>
            <Route index element={<Navigate to="test-cases" replace />} />
            <Route path="test-cases" element={<div>test cases screen</div>} />
          </Route>
        </Route>
        <Route path="/not-found" element={<div>not found page</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("TestingSidebar", () => {
  beforeEach(() => {
    mockAvailability.isAvailable = true;
    mockAvailability.isLoading = false;
  });

  it("should render an entry per testing section", () => {
    renderSidebar("/chains/chain-1/testing/test-cases");

    expect(screen.getByText("Test Cases")).toBeInTheDocument();
    expect(screen.getByText("Endpoint Mocks")).toBeInTheDocument();
    expect(screen.getByText("Test Case Runs")).toBeInTheDocument();
  });

  it("should select the entry the route belongs to", () => {
    const { container } = renderSidebar(
      "/chains/chain-1/testing/endpoint-mocks/mock-1/general",
    );

    const selected = container.querySelector(".ant-menu-item-selected");
    expect(
      within(selected as HTMLElement).getByText("Endpoint Mocks"),
    ).toBeInTheDocument();
  });

  it("should select the first section when the route names none", () => {
    const { container } = renderSidebar("/chains/chain-1/testing");

    const selected = container.querySelector(".ant-menu-item-selected");
    expect(
      within(selected as HTMLElement).getByText("Test Cases"),
    ).toBeInTheDocument();
  });

  it("should navigate within the chain when an entry is clicked", () => {
    renderSidebar("/chains/chain-1/testing/test-cases");

    fireEvent.click(screen.getByText("Test Case Runs"));

    expect(screen.getByTestId("path")).toHaveTextContent(
      "/chains/chain-1/testing/test-case-runs",
    );
  });
});

describe("TestingGuard", () => {
  beforeEach(() => {
    mockAvailability.isAvailable = true;
    mockAvailability.isLoading = false;
  });

  it("should render the section when the service is available", () => {
    renderGuardedSubtree("/chains/chain-1/testing/test-cases");

    expect(screen.getByText("test cases screen")).toBeInTheDocument();
  });

  it("should redirect the index route to the first section", () => {
    renderGuardedSubtree("/chains/chain-1/testing");

    expect(screen.getByText("test cases screen")).toBeInTheDocument();
  });

  it("should redirect away when the service is unavailable", () => {
    mockAvailability.isAvailable = false;

    renderGuardedSubtree("/chains/chain-1/testing/test-cases");

    expect(screen.getByText("not found page")).toBeInTheDocument();
    expect(screen.queryByText("test cases screen")).not.toBeInTheDocument();
  });

  it("should render nothing while availability is still unknown", () => {
    mockAvailability.isAvailable = false;
    mockAvailability.isLoading = true;

    renderGuardedSubtree("/chains/chain-1/testing/test-cases");

    expect(screen.queryByText("test cases screen")).not.toBeInTheDocument();
    expect(screen.queryByText("not found page")).not.toBeInTheDocument();
  });
});
