/**
 * @jest-environment jsdom
 */
import { describe, expect, it, jest } from "@jest/globals";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom/jest-globals";
import { MemoryRouter, useLocation } from "react-router-dom";
import { DevToolsSidebar } from "../../../src/components/dev_tools/DevToolsSidebar";

jest.mock("../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => <span data-icon={name} />,
}));

function CurrentLocation() {
  const { pathname } = useLocation();
  return <output aria-label="Current route">{pathname}</output>;
}

function renderSidebar(pathname: string) {
  return render(
    <MemoryRouter initialEntries={[pathname]}>
      <DevToolsSidebar collapsed={false} />
      <CurrentLocation />
    </MemoryRouter>,
  );
}

describe("DevToolsSidebar", () => {
  it("should select Kafka and expand MaaS when opened on the Kafka route", () => {
    renderSidebar("/devtools/maas/kafka");

    expect(screen.getByRole("menuitem", { name: "Maas" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );
    expect(screen.getByRole("menuitem", { name: "Kafka" })).toHaveClass(
      "ant-menu-item-selected",
    );
    expect(screen.getByRole("menuitem", { name: "RabbitMQ" })).not.toHaveClass(
      "ant-menu-item-selected",
    );
  });

  it("should navigate to RabbitMQ and select it when clicked", async () => {
    renderSidebar("/devtools/maas/kafka");

    fireEvent.click(screen.getByRole("menuitem", { name: "RabbitMQ" }));

    await waitFor(() => {
      expect(
        screen.getByRole("status", { name: "Current route" }),
      ).toHaveTextContent("/devtools/maas/rabbitmq");
    });
    expect(screen.getByRole("menuitem", { name: "RabbitMQ" })).toHaveClass(
      "ant-menu-item-selected",
    );
    expect(screen.getByRole("menuitem", { name: "Kafka" })).not.toHaveClass(
      "ant-menu-item-selected",
    );
  });

  it("should navigate to Diagnostic and close MaaS when clicked", async () => {
    renderSidebar("/devtools/maas/kafka");

    fireEvent.click(screen.getByRole("menuitem", { name: "Diagnostic" }));

    await waitFor(() => {
      expect(
        screen.getByRole("status", { name: "Current route" }),
      ).toHaveTextContent("/devtools/diagnostic/validations");
      expect(screen.getByRole("menuitem", { name: "Maas" })).toHaveAttribute(
        "aria-expanded",
        "false",
      );
    });
    expect(screen.getByRole("menuitem", { name: "Diagnostic" })).toHaveClass(
      "ant-menu-item-selected",
    );
  });

  it("should close the MaaS submenu when the sidebar collapses", async () => {
    const { rerender } = renderSidebar("/devtools/maas/kafka");
    expect(screen.getByRole("menuitem", { name: "Maas" })).toHaveAttribute(
      "aria-expanded",
      "true",
    );

    rerender(
      <MemoryRouter initialEntries={["/devtools/maas/kafka"]}>
        <DevToolsSidebar collapsed={true} />
        <CurrentLocation />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(screen.getByRole("menuitem", { name: "Maas" })).toHaveAttribute(
        "aria-expanded",
        "false",
      );
    });
    expect(
      screen.getByRole("status", { name: "Current route" }),
    ).toHaveTextContent("/devtools/maas/kafka");
  });
});
