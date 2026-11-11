/**
 * @jest-environment jsdom
 */
import { describe, test, expect, jest } from "@jest/globals";
import "@testing-library/jest-dom/jest-globals";
import { render, screen, fireEvent } from "@testing-library/react";
import { MaasPageHeader } from "../../../../src/components/dev_tools/maas/MaasPageHeader";

jest.mock("../../../../src/components/dev_tools/maas/Maas.module.css", () => ({
  __esModule: true,
  default: { header: "header", titleHeader: "titleHeader" },
}));

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

const getExportButton = () =>
  screen.getByRole("button", { name: "Export declarative file" });

describe("MaasPageHeader", () => {
  test("renders title", () => {
    render(
      <MaasPageHeader
        title="Kafka"
        exportInProgress={false}
        isFormValid={true}
        onExport={jest.fn()}
      />,
    );
    expect(screen.getByRole("heading", { name: "Kafka" })).toBeInTheDocument();
  });

  test("calls onExport when Export button is clicked", () => {
    const onExport = jest.fn();
    render(
      <MaasPageHeader
        title="Test"
        exportInProgress={false}
        isFormValid={true}
        onExport={onExport}
      />,
    );
    fireEvent.click(getExportButton());
    expect(onExport).toHaveBeenCalledTimes(1);
  });

  test("Export button is disabled when isFormValid is false", () => {
    render(
      <MaasPageHeader
        title="Test"
        exportInProgress={false}
        isFormValid={false}
        onExport={jest.fn()}
      />,
    );
    expect(getExportButton()).toBeDisabled();
  });

  test("Export button is disabled when exportInProgress is true", () => {
    render(
      <MaasPageHeader
        title="Test"
        exportInProgress={true}
        isFormValid={true}
        onExport={jest.fn()}
      />,
    );
    expect(getExportButton()).toBeDisabled();
  });
});
