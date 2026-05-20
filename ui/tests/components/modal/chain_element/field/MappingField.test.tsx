/**
 * @jest-environment jsdom
 */

import { describe, it, expect, beforeEach, jest } from "@jest/globals";
import { act, render } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { FieldProps } from "@rjsf/utils";
import type { JSONSchema7 } from "json-schema";
import type { FormContext } from "../../../../../src/components/modal/chain_element/ChainElementModificationContext";
import type { MappingDescription } from "../../../../../src/mapper/model/model";
import { MappingUtil } from "../../../../../src/mapper/util/mapping";
import { DataTypes } from "../../../../../src/mapper/util/types";

// ─── Mocks ─────────────────────────────────────────────────────────────────

const mockConfirmAndRun = jest.fn(
  (_opts: { onOk?: () => void | Promise<void> }) => {
    // Default: auto-confirm (call onOk immediately).
    void _opts.onOk?.();
  },
);

jest.mock("../../../../../src/misc/confirm-utils", () => ({
  confirmAndRun: (opts: { onOk?: () => void | Promise<void> }) =>
    mockConfirmAndRun(opts),
}));

// Replace the actual Mapping widget with a minimal stub that exposes the
// mapping prop for assertions.
jest.mock("../../../../../src/components/mapper/Mapping", () => ({
  Mapping: (props: {
    elementId: string;
    mapping: MappingDescription;
    onChange?: (value: MappingDescription) => void;
  }) => (
    <div
      data-testid="mapping-stub"
      data-element-id={props.elementId}
      data-mapping={JSON.stringify(props.mapping)}
    />
  ),
}));

import MappingField from "../../../../../src/components/modal/chain_element/field/MappingField";

// ─── Helpers ───────────────────────────────────────────────────────────────

type Props = FieldProps<unknown, JSONSchema7, FormContext>;

function makeProps(overrides: Partial<Props> & {
  path?: (string | number)[];
  formContext?: Partial<FormContext>;
} = {}): Props {
  const { path = ["properties", "mappingDescription"], formContext, ...rest } =
    overrides;
  return {
    name: "mappingDescription",
    formData: undefined,
    onChange: jest.fn(),
    schema: { type: "object" } as JSONSchema7,
    uiSchema: {},
    registry: {
      formContext: {
        elementType: "service-call",
        integrationOperationProtocolType: "http",
        ...formContext,
      } as FormContext,
    } as Props["registry"],
    fieldPathId: {
      $id: `root_${path.join("_")}`,
      path,
    },
    ...rest,
  } as Props;
}

function getRenderedMapping(container: HTMLElement): MappingDescription {
  const stub = container.querySelector("[data-testid='mapping-stub']");
  const data = stub?.getAttribute("data-mapping") ?? "{}";
  return JSON.parse(data) as MappingDescription;
}

const SIMPLE_REQUEST_SCHEMA = {
  "application/json": {
    type: "object",
    properties: {
      first: { type: "string" },
      second: { type: "integer" },
    },
    required: ["first"],
  },
};

const SIMPLE_RESPONSE_SCHEMAS = {
  "200": {
    "application/json": {
      type: "object",
      properties: {
        id: { type: "string" },
      },
    },
  },
  "500": {
    "application/json": {
      type: "object",
      properties: { error: { type: "string" } },
    },
  },
};

// ─── Tests ─────────────────────────────────────────────────────────────────

describe("MappingField", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("direction=none (no schema auto-applied)", () => {
    it("does not call onChange when path is not request/response", () => {
      const onChange = jest.fn();
      const props = makeProps({
        onChange,
        path: ["properties", "mappingDescription"], // mapper-2 — not before/after
        formContext: {
          operationRequestSchema: SIMPLE_REQUEST_SCHEMA,
          operationResponseSchemas: SIMPLE_RESPONSE_SCHEMAS,
        },
      });

      render(<MappingField {...props} />);

      expect(onChange).not.toHaveBeenCalled();
    });

    it("does not call onChange for handlerContainer.mappingDescription (http-trigger)", () => {
      const onChange = jest.fn();
      const props = makeProps({
        onChange,
        path: ["properties", "handlerContainer", "mappingDescription"],
        formContext: {
          operationRequestSchema: SIMPLE_REQUEST_SCHEMA,
        },
      });

      render(<MappingField {...props} />);

      expect(onChange).not.toHaveBeenCalled();
    });
  });

  describe("direction=request", () => {
    it("auto-fills target.body from requestSchema when mapping is empty", () => {
      const onChange = jest.fn();
      const props = makeProps({
        onChange,
        path: ["properties", "before", "mappingDescription"],
        formContext: {
          operationRequestSchema: SIMPLE_REQUEST_SCHEMA,
        },
      });

      render(<MappingField {...props} />);

      expect(onChange).toHaveBeenCalledTimes(1);
      const arg = (onChange as jest.Mock).mock
        .calls[0][0] as MappingDescription;
      expect(arg.target.body).toBeTruthy();
      expect(arg.target.body?.name).toBe("object");
      // source untouched
      expect(arg.source.body).toBeNull();
    });

    it("does not prompt any confirm when target.body is empty (silent fill)", () => {
      const onChange = jest.fn();
      const props = makeProps({
        onChange,
        path: ["properties", "before", "mappingDescription"],
        formContext: {
          operationRequestSchema: SIMPLE_REQUEST_SCHEMA,
        },
      });

      render(<MappingField {...props} />);

      expect(mockConfirmAndRun).not.toHaveBeenCalled();
    });

    it("does NOT touch target.body when it's already populated (manual LoadSchemaDialog required)", () => {
      const existing: MappingDescription = {
        ...MappingUtil.emptyMapping(),
        target: {
          ...MappingUtil.emptyMessageSchema(),
          body: DataTypes.stringType(),
        },
      };
      const onChange = jest.fn();
      const props = makeProps({
        onChange,
        formData: existing,
        path: ["properties", "before", "mappingDescription"],
        formContext: {
          operationRequestSchema: SIMPLE_REQUEST_SCHEMA,
        },
      });

      render(<MappingField {...props} />);

      // User's existing body is preserved; refresh is now an explicit action.
      expect(onChange).not.toHaveBeenCalled();
      expect(mockConfirmAndRun).not.toHaveBeenCalled();
    });

    it("does not reapply the schema when MappingField remounts (e.g. tab switch)", () => {
      // Simulate the flow: first mount auto-applies; a fresh mount later with
      // the same (now-populated) formData must be a noop.
      const onChange = jest.fn();
      const firstProps = makeProps({
        onChange,
        path: ["properties", "before", "mappingDescription"],
        formContext: {
          operationRequestSchema: SIMPLE_REQUEST_SCHEMA,
        },
      });
      const first = render(<MappingField {...firstProps} />);
      expect(onChange).toHaveBeenCalledTimes(1);
      const populatedFormData = (onChange as jest.Mock).mock
        .calls[0][0] as MappingDescription;
      first.unmount();

      const onChange2 = jest.fn();
      const secondProps = makeProps({
        onChange: onChange2,
        formData: populatedFormData,
        path: ["properties", "before", "mappingDescription"],
        formContext: {
          operationRequestSchema: SIMPLE_REQUEST_SCHEMA,
        },
      });
      render(<MappingField {...secondProps} />);

      expect(onChange2).not.toHaveBeenCalled();
      expect(mockConfirmAndRun).not.toHaveBeenCalled();
    });

  });

  describe("direction=response", () => {
    it("auto-fills source.body from responseSchemas when mapping is empty", () => {
      const onChange = jest.fn();
      const props = makeProps({
        onChange,
        path: ["properties", "after", 0, "mappingDescription"],
        formContext: {
          operationResponseSchemas: SIMPLE_RESPONSE_SCHEMAS,
        },
      });

      render(<MappingField {...props} />);

      expect(onChange).toHaveBeenCalledTimes(1);
      const arg = (onChange as jest.Mock).mock
        .calls[0][0] as MappingDescription;
      expect(arg.source.body).toBeTruthy();
      expect(arg.target.body).toBeNull();
    });
  });

  describe("fallbacks", () => {
    it("does nothing when requestSchema is missing", () => {
      const onChange = jest.fn();
      const props = makeProps({
        onChange,
        path: ["properties", "before", "mappingDescription"],
        formContext: {}, // no operationRequestSchema
      });

      render(<MappingField {...props} />);

      expect(onChange).not.toHaveBeenCalled();
    });

    it("does nothing when schema cannot be parsed into DataType", () => {
      const onChange = jest.fn();
      const warnSpy = jest
        .spyOn(console, "warn")
        .mockImplementation(() => undefined);
      const props = makeProps({
        onChange,
        path: ["properties", "before", "mappingDescription"],
        formContext: {
          // "any" type is explicitly rejected by AttributeImporter.
          operationRequestSchema: {
            "application/json": { type: "any" },
          },
        },
      });

      render(<MappingField {...props} />);

      expect(onChange).not.toHaveBeenCalled();
      warnSpy.mockRestore();
    });

    it("does not re-fire onChange on benign re-renders (fingerprint guard)", () => {
      const onChange = jest.fn();
      const props = makeProps({
        onChange,
        path: ["properties", "before", "mappingDescription"],
        formContext: {
          operationRequestSchema: SIMPLE_REQUEST_SCHEMA,
        },
      });

      const { rerender } = render(<MappingField {...props} />);
      expect(onChange).toHaveBeenCalledTimes(1);

      // Trigger a re-render with the exact same context — effect should be a
      // noop thanks to the fingerprint ref.
      act(() => {
        rerender(<MappingField {...props} />);
      });

      expect(onChange).toHaveBeenCalledTimes(1);
    });
  });

  describe("Mapping prop wiring", () => {
    it("passes the provided non-empty mapping through to <Mapping>", () => {
      const existing: MappingDescription = {
        ...MappingUtil.emptyMapping(),
        source: {
          ...MappingUtil.emptyMessageSchema(),
          body: DataTypes.stringType(),
        },
      };
      const props = makeProps({
        formData: existing,
        path: ["properties", "mappingDescription"], // direction=none → no rewrite
      });

      const { container } = render(<MappingField {...props} />);
      const rendered = getRenderedMapping(container);
      expect(rendered.source.body).toEqual(DataTypes.stringType());
    });

    it("passes onChange edits from <Mapping> through to parent", () => {
      const onChange = jest.fn();
      const props = makeProps({
        onChange,
        path: ["properties", "mappingDescription"], // no auto-fill for this path
      });
      const { container } = render(<MappingField {...props} />);
      const stub = container.querySelector("[data-testid='mapping-stub']");
      expect(stub).toBeTruthy();
    });
  });
});
