/**
 * @jest-environment jsdom
 */
import type { ReactNode } from "react";
import { render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter, useLocation } from "react-router-dom";
import { Services } from "../../src/pages/Services";

jest.mock("../../src/components/services/detail/ServiceSidebar", () => ({
  ServiceSidebar: () => <div data-testid="service-sidebar" />,
}));

jest.mock("../../src/pages/PageWithSidebar", () => ({
  PageWithSidebar: ({
    sidebar,
    children,
  }: {
    sidebar: ReactNode;
    children: ReactNode;
  }) => (
    <div>
      {sidebar}
      {children}
    </div>
  ),
}));

jest.mock("../../src/components/services/ServiceListPage.tsx", () => ({
  ServiceListPage: () => <div data-testid="service-list-page" />,
}));

const HashProbe = () => {
  const { hash } = useLocation();
  return <div data-testid="hash">{hash}</div>;
};

const renderServices = (entry: string) =>
  render(
    <MemoryRouter initialEntries={[entry]}>
      <Services />
      <HashProbe />
    </MemoryRouter>,
  );

describe("Services", () => {
  test("defaults to the external tab and keeps an existing hash", async () => {
    const defaultView = renderServices("/services");

    await waitFor(() =>
      expect(screen.getByTestId("hash")).toHaveTextContent("#external"),
    );
    expect(screen.getByTestId("service-list-page")).toBeInTheDocument();
    expect(screen.getByTestId("service-sidebar")).toBeInTheDocument();

    defaultView.unmount();
    renderServices("/services#mcp");

    expect(screen.getByTestId("hash")).toHaveTextContent("#mcp");
    expect(screen.getByTestId("service-list-page")).toBeInTheDocument();
    expect(screen.getByTestId("service-sidebar")).toBeInTheDocument();
  });
});
