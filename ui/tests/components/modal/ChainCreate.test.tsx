/**
 * @jest-environment jsdom
 */
import React from "react";
import {
  render,
  screen,
  fireEvent,
  waitFor,
  act,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import { ChainCreate } from "../../../src/components/modal/ChainCreate";
import type { Chain } from "../../../src/api/apiTypes";

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

const mockGetChain = jest.fn() as jest.MockedFunction<
  (id: string) => Promise<Chain>
>;

jest.mock("../../../src/api/api.ts", () => ({
  api: {
    getChain: (id: string): Promise<Chain> => mockGetChain(id),
  },
}));

const mockRequestFailed = jest.fn();
jest.mock("../../../src/hooks/useNotificationService.tsx", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
  }),
}));

const mockCloseContainingModal = jest.fn();
jest.mock("../../../src/ModalContextProvider.tsx", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

function minimalChain(overrides: Partial<Chain> = {}): Chain {
  return {
    id: "chain-1",
    name: "Loaded Chain",
    description: "d",
    labels: [{ name: "l1", technical: false }],
    businessDescription: "bd",
    assumptions: "a",
    outOfScope: "o",
    navigationPath: [],
    elements: [],
    dependencies: [],
    deployments: [],
    defaultSwimlaneId: "s1",
    reuseSwimlaneId: "s2",
    unsavedChanges: false,
    containsDeprecatedContainers: false,
    containsDeprecatedElements: false,
    containsUnsupportedElements: false,
    ...overrides,
  } as Chain;
}

describe("ChainCreate", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockGetChain.mockReset();
  });

  it("create mode shows Description on General Info tab", () => {
    const onSubmit = jest.fn();
    render(<ChainCreate onSubmit={onSubmit} />);
    expect(
      screen.getByRole("textbox", { name: "Description" }),
    ).toBeInTheDocument();
  });

  it("create mode Extended Description tab exposes Business Description", () => {
    render(<ChainCreate onSubmit={jest.fn()} />);
    fireEvent.click(screen.getByRole("tab", { name: "Extended Description" }));
    expect(
      screen.getByRole("textbox", { name: "Business Description" }),
    ).toBeInTheDocument();
  });

  it("create mode footer shows Submit primary button", () => {
    render(<ChainCreate onSubmit={jest.fn()} />);
    expect(
      screen.getByRole("button", { name: /^submit$/i }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /^cancel$/i }),
    ).toBeInTheDocument();
  });

  it("create mode shows New Chain and submits with sync onSubmit", async () => {
    const onSubmit = jest.fn();
    render(<ChainCreate onSubmit={onSubmit} />);

    expect(screen.getByText("New Chain")).toBeInTheDocument();

    const nameInput = screen.getByRole("textbox", { name: "Name" });
    fireEvent.change(nameInput, { target: { value: "My Chain" } });

    const form = document.getElementById("createChainForm");
    expect(form).toBeTruthy();
    fireEvent.submit(form!);

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalledTimes(1);
    });
    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        name: "My Chain",
        labels: [],
      }),
      true,
      false,
    );
    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  it("create mode does not call onSubmit when name is empty", () => {
    const onSubmit = jest.fn();
    render(<ChainCreate onSubmit={onSubmit} />);

    const form = document.getElementById("createChainForm");
    fireEvent.submit(form!);

    expect(onSubmit).not.toHaveBeenCalled();
  });

  it("disables In new tab checkbox when Open chain is unchecked", () => {
    render(<ChainCreate onSubmit={jest.fn()} />);
    const openChain = screen.getByRole("checkbox", { name: /open chain/i });
    const newTab = screen.getByRole("checkbox", { name: /in new tab/i });
    expect(newTab).not.toBeDisabled();
    fireEvent.click(openChain);
    expect(openChain).not.toBeChecked();
    expect(newTab).toBeDisabled();
  });

  it(
    "create mode passes openChain false to onSubmit when Open chain is unchecked",
    async () => {
      const onSubmit = jest.fn();
      render(<ChainCreate onSubmit={onSubmit} />);
      fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
        target: { value: "Named" },
      });
      fireEvent.click(screen.getByRole("checkbox", { name: /open chain/i }));
      fireEvent.click(screen.getByRole("button", { name: /^submit$/i }));
      await waitFor(() => expect(onSubmit).toHaveBeenCalled());
      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({ name: "Named" }),
        false,
        false,
      );
    },
    15_000,
  );

  it("edit mode loads chain and calls onUpdateMetadata on submit", async () => {
    mockGetChain.mockResolvedValue(minimalChain({ name: "Loaded Chain" }));
    const onUpdateMetadata = jest.fn();

    render(
      <ChainCreate
        variant="editChainMetaData"
        chainId="chain-1"
        onUpdateMetadata={onUpdateMetadata}
      />,
    );

    expect(screen.getByText("Edit chain")).toBeInTheDocument();

    const nameInput = await screen.findByDisplayValue("Loaded Chain");
    fireEvent.change(nameInput, { target: { value: "Renamed" } });

    const form = document.getElementById("editChainMetadataForm");
    expect(form).toBeTruthy();
    fireEvent.submit(form!);

    await waitFor(() => {
      expect(onUpdateMetadata).toHaveBeenCalled();
    });
    expect(onUpdateMetadata).toHaveBeenCalledWith(
      "chain-1",
      expect.objectContaining({
        name: "Renamed",
        labels: [{ name: "l1", technical: false }],
      }),
      false,
      false,
    );
    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  it("edit mode footer shows Submit after chain loads", async () => {
    mockGetChain.mockResolvedValue(minimalChain());
    render(
      <ChainCreate
        variant="editChainMetaData"
        chainId="chain-1"
        onUpdateMetadata={jest.fn()}
      />,
    );

    await screen.findByDisplayValue("Loaded Chain");
    expect(
      screen.getByRole("button", { name: /^submit$/i }),
    ).toBeInTheDocument();
  });

  it("edit mode on getChain failure notifies and closes modal", async () => {
    mockGetChain.mockRejectedValue(new Error("network"));

    render(
      <ChainCreate
        variant="editChainMetaData"
        chainId="chain-1"
        onUpdateMetadata={jest.fn()}
      />,
    );

    await act(async () => {
      await Promise.resolve();
    });

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to load chain",
        expect.any(Error),
      );
      expect(mockCloseContainingModal).toHaveBeenCalled();
    });
  });

  it("create mode keeps modal open when onSubmit returns a rejected promise", async () => {
    const onSubmit = jest.fn().mockRejectedValue(new Error("fail"));
    render(<ChainCreate onSubmit={onSubmit} />);

    fireEvent.change(screen.getByRole("textbox", { name: "Name" }), {
      target: { value: "X" },
    });
    fireEvent.submit(document.getElementById("createChainForm")!);

    await waitFor(() => {
      expect(onSubmit).toHaveBeenCalled();
    });

    expect(mockCloseContainingModal).not.toHaveBeenCalled();
  });
});
