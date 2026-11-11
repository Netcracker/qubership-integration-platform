/**
 * @jest-environment jsdom
 */

import { describe, it, expect, jest } from "@jest/globals";
import "@testing-library/jest-dom";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import { Form, Input, type FormInstance } from "antd";
import { useInitialDomains } from "../../src/hooks/useInitialDomains.ts";
import type { Domain } from "../../src/components/SelectDomains.tsx";
import { DomainType } from "../../src/api/apiTypes.ts";

jest.mock("antd", () =>
  require("tests/helpers/antdMockWithLightweightTable").antdMockWithLightweightTable(),
);

type FormShape = { domains: Domain[] };

const first: Domain[] = [{ name: "first", type: DomainType.CLASSIC }];
const second: Domain[] = [{ name: "second", type: DomainType.CLASSIC }];

function Harness({
  initial,
  onForm,
}: {
  initial?: Domain[];
  onForm: (form: FormInstance<FormShape>) => void;
}) {
  const [form] = Form.useForm<FormShape>();
  onForm(form);
  useInitialDomains(form, initial);
  return (
    <Form form={form}>
      <Form.Item name="domains">
        <Input aria-label="domains" />
      </Form.Item>
    </Form>
  );
}

describe("useInitialDomains", () => {
  it("should apply initialDomains on mount", async () => {
    let form!: FormInstance<FormShape>;
    render(<Harness initial={first} onForm={(f) => (form = f)} />);

    await waitFor(() => expect(form.getFieldValue("domains")).toEqual(first));
  });

  it("should ignore later initialDomains while the field is untouched", async () => {
    let form!: FormInstance<FormShape>;
    const { rerender } = render(
      <Harness initial={first} onForm={(f) => (form = f)} />,
    );

    await waitFor(() => expect(form.getFieldValue("domains")).toEqual(first));

    rerender(<Harness initial={second} onForm={(f) => (form = f)} />);

    await waitFor(() => expect(form.getFieldValue("domains")).toEqual(first));
  });

  it("should keep the user value once the field is touched", async () => {
    let form!: FormInstance<FormShape>;
    const { rerender } = render(
      <Harness initial={first} onForm={(f) => (form = f)} />,
    );

    await waitFor(() => expect(form.getFieldValue("domains")).toEqual(first));
    fireEvent.change(screen.getByLabelText("domains"), {
      target: { value: "user-picked" },
    });

    rerender(<Harness initial={second} onForm={(f) => (form = f)} />);

    await waitFor(() =>
      expect(form.getFieldValue("domains")).toBe("user-picked"),
    );
  });
});
