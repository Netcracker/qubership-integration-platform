/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, fireEvent, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { CreateDesignTemplateModal } from "../../../../src/components/admin_tools/design-templates/CreateDesignTemplateModal";

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

const mockCloseContainingModal = jest.fn();
jest.mock("../../../../src/ModalContextProvider", () => ({
  useModalContext: () => ({
    closeContainingModal: mockCloseContainingModal,
  }),
}));

const mockRequestFailed = jest.fn();
jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: mockRequestFailed,
  }),
}));

const mockCreateOrUpdate = jest.fn();
jest.mock("../../../../src/api/api", () => ({
  api: {
    createOrUpdateDetailedDesignTemplate: (...args: unknown[]) =>
      mockCreateOrUpdate(...args),
  },
}));

jest.mock("antd/es/upload/Dragger", () => ({
  __esModule: true,
  default: ({
    children,
    onChange,
    fileList,
  }: {
    children?: React.ReactNode;
    onChange?: (info: {
      file: { status?: string; name: string };
      fileList: unknown[];
    }) => void;
    fileList?: unknown[];
  }) => {
    const originFileObj = {
      text: async () => "{% x %}",
    } as File;
    const attached = {
      uid: "1",
      name: "tpl.ftl",
      originFileObj,
    };
    return (
      <div>
        <button
          data-testid="mock-dragger-add"
          type="button"
          onClick={() =>
            onChange?.({
              file: { name: "tpl.ftl" },
              fileList: [attached],
            })
          }
        >
          add file
        </button>
        <button
          data-testid="mock-dragger-remove"
          type="button"
          onClick={() =>
            onChange?.({
              file: { status: "removed", name: "tpl.ftl" },
              fileList: [],
            })
          }
        >
          remove file
        </button>
        {children}
        <span data-testid="dragger-file-count">{fileList?.length ?? 0}</span>
      </div>
    );
  },
}));

describe("CreateDesignTemplateModal", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockCreateOrUpdate.mockResolvedValue({ id: "t1", name: "tpl" });
  });

  it("reports a missing file as a validation error", async () => {
    render(<CreateDesignTemplateModal onTemplateCreated={jest.fn()} />);

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "OnlyName" },
    });
    fireEvent.click(screen.getByRole("button", { name: /^create$/i }));

    expect(await screen.findByText("Attach a .ftl file")).toBeInTheDocument();
    expect(mockCreateOrUpdate).not.toHaveBeenCalled();
  });

  it("creates the template from the attached file", async () => {
    const onTemplateCreated = jest.fn();
    render(<CreateDesignTemplateModal onTemplateCreated={onTemplateCreated} />);

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "  My Template  " },
    });
    fireEvent.click(screen.getByTestId("mock-dragger-add"));
    fireEvent.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => {
      expect(mockCreateOrUpdate).toHaveBeenCalledWith("My Template", "{% x %}");
    });
    expect(mockCloseContainingModal).toHaveBeenCalled();
    expect(onTemplateCreated).toHaveBeenCalled();
  });

  it("Cancel closes the modal without calling the API", () => {
    render(<CreateDesignTemplateModal onTemplateCreated={jest.fn()} />);

    fireEvent.click(screen.getByRole("button", { name: /^cancel$/i }));

    expect(mockCloseContainingModal).toHaveBeenCalled();
    expect(mockCreateOrUpdate).not.toHaveBeenCalled();
  });

  it("fills name from .ftl file when name is empty", () => {
    render(<CreateDesignTemplateModal onTemplateCreated={jest.fn()} />);

    fireEvent.click(screen.getByTestId("mock-dragger-add"));

    expect(screen.getByRole("textbox", { name: /name/i })).toHaveValue("tpl");
  });

  it("clears name when file is removed if it matched the file stem", () => {
    render(<CreateDesignTemplateModal onTemplateCreated={jest.fn()} />);

    fireEvent.click(screen.getByTestId("mock-dragger-add"));
    expect(screen.getByRole("textbox", { name: /name/i })).toHaveValue("tpl");

    fireEvent.click(screen.getByTestId("mock-dragger-remove"));

    expect(screen.getByRole("textbox", { name: /name/i })).toHaveValue("");
  });

  it("shows requestFailed when save fails", async () => {
    mockCreateOrUpdate.mockRejectedValue(new Error("network"));

    render(<CreateDesignTemplateModal onTemplateCreated={jest.fn()} />);

    fireEvent.change(screen.getByRole("textbox", { name: /name/i }), {
      target: { value: "T" },
    });
    fireEvent.click(screen.getByTestId("mock-dragger-add"));
    fireEvent.click(screen.getByRole("button", { name: /^create$/i }));

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalled();
    });
    expect(mockCloseContainingModal).not.toHaveBeenCalled();
  });
});
