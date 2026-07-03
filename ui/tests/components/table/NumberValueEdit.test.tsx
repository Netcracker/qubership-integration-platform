/** @jest-environment jsdom */
import { describe, it, expect, jest } from "@jest/globals";
import type { ReactNode } from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Form } from "antd";
import type { FormRule } from "antd";
import {
  InlineEditContext,
  type InlineEditContextProps,
} from "../../../src/components/InlineEdit";
import {
  NumberValueEdit,
  type NumberValueEditorProps,
} from "../../../src/components/table/NumberValueEdit";

type FormValues = { value?: string };

type HarnessProps = {
  toggle?: () => void;
  onFinish?: (values: FormValues) => void;
  initialValues?: FormValues;
  rules?: FormRule[];
  inputProps?: NumberValueEditorProps["inputProps"];
  children?: ReactNode;
};

function Harness({
  toggle,
  onFinish,
  initialValues,
  rules,
  inputProps,
  children,
}: HarnessProps) {
  const contextValue: InlineEditContextProps | null = toggle
    ? { toggle }
    : null;
  return (
    <InlineEditContext.Provider value={contextValue}>
      <Form<FormValues> initialValues={initialValues} onFinish={onFinish}>
        <NumberValueEdit name="value" rules={rules} inputProps={inputProps} />
        {children}
      </Form>
    </InlineEditContext.Provider>
  );
}

describe("NumberValueEdit", () => {
  it("should render a spinbutton input when placed inside a form", () => {
    render(<Harness initialValues={{ value: "7" }} />);

    const input = screen.getByRole("spinbutton");
    expect(input).toBeInTheDocument();
    expect(input).toHaveValue("7");
  });

  it("should show the default required message when submitting an empty value without custom rules", async () => {
    render(<Harness initialValues={{}} />);

    fireEvent.keyDown(screen.getByRole("spinbutton"), { key: "Enter" });

    expect(await screen.findByText("Value is required.")).toBeInTheDocument();
  });

  it("should call form onFinish with the value when Enter is pressed on a valid input", async () => {
    const onFinish = jest.fn<(values: FormValues) => void>();
    render(<Harness initialValues={{ value: "42" }} onFinish={onFinish} />);

    fireEvent.keyDown(screen.getByRole("spinbutton"), { key: "Enter" });

    await waitFor(() => {
      expect(onFinish).toHaveBeenCalledWith({ value: "42" });
    });
  });

  it("should call the inline edit toggle when blur moves focus outside the input", () => {
    const toggle = jest.fn<() => void>();
    render(
      <Harness toggle={toggle} initialValues={{ value: "1" }}>
        <button type="button">outside</button>
      </Harness>,
    );

    const outside = screen.getByRole("button", { name: "outside" });
    fireEvent.blur(screen.getByRole("spinbutton"), { relatedTarget: outside });

    expect(toggle).toHaveBeenCalledTimes(1);
  });

  it("should not call the inline edit toggle when blur moves focus inside the input", () => {
    const toggle = jest.fn<() => void>();
    render(<Harness toggle={toggle} initialValues={{ value: "1" }} />);

    const input = screen.getByRole("spinbutton");
    // The related target sits inside the InputNumber's nativeElement wrapper.
    fireEvent.blur(input, { relatedTarget: input });

    expect(toggle).not.toHaveBeenCalled();
  });

  it("should not call the inline edit toggle when blur has no related target", () => {
    const toggle = jest.fn<() => void>();
    render(<Harness toggle={toggle} initialValues={{ value: "1" }} />);

    fireEvent.blur(screen.getByRole("spinbutton"));

    expect(toggle).not.toHaveBeenCalled();
  });

  it("should not throw on blur outside the input when no inline edit context is present", () => {
    render(
      <Harness initialValues={{ value: "1" }}>
        <button type="button">outside</button>
      </Harness>,
    );

    const outside = screen.getByRole("button", { name: "outside" });
    const input = screen.getByRole("spinbutton");
    expect(() =>
      fireEvent.blur(input, { relatedTarget: outside }),
    ).not.toThrow();
  });

  it("should show a custom required message when custom rules are provided", async () => {
    render(
      <Harness
        initialValues={{}}
        rules={[{ required: true, message: "Custom number is required." }]}
      />,
    );

    fireEvent.keyDown(screen.getByRole("spinbutton"), { key: "Enter" });

    expect(
      await screen.findByText("Custom number is required."),
    ).toBeInTheDocument();
    expect(screen.queryByText("Value is required.")).not.toBeInTheDocument();
  });

  it("should disable the input when inputProps disabled is passed", () => {
    render(
      <Harness
        initialValues={{ value: "1" }}
        inputProps={{ disabled: true }}
      />,
    );

    expect(screen.getByRole("spinbutton")).toBeDisabled();
  });
});
