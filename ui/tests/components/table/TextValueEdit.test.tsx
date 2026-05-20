/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Form } from "antd";

jest.mock("../../../src/components/InlineEdit.module.css", () => ({
  __esModule: true,
  default: {},
}));

Object.defineProperty(globalThis, "matchMedia", {
  writable: true,
  value: jest.fn().mockImplementation((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: jest.fn(),
    removeListener: jest.fn(),
    addEventListener: jest.fn(),
    removeEventListener: jest.fn(),
    dispatchEvent: jest.fn(),
  })),
});

import { TextValueEdit } from "../../../src/components/table/TextValueEdit";

function TestForm({
  children,
  initialValues = { field: "initial" },
}: {
  children: React.ReactNode;
  initialValues?: Record<string, string>;
}) {
  return (
    <Form initialValues={initialValues} onFinish={jest.fn()}>
      {children}
    </Form>
  );
}

describe("TextValueEdit", () => {
  it("renders input without suffixAction", () => {
    render(
      <TestForm>
        <TextValueEdit name="field" />
      </TestForm>,
    );
    expect(screen.getByDisplayValue("initial")).toBeInTheDocument();
  });

  it("renders InputWithSuffix when suffixAction is provided", () => {
    render(
      <TestForm>
        <TextValueEdit
          name="field"
          suffixAction={<button type="button">Clear</button>}
        />
      </TestForm>,
    );
    expect(screen.getByDisplayValue("initial")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Clear" })).toBeInTheDocument();
  });

  it("calls onChange when typing", () => {
    render(
      <TestForm>
        <TextValueEdit name="field" />
      </TestForm>,
    );
    const input = screen.getByDisplayValue("initial");
    fireEvent.change(input, { target: { value: "updated" } });
    expect(input).toHaveValue("updated");
  });

  it("uses custom rules when provided", () => {
    render(
      <TestForm initialValues={{ field: "" }}>
        <TextValueEdit
          name="field"
          rules={[{ required: true, message: "Custom required" }]}
        />
      </TestForm>,
    );
    expect(screen.getByRole("textbox")).toBeInTheDocument();
  });
});
