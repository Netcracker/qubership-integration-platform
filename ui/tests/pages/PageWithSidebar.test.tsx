/**
 * @jest-environment jsdom
 */
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { PageWithSidebar } from "../../src/pages/PageWithSidebar";

const Sidebar = ({ collapsed }: { collapsed?: boolean }) => (
  <div data-testid="sidebar">{String(collapsed)}</div>
);

describe("PageWithSidebar", () => {
  afterEach(() => {
    jest.restoreAllMocks();
  });

  test("should pass collapsed to the sidebar when it is a component", () => {
    render(<PageWithSidebar sidebar={<Sidebar />}>content</PageWithSidebar>);

    expect(screen.getByTestId("sidebar")).toHaveTextContent("false");
  });

  test("should not pass collapsed when the sidebar is a fragment", () => {
    const consoleError = jest.spyOn(console, "error");

    render(
      <PageWithSidebar
        sidebar={
          <>
            <span>search</span>
            <span>tree</span>
          </>
        }
      >
        content
      </PageWithSidebar>,
    );

    expect(screen.getByText("tree")).toBeInTheDocument();
    expect(consoleError).not.toHaveBeenCalledWith(
      expect.stringContaining("React.Fragment"),
      expect.anything(),
    );
  });
});
