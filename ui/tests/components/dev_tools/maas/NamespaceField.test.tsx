/**
 * @jest-environment jsdom
 */
import React from "react";
import { describe, test, expect, jest } from "@jest/globals";
import "@testing-library/jest-dom/jest-globals";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { Form } from "antd";
import { NamespaceField } from "../../../../src/components/dev_tools/maas/NamespaceField";

const FormWrapper: React.FC<{ children: React.ReactNode }> = ({ children }) => (
  <Form>{children}</Form>
);

describe("NamespaceField", () => {
  test("renders Namespace label", () => {
    render(
      <FormWrapper>
        <NamespaceField />
      </FormWrapper>,
    );
    expect(screen.getByText("Namespace")).toBeInTheDocument();
  });

  test("renders namespace input", () => {
    render(
      <FormWrapper>
        <NamespaceField />
      </FormWrapper>,
    );
    const input = screen.getByLabelText(/namespace/i);
    expect(input).toBeInTheDocument();
    expect(input).toHaveAttribute("type", "text");
  });

  test("shows error when empty and form is submitted", async () => {
    const FormWithSubmit: React.FC<{ children: React.ReactNode }> = ({
      children,
    }) => (
      <Form onFinish={() => {}}>
        {children}
        <button type="submit">Submit</button>
      </Form>
    );

    render(
      <FormWithSubmit>
        <NamespaceField />
      </FormWithSubmit>,
    );

    fireEvent.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(screen.getByText("Namespace is required")).toBeInTheDocument();
    });
  });

  test("shows error when value is whitespace only", async () => {
    const FormWithSubmit: React.FC<{ children: React.ReactNode }> = ({
      children,
    }) => (
      <Form onFinish={() => {}}>
        {children}
        <button type="submit">Submit</button>
      </Form>
    );

    render(
      <FormWithSubmit>
        <NamespaceField />
      </FormWithSubmit>,
    );

    fireEvent.change(screen.getByLabelText(/namespace/i), {
      target: { value: "   " },
    });
    fireEvent.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(screen.getByText("Namespace cannot be empty")).toBeInTheDocument();
    });
  });

  test("passes validation when value has non-whitespace", async () => {
    const onFinish = jest.fn();
    const FormWithSubmit: React.FC<{ children: React.ReactNode }> = ({
      children,
    }) => (
      <Form onFinish={onFinish}>
        {children}
        <button type="submit">Submit</button>
      </Form>
    );

    render(
      <FormWithSubmit>
        <NamespaceField />
      </FormWithSubmit>,
    );

    fireEvent.change(screen.getByLabelText(/namespace/i), {
      target: { value: "my-namespace" },
    });
    fireEvent.click(screen.getByRole("button", { name: /submit/i }));

    await waitFor(() => {
      expect(onFinish).toHaveBeenCalled();
    });
  });
});
