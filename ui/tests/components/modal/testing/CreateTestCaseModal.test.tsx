/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Element, TestCase } from "../../../../src/api/apiTypes.ts";
import { CreateTestCaseModal } from "../../../../src/components/modal/testing/CreateTestCaseModal.tsx";
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
const mockCreateTestCase = jest.fn<Promise<TestCase>, [unknown]>();

jest.mock("../../../../src/api/api.ts", () => ({
  api: {
    getElements: (chainId: string) => mockGetElements(chainId),
    createTestCase: (request: unknown) => mockCreateTestCase(request),
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
    name: "Trigger",
    description: "",
    chainId: "chain-1",
    type: "http-trigger",
    mandatoryChecksPassed: true,
    properties: undefined as never,
    ...overrides,
  };
}

function withProperties(
  overrides: Partial<Element>,
  properties: Record<string, unknown>,
): Element {
  return { ...element(overrides), properties: properties as never };
}

function createdTestCase(id: string): TestCase {
  return {
    id,
    name: "New case",
    description: "",
    enabled: false,
    triggerReference: { chainId: "chain-1", elementId: "element-1" },
    requestSettings: null,
    responseValidationRules: [],
    createdBy: null,
    createdAt: null,
    updatedBy: null,
    updatedAt: null,
  };
}

function renderModal() {
  const onCreated = jest.fn();
  const utils = render(
    <CreateTestCaseModal chainId="chain-1" onCreated={onCreated} />,
  );
  return { ...utils, onCreated };
}

function typeName(value: string): void {
  fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
    target: { value },
  });
}

function submitForm(): void {
  const form = document.getElementById("createTestCaseForm");
  expect(form).toBeTruthy();
  fireEvent.submit(form!);
}

beforeEach(() => {
  jest.clearAllMocks();
  mockGetElements.mockResolvedValue([]);
  mockCreateTestCase.mockResolvedValue(createdTestCase("case-1"));
});

describe("CreateTestCaseModal", () => {
  it("should send the creation defaults when the form is submitted", async () => {
    mockGetElements.mockResolvedValue([
      withProperties({}, { httpMethodRestrict: "POST, PUT" }),
    ]);
    const { onCreated } = renderModal();
    await waitFor(() =>
      expect(mockGetElements).toHaveBeenCalledWith("chain-1"),
    );

    typeName("  New case  ");
    submitForm();

    await waitFor(() => expect(mockCreateTestCase).toHaveBeenCalled());
    expect(mockCreateTestCase).toHaveBeenCalledWith({
      name: "New case",
      description: "",
      enabled: false,
      triggerReference: { chainId: "chain-1", elementId: "element-1" },
      requestSettings: {
        queryParameters: [],
        pathParameters: [],
        message: { body: null, headers: [] },
        method: "POST",
        timeout: 120000,
      },
      responseValidationRules: [],
    });
    expect(mockCloseContainingModal).toHaveBeenCalled();
    expect(onCreated).toHaveBeenCalledWith(createdTestCase("case-1"));
  });

  it("should default the method to GET when the trigger restricts none", async () => {
    mockGetElements.mockResolvedValue([element()]);
    renderModal();
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    typeName("No method");
    submitForm();

    await waitFor(() => expect(mockCreateTestCase).toHaveBeenCalled());
    expect(mockCreateTestCase).toHaveBeenCalledWith(
      expect.objectContaining({
        requestSettings: expect.objectContaining({ method: "GET" }),
      }),
    );
  });

  it("should offer HTTP triggers only", async () => {
    mockGetElements.mockResolvedValue([
      element(),
      element({ id: "element-2", name: "Sender", type: "http-sender" }),
    ]);
    renderModal();
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    // The modal renders into a portal, so the select lives outside `container`.
    openSelect(document.body);

    await waitFor(() => expect(querySelectOption("Trigger")).toBeTruthy());
    expect(querySelectOption("Sender")).toBeNull();
  });

  it("should keep the chain reference when no trigger is selected", async () => {
    renderModal();
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    typeName("Without trigger");
    submitForm();

    await waitFor(() => expect(mockCreateTestCase).toHaveBeenCalled());
    expect(mockCreateTestCase).toHaveBeenCalledWith(
      expect.objectContaining({
        triggerReference: { chainId: "chain-1", elementId: "" },
      }),
    );
  });

  it("should not create anything when the name is blank", async () => {
    renderModal();
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    submitForm();

    await screen.findByText("Enter a name for the test case.");
    expect(mockCreateTestCase).not.toHaveBeenCalled();
  });

  it("should report a failed creation and stay open", async () => {
    mockCreateTestCase.mockRejectedValue(new Error("nope"));
    const { onCreated } = renderModal();
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    typeName("Doomed");
    submitForm();

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to create a test case",
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
