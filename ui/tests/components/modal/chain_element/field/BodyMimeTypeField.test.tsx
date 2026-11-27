/**
 * @jest-environment jsdom
 */
import type { FieldProps, RJSFSchema } from "@rjsf/utils";
import { fireEvent, render, screen } from "@testing-library/react";
import BodyMimeTypeField from "../../../../../src/components/modal/chain_element/field/BodyMimeTypeField";
import type { FormContext } from "../../../../../src/components/modal/chain_element/ChainElementModificationContext";
import { querySelectOption } from "../../../../helpers/antdSelect";

it("should suggest multipart MIME types and accept custom types", () => {
  const updateContext = jest.fn();
  const entry = { name: "payload", value: "{}", mimeType: "text/plain" };
  render(
    <BodyMimeTypeField
      {...({
        formData: "multipart/form-data",
        registry: {
          formContext: { bodyFormData: [entry], updateContext },
        },
      } as unknown as FieldProps<string | undefined, RJSFSchema, FormContext>)}
    />,
  );

  const mimeType = screen.getByDisplayValue("text/plain");

  fireEvent.change(mimeType, { target: { value: "APPLICATION/J" } });
  const jsonOption = querySelectOption("application/json");
  expect(jsonOption).toBeInTheDocument();
  expect(querySelectOption("text/plain")).toBeNull();
  fireEvent.click(jsonOption!);
  expect(updateContext).toHaveBeenLastCalledWith({
    bodyFormData: [{ ...entry, mimeType: "application/json" }],
  });

  fireEvent.change(mimeType, {
    target: { value: "application/vnd.acme+json" },
  });
  expect(updateContext).toHaveBeenLastCalledWith({
    bodyFormData: [{ ...entry, mimeType: "application/vnd.acme+json" }],
  });
});

it("should let users select a MIME type for a multipart part with no MIME type", () => {
  const updateContext = jest.fn();
  const entry = { name: "payload", value: "{}" };
  render(
    <BodyMimeTypeField
      {...({
        formData: "multipart/form-data",
        registry: {
          formContext: { bodyFormData: [entry], updateContext },
        },
      } as unknown as FieldProps<string | undefined, RJSFSchema, FormContext>)}
    />,
  );

  const mimeType = screen.getByRole("combobox", { name: "MIME Type" });
  expect(mimeType).toHaveValue("");
  fireEvent.change(mimeType, { target: { value: "image/p" } });
  const pngOption = querySelectOption("image/png");
  expect(pngOption).toBeInTheDocument();
  fireEvent.click(pngOption!);

  expect(updateContext).toHaveBeenLastCalledWith({
    bodyFormData: [{ ...entry, mimeType: "image/png" }],
  });
});
