/**
 * @jest-environment jsdom
 */
import React from "react";
import { describe, test, expect, jest } from "@jest/globals";
import "@testing-library/jest-dom/jest-globals";
import { render, screen, fireEvent } from "@testing-library/react";
import { MaasFormActions } from "../../../../src/components/dev_tools/maas/MaasFormActions";

jest.mock("../../../../src/components/dev_tools/maas/Maas.module.css", () => ({
  __esModule: true,
  default: { actionsContainer: "actionsContainer" },
}));

describe("MaasFormActions", () => {
  test("calls onCreate when Create button is clicked", () => {
    const onCreate = jest.fn();
    render(
      <MaasFormActions
        createInProgress={false}
        isFormValid={true}
        onCreate={onCreate}
        onReset={jest.fn()}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /create/i }));
    expect(onCreate).toHaveBeenCalledTimes(1);
  });

  test("calls onReset when Reset button is clicked", () => {
    const onReset = jest.fn();
    render(
      <MaasFormActions
        createInProgress={false}
        isFormValid={true}
        onCreate={jest.fn()}
        onReset={onReset}
      />,
    );
    fireEvent.click(screen.getByRole("button", { name: /reset/i }));
    expect(onReset).toHaveBeenCalledTimes(1);
  });

  test("Create button is disabled when isFormValid is false", () => {
    render(
      <MaasFormActions
        createInProgress={false}
        isFormValid={false}
        onCreate={jest.fn()}
        onReset={jest.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: /create/i })).toBeDisabled();
  });

  test("Create and Reset buttons are disabled when createInProgress is true", () => {
    render(
      <MaasFormActions
        createInProgress={true}
        isFormValid={true}
        onCreate={jest.fn()}
        onReset={jest.fn()}
      />,
    );
    expect(screen.getByRole("button", { name: /create/i })).toBeDisabled();
    expect(screen.getByRole("button", { name: /reset/i })).toBeDisabled();
  });
});
