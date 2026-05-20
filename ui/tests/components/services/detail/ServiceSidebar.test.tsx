/**
 * @jest-environment jsdom
 */
import React from "react";
import { describe, it, expect } from "@jest/globals";
import { render, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter } from "react-router-dom";
import { ServiceSidebar } from "../../../../src/components/services/detail/ServiceSidebar";

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => <span data-icon={name} />,
}));

describe("ServiceSidebar", () => {
  it("selects External when hash is empty", () => {
    const { container } = render(
      <MemoryRouter initialEntries={["/services"]}>
        <ServiceSidebar collapsed={false} />
      </MemoryRouter>,
    );
    const selected = container.querySelector(".ant-menu-item-selected");
    expect(selected).toBeInTheDocument();
    expect(
      within(selected as HTMLElement).getByText("External"),
    ).toBeInTheDocument();
  });

  it("selects Implemented when hash is set", () => {
    const { container } = render(
      <MemoryRouter initialEntries={["/services#implemented"]}>
        <ServiceSidebar collapsed={false} />
      </MemoryRouter>,
    );
    const selected = container.querySelector(".ant-menu-item-selected");
    expect(selected).toBeInTheDocument();
    expect(
      within(selected as HTMLElement).getByText("Implemented"),
    ).toBeInTheDocument();
  });
});
