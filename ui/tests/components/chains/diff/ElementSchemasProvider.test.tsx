/**
 * @jest-environment jsdom
 */

import { render } from "@testing-library/react";
import "@testing-library/jest-dom";
import { useContext } from "react";
import type { JSONSchema7 } from "json-schema";

const mockErrorWithDetails = jest.fn();
const mockGetSchemaRawByElementType = jest.fn();
const mockYamlLoad = jest.fn();

jest.mock("../../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => ({ errorWithDetails: mockErrorWithDetails }),
}));

jest.mock(
  "../../../../src/components/modal/chain_element/chainElementSchemaModules.ts",
  () => ({
    getSchemaRawByElementType: (type: string) =>
      mockGetSchemaRawByElementType(type),
  }),
);

jest.mock("js-yaml", () => ({
  load: (...args: unknown[]) => mockYamlLoad(...args),
}));

import {
  ElementSchemasContext,
  ElementSchemasProvider,
  type ElementSchemasContextProps,
} from "../../../../src/components/chains/diff/ElementSchemasProvider";

let capturedContext: ElementSchemasContextProps | null = null;
const ContextCapture = () => {
  capturedContext = useContext(ElementSchemasContext);
  return null;
};

describe("ElementSchemasProvider", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    capturedContext = null;
    mockGetSchemaRawByElementType.mockReturnValue(undefined);
  });

  it("should render children", () => {
    const { getByTestId } = render(
      <ElementSchemasProvider>
        <div data-testid="child" />
      </ElementSchemasProvider>,
    );

    expect(getByTestId("child")).toBeInTheDocument();
  });

  it("should provide a getSchema function through context", () => {
    render(
      <ElementSchemasProvider>
        <ContextCapture />
      </ElementSchemasProvider>,
    );

    expect(typeof capturedContext!.getSchema).toBe("function");
  });

  it("should return the parsed schema when the type exists in schema modules", () => {
    const rawYaml = "type: object";
    const parsedSchema: JSONSchema7 = { type: "object" };
    mockGetSchemaRawByElementType.mockImplementation((type: string) =>
      type === "http-trigger-t1" ? rawYaml : undefined,
    );
    mockYamlLoad.mockImplementation((raw: unknown) =>
      raw === rawYaml ? parsedSchema : undefined,
    );

    render(
      <ElementSchemasProvider>
        <ContextCapture />
      </ElementSchemasProvider>,
    );

    expect(capturedContext!.getSchema("http-trigger-t1")).toBe(parsedSchema);
    expect(mockGetSchemaRawByElementType).toHaveBeenCalledWith(
      "http-trigger-t1",
    );
  });

  it("should return undefined when the type is not in schema modules", () => {
    mockYamlLoad.mockReturnValue(undefined);

    render(
      <ElementSchemasProvider>
        <ContextCapture />
      </ElementSchemasProvider>,
    );

    expect(capturedContext!.getSchema("nonexistent-type-t2")).toBeUndefined();
  });

  it("should cache the schema and not parse the same type twice", () => {
    const rawYaml = "type: string";
    const parsedSchema: JSONSchema7 = { type: "string" };
    mockGetSchemaRawByElementType.mockImplementation((type: string) =>
      type === "cached-type-t3" ? rawYaml : undefined,
    );
    mockYamlLoad.mockReturnValue(parsedSchema);

    render(
      <ElementSchemasProvider>
        <ContextCapture />
      </ElementSchemasProvider>,
    );

    capturedContext!.getSchema("cached-type-t3");
    capturedContext!.getSchema("cached-type-t3");

    expect(mockYamlLoad).toHaveBeenCalledTimes(1);
  });

  it("should call errorWithDetails and return undefined when yaml parsing throws", () => {
    const parseError = new Error("YAML parse error");
    mockGetSchemaRawByElementType.mockImplementation((type: string) =>
      type === "broken-t4" ? "bad-yaml" : undefined,
    );
    mockYamlLoad.mockImplementation(() => {
      throw parseError;
    });

    render(
      <ElementSchemasProvider>
        <ContextCapture />
      </ElementSchemasProvider>,
    );

    const result = capturedContext!.getSchema("broken-t4");

    expect(mockErrorWithDetails).toHaveBeenCalledWith(
      "Failed to parse element schema",
      "",
      parseError,
    );
    expect(result).toBeUndefined();
  });
});
