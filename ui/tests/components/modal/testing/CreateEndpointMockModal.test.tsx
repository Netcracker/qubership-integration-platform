/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Element, EndpointMock } from "../../../../src/api/apiTypes.ts";
import { CreateEndpointMockModal } from "../../../../src/components/modal/testing/CreateEndpointMockModal.tsx";
import { openSelect, querySelectOption } from "../../../helpers/antdSelect.ts";

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

const mockGetElements = jest.fn<Promise<Element[]>, [string]>();
const mockCreateEndpointMock = jest.fn<Promise<EndpointMock>, [unknown]>();

jest.mock("../../../../src/api/api.ts", () => ({
  api: {
    getElements: (chainId: string) => mockGetElements(chainId),
    createEndpointMock: (request: unknown) => mockCreateEndpointMock(request),
  },
}));

const mockRequestFailed = jest.fn();
jest.mock("../../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => ({ requestFailed: mockRequestFailed }),
}));

const mockCloseContainingModal = jest.fn();
jest.mock("../../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

function element(overrides: Partial<Element> = {}): Element {
  return {
    id: "element-1",
    name: "Sender",
    description: "",
    chainId: "chain-1",
    type: "http-sender",
    mandatoryChecksPassed: true,
    properties: undefined as never,
    ...overrides,
  };
}

function serviceCall(
  overrides: Partial<Element>,
  protocol: string | undefined,
): Element {
  return {
    ...element({ type: "service-call", ...overrides }),
    properties: { integrationOperationProtocolType: protocol } as never,
  };
}

function createdMock(id: string): EndpointMock {
  return {
    id,
    name: "New mock",
    description: "",
    enabled: true,
    endpointReference: { chainId: "chain-1", elementId: "element-1" },
    responseSettings: null,
    requestMatchers: [],
    createdBy: null,
    createdAt: null,
    updatedBy: null,
    updatedAt: null,
  };
}

function renderModal() {
  const onCreated = jest.fn();
  const utils = render(
    <CreateEndpointMockModal chainId="chain-1" onCreated={onCreated} />,
  );
  return { ...utils, onCreated };
}

function typeName(value: string): void {
  fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
    target: { value },
  });
}

function submitForm(): void {
  const form = document.getElementById("createEndpointMockForm");
  expect(form).toBeTruthy();
  fireEvent.submit(form!);
}

beforeEach(() => {
  jest.clearAllMocks();
  mockGetElements.mockResolvedValue([]);
  mockCreateEndpointMock.mockResolvedValue(createdMock("mock-1"));
});

describe("CreateEndpointMockModal", () => {
  it("should send the creation defaults when the form is submitted", async () => {
    mockGetElements.mockResolvedValue([element()]);
    const { onCreated } = renderModal();
    await waitFor(() =>
      expect(mockGetElements).toHaveBeenCalledWith("chain-1"),
    );

    typeName("  New mock  ");
    submitForm();

    await waitFor(() => expect(mockCreateEndpointMock).toHaveBeenCalled());
    expect(mockCreateEndpointMock).toHaveBeenCalledWith({
      name: "New mock",
      description: "",
      enabled: true,
      endpointReference: { chainId: "chain-1", elementId: "element-1" },
      responseSettings: {
        message: { body: null, headers: [] },
        status: 200,
        delay: 0,
      },
      requestMatchers: [],
    });
    expect(mockCloseContainingModal).toHaveBeenCalled();
    expect(onCreated).toHaveBeenCalledWith(createdMock("mock-1"));
  });

  it("should offer HTTP endpoints only", async () => {
    mockGetElements.mockResolvedValue([
      element(),
      element({ id: "element-2", name: "Trigger", type: "http-trigger" }),
      serviceCall({ id: "element-3", name: "HTTP call" }, "http"),
      serviceCall({ id: "element-4", name: "Kafka call" }, "kafka"),
      serviceCall({ id: "element-5", name: "Protocol-less call" }, undefined),
    ]);
    renderModal();
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    // The modal renders into a portal, so the select lives outside `container`.
    openSelect(document.body);

    await waitFor(() => expect(querySelectOption("Sender")).toBeTruthy());
    expect(querySelectOption("HTTP call")).toBeTruthy();
    expect(querySelectOption("Trigger")).toBeNull();
    expect(querySelectOption("Kafka call")).toBeNull();
    expect(querySelectOption("Protocol-less call")).toBeNull();
  });

  it("should offer an endpoint nested in a container element", async () => {
    mockGetElements.mockResolvedValue([
      element({
        id: "container-1",
        name: "Try",
        type: "try-catch-finally",
        children: [element({ id: "element-9", name: "Nested sender" })],
      }),
    ]);
    renderModal();
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    openSelect(document.body);

    await waitFor(() =>
      expect(querySelectOption("Nested sender")).toBeTruthy(),
    );
    expect(querySelectOption("Try")).toBeNull();
  });

  it("should keep the chain reference when no endpoint is selected", async () => {
    renderModal();
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    typeName("Without endpoint");
    submitForm();

    await waitFor(() => expect(mockCreateEndpointMock).toHaveBeenCalled());
    expect(mockCreateEndpointMock).toHaveBeenCalledWith(
      expect.objectContaining({
        endpointReference: { chainId: "chain-1", elementId: "" },
      }),
    );
  });

  it("should not create anything when the name is blank", async () => {
    renderModal();
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    submitForm();

    await screen.findByText("Enter a name for the endpoint mock.");
    expect(mockCreateEndpointMock).not.toHaveBeenCalled();
  });

  it("should report a failed creation and stay open", async () => {
    mockCreateEndpointMock.mockRejectedValue(new Error("nope"));
    const { onCreated } = renderModal();
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    typeName("Doomed");
    submitForm();

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to create an endpoint mock",
        expect.any(Error),
      ),
    );
    expect(mockCloseContainingModal).not.toHaveBeenCalled();
    expect(onCreated).not.toHaveBeenCalled();
  });

  it("should report a failed element load", async () => {
    mockGetElements.mockRejectedValue(new Error("gone"));
    renderModal();

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to load chain elements",
        expect.any(Error),
      ),
    );
  });
});
