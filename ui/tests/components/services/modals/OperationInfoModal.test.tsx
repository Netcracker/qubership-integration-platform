/**
 * @jest-environment jsdom
 */

Object.defineProperty(window, "matchMedia", {
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

import { describe, it, expect } from "@jest/globals";
import { render, screen, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { OperationInfoModal } from "../../../../src/components/services/modals/OperationInfoModal";
import type {
  OperationInfo,
  SystemOperation,
} from "../../../../src/api/apiTypes";

jest.mock("../../../../src/hooks/useSyntaxHighlighterTheme", () => ({
  useSyntaxHighlighterTheme: () => ({}),
}));

function makeOperationInfo(
  overrides: Partial<OperationInfo> = {},
): OperationInfo {
  return {
    id: "op-1",
    specification: { openapi: "3.0.0" },
    requestSchema: { type: "object" },
    responseSchemas: { "200": { type: "object" } },
    ...overrides,
  };
}

function makeOperation(
  overrides: Partial<SystemOperation> = {},
): SystemOperation {
  return {
    id: "op-1",
    name: "Get widget",
    method: "GET",
    path: "/widgets/{id}",
    modelId: "spec-1",
    chains: [],
    ...overrides,
  };
}

describe("OperationInfoModal", () => {
  it("renders specification, request schema, and response schemas from getOperationInfo", () => {
    const operationInfo = makeOperationInfo();
    render(
      <OperationInfoModal
        visible={true}
        onClose={jest.fn()}
        operationInfo={operationInfo}
      />,
    );

    expect(screen.getByText("Specification")).toBeInTheDocument();
    expect(screen.getByText("Request schema")).toBeInTheDocument();
    expect(screen.getByText("Response schemas")).toBeInTheDocument();

    // The modal renders through an antd Modal portal, so assert on document.body
    // (Prism splits the JSON into per-token spans, so match the aggregated text).
    // Tabs lazy-render their pane on first activation, so switch tabs before asserting.
    expect(document.body.textContent).toContain('"openapi"');

    fireEvent.click(screen.getByText("Request schema"));
    expect(document.body.textContent).toContain('"type"');

    fireEvent.click(screen.getByText("Response schemas"));
    expect(document.body.textContent).toContain('"200"');
  });

  it("renders null request and response schemas without crashing", () => {
    const operationInfo = makeOperationInfo({
      requestSchema: null as unknown as Record<string, unknown>,
      responseSchemas: null as unknown as Record<string, unknown>,
    });

    render(
      <OperationInfoModal
        visible={true}
        onClose={jest.fn()}
        operationInfo={operationInfo}
      />,
    );

    expect(screen.getByText("Specification")).toBeInTheDocument();

    const rendersNullCodeBlock = () =>
      Array.from(document.querySelectorAll("pre")).some(
        (el) => el.textContent?.trim() === "null",
      );

    fireEvent.click(screen.getByText("Request schema"));
    expect(rendersNullCodeBlock()).toBe(true);

    fireEvent.click(screen.getByText("Response schemas"));
    expect(rendersNullCodeBlock()).toBe(true);
  });

  it("shows the typed operation fields (protocol, rpc method, deprecated flag) when present", () => {
    const operation = makeOperation({
      binding: "kafka",
      rpcMethod: "GetWidget",
      channel: "widgets.updated",
      isDeprecated: true,
      summary: "Fetches a widget by id",
    });

    render(
      <OperationInfoModal
        visible={true}
        onClose={jest.fn()}
        operationInfo={makeOperationInfo()}
        operation={operation}
      />,
    );

    expect(screen.getByText("kafka")).toBeInTheDocument();
    expect(screen.getByText("GetWidget")).toBeInTheDocument();
    expect(screen.getByText("Deprecated")).toBeInTheDocument();
    expect(screen.getByText("Fetches a widget by id")).toBeInTheDocument();
    // The channel belongs to the operations table, not to this header.
    expect(screen.queryByText("widgets.updated")).not.toBeInTheDocument();
  });

  it("puts the method badge and the summary on the same header row", () => {
    const operation = makeOperation({ summary: "Fetches a widget by id" });

    render(
      <OperationInfoModal
        visible={true}
        onClose={jest.fn()}
        operationInfo={makeOperationInfo()}
        operation={operation}
      />,
    );

    const method = screen.getByText("GET");
    const summary = screen.getByText("Fetches a widget by id");
    expect(method.parentElement).toBe(summary.parentElement);
  });

  it("renders no header badges when the typed operation fields are undefined", () => {
    // No method/channel/protocol/rpcMethod/isDeprecated/summary set — the header
    // section must hide entirely rather than render empty or "undefined" content.
    const operation = makeOperation({ method: "" });

    render(
      <OperationInfoModal
        visible={true}
        onClose={jest.fn()}
        operationInfo={makeOperationInfo()}
        operation={operation}
      />,
    );

    expect(screen.queryByText("GET")).not.toBeInTheDocument();
    expect(screen.queryByText("Deprecated")).not.toBeInTheDocument();
    expect(screen.queryByText("undefined")).not.toBeInTheDocument();
  });

  it("does not render header badges when no operation is passed", () => {
    render(
      <OperationInfoModal
        visible={true}
        onClose={jest.fn()}
        operationInfo={makeOperationInfo()}
      />,
    );

    expect(screen.queryByText("Deprecated")).not.toBeInTheDocument();
  });
});
