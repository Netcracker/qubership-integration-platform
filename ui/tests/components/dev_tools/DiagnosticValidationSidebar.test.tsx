/**
 * @jest-environment jsdom
 */

import {
  describe,
  it,
  expect,
  beforeEach,
  afterEach,
  jest,
} from "@jest/globals";
import { fireEvent, render, screen, within } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  DiagnosticValidation,
  ValidationEntityType,
  ValidationImplementationType,
  ValidationSeverity,
  ValidationState,
} from "../../../src/api/apiTypes";

Object.defineProperty(window, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation(((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })) as (...args: unknown[]) => unknown),
});

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

import { DiagnosticValidationSidebar } from "../../../src/components/dev_tools/DiagnosticValidationSidebar";

function makeValidation(
  overrides: Partial<DiagnosticValidation> = {},
): DiagnosticValidation {
  return {
    id: "val-1",
    title: "Test Validation",
    description: "Test description",
    suggestion: "Test suggestion",
    entityType: ValidationEntityType.CHAIN_ELEMENT,
    implementationType: ValidationImplementationType.BUILT_IN,
    severity: ValidationSeverity.ERROR,
    properties: {},
    alertsCount: 3,
    chainEntities: [],
    status: { state: ValidationState.OK, startedWhen: "2026-01-01T00:00:00Z" },
    ...overrides,
  };
}

function renderSidebar(
  value: DiagnosticValidation | undefined,
  open = true,
  onClose: () => void = jest.fn(),
) {
  return render(
    <DiagnosticValidationSidebar value={value} open={open} onClose={onClose} />,
  );
}

let pageContent: HTMLElement;

describe("DiagnosticValidationSidebar", () => {
  beforeEach(() => {
    pageContent = document.createElement("div");
    pageContent.className = "page-content";
    document.body.appendChild(pageContent);
  });

  afterEach(() => {
    pageContent.remove();
  });

  it("should render nothing when closed", () => {
    renderSidebar(makeValidation(), false);

    expect(screen.queryByText("Validation Details")).not.toBeInTheDocument();
  });

  it("should render the drawer inside the page content when open", () => {
    renderSidebar(makeValidation());

    const title = screen.getByText("Validation Details");
    expect(pageContent).toContainElement(title);
  });

  it("should render labeled general info of the validation", () => {
    renderSidebar(makeValidation());

    expect(screen.getByText("ID")).toBeInTheDocument();
    expect(screen.getByText("val-1")).toBeInTheDocument();
    expect(screen.getByText("Name")).toBeInTheDocument();
    expect(screen.getByText("Test Validation")).toBeInTheDocument();
    expect(screen.getByText("Description")).toBeInTheDocument();
    expect(screen.getByText("Test description")).toBeInTheDocument();
    expect(screen.getByText("Hint")).toBeInTheDocument();
    expect(screen.getByText("Test suggestion")).toBeInTheDocument();
    expect(screen.getByText("Alerts")).toBeInTheDocument();
    expect(screen.getByText("3")).toBeInTheDocument();
  });

  it("should omit fields that have no label", () => {
    renderSidebar(makeValidation());

    expect(screen.queryByText(ValidationEntityType.CHAIN_ELEMENT)).toBeNull();
    expect(screen.queryByText("[object Object]")).toBeNull();
  });

  it("should show built-in source and error tag when validation is built-in error", () => {
    renderSidebar(makeValidation());

    expect(screen.getByText("Built-in")).toBeInTheDocument();
    const tag = screen.getByText("Error").closest(".ant-tag");
    expect(tag).toHaveClass("ant-tag-red");
  });

  it("should show custom source and warning tag when validation is plugin warning", () => {
    renderSidebar(
      makeValidation({
        implementationType: ValidationImplementationType.PLUGIN,
        severity: ValidationSeverity.WARNING,
      }),
    );

    expect(screen.getByText("Custom")).toBeInTheDocument();
    const tag = screen.getByText("Warning").closest(".ant-tag");
    expect(tag).toHaveClass("ant-tag-gold");
  });

  it("should render parameters as a key-value table", () => {
    renderSidebar(
      makeValidation({ properties: { timeout: 30, mode: "strict" } }),
    );

    expect(screen.getByText("parameters")).toBeInTheDocument();
    const table = pageContent.querySelector(
      ".details-sidebar-kv-table",
    ) as HTMLElement;
    expect(table).toBeInTheDocument();
    expect(within(table).getByText("timeout")).toBeInTheDocument();
    expect(within(table).getByText("30")).toBeInTheDocument();
    expect(within(table).getByText("mode")).toBeInTheDocument();
    expect(within(table).getByText("strict")).toBeInTheDocument();
  });

  it("should render an empty drawer when no validation is selected", () => {
    renderSidebar(undefined);

    expect(screen.getByText("Validation Details")).toBeInTheDocument();
    expect(screen.queryByText("ID")).toBeNull();
  });

  it("should call onClose when the close button is clicked", () => {
    const onClose = jest.fn();
    renderSidebar(makeValidation(), true, onClose);

    fireEvent.click(screen.getByRole("button", { name: /close/i }));

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
