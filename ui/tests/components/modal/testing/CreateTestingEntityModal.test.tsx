/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { Element } from "../../../../src/api/apiTypes.ts";
import { CreateTestingEntityModal } from "../../../../src/components/modal/testing/CreateTestingEntityModal.tsx";

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

jest.mock("../../../../src/api/api.ts", () => ({
  api: {
    getElements: (chainId: string) => mockGetElements(chainId),
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

const FORM_ID = "createTestingEntityForm";

type Entity = { id: string };

function element(overrides: Partial<Element> = {}): Element {
  return {
    id: "element-1",
    name: "First",
    description: "",
    chainId: "chain-1",
    type: "http-trigger",
    mandatoryChecksPassed: true,
    properties: undefined as never,
    ...overrides,
  };
}

const isTrigger = (element: Element): boolean =>
  element.type === "http-trigger";

function renderModal(create: jest.Mock) {
  const onCreated = jest.fn<void, [Entity]>();
  const utils = render(
    <CreateTestingEntityModal<Entity>
      chainId="chain-1"
      onCreated={onCreated}
      formId={FORM_ID}
      title="Create Thing"
      nameTestId="thing-name"
      nameRequiredMessage="Enter a name for the thing."
      createFailedMessage="Failed to create a thing"
      elementLabel="Trigger"
      elementPlaceholder="Select an HTTP trigger"
      elementPredicate={isTrigger}
      create={create}
    />,
  );
  return { ...utils, onCreated };
}

function typeName(value: string): void {
  fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
    target: { value },
  });
}

beforeEach(() => {
  jest.clearAllMocks();
  mockGetElements.mockResolvedValue([element()]);
});

describe("CreateTestingEntityModal", () => {
  it("should preselect the first element when the chain has several", async () => {
    mockGetElements.mockResolvedValue([
      element(),
      element({ id: "element-2", name: "Second" }),
    ]);
    const create = jest.fn().mockResolvedValue({ id: "thing-1" });
    renderModal(create);
    await waitFor(() =>
      expect(mockGetElements).toHaveBeenCalledWith("chain-1"),
    );

    typeName("Thing");
    fireEvent.submit(document.getElementById(FORM_ID)!);

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create).toHaveBeenCalledWith(
      expect.objectContaining({ name: "Thing", elementId: "element-1" }),
      [element(), element({ id: "element-2", name: "Second" })],
    );
  });

  it("should submit the form when the footer button is clicked", async () => {
    const create = jest.fn().mockResolvedValue({ id: "thing-1" });
    const { onCreated } = renderModal(create);
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    typeName("Thing");
    fireEvent.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(mockCloseContainingModal).toHaveBeenCalled();
    expect(onCreated).toHaveBeenCalledWith({ id: "thing-1" });
  });

  it("should label the element field and its placeholder from the caller", async () => {
    mockGetElements.mockResolvedValue([]);
    renderModal(jest.fn());
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    expect(screen.getByText("Trigger")).toBeInTheDocument();
    expect(screen.getByText("Select an HTTP trigger")).toBeInTheDocument();
  });

  it("should send the description along with the name", async () => {
    const create = jest.fn().mockResolvedValue({ id: "thing-1" });
    renderModal(create);
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    typeName("Thing");
    fireEvent.change(screen.getByRole("textbox", { name: "Description" }), {
      target: { value: "why it exists" },
    });
    fireEvent.submit(document.getElementById(FORM_ID)!);

    await waitFor(() => expect(create).toHaveBeenCalled());
    expect(create).toHaveBeenCalledWith(
      expect.objectContaining({ description: "why it exists" }),
      expect.anything(),
    );
  });

  it("should close when the modal is dismissed", async () => {
    renderModal(jest.fn());
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    fireEvent.click(screen.getByRole("button", { name: "Close" }));

    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  // A second submit would create a second entity, so the form is held shut until
  // the first one answers.
  it("should hold the form while the creation is in flight", async () => {
    let finish!: (entity: Entity) => void;
    const create = jest.fn(
      () =>
        new Promise<Entity>((resolve) => {
          finish = resolve;
        }),
    );
    renderModal(create);
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    typeName("Thing");
    fireEvent.submit(document.getElementById(FORM_ID)!);
    // Not `waitFor`: it would wait on the creation this test holds open.
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(create).toHaveBeenCalled();

    expect(screen.getByRole("button", { name: "Cancel" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Name" })).toBeDisabled();

    finish({ id: "thing-1" });
    await waitFor(() => expect(mockCloseContainingModal).toHaveBeenCalled());
  });

  it("should report a failed creation and stay open", async () => {
    const create = jest.fn().mockRejectedValue(new Error("nope"));
    const { onCreated } = renderModal(create);
    await waitFor(() => expect(mockGetElements).toHaveBeenCalled());

    typeName("Doomed");
    fireEvent.submit(document.getElementById(FORM_ID)!);

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to create a thing",
        expect.any(Error),
      ),
    );
    expect(mockCloseContainingModal).not.toHaveBeenCalled();
    expect(onCreated).not.toHaveBeenCalled();
  });
});
