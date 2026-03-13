/**
 * @jest-environment jsdom
 */
import React, { PropsWithChildren } from "react";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Modal } from "antd";
import type { GeneralImportInstructions } from "../../../src";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import { getAllPermissions } from "../../../src/permissions/funcs.ts";

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

Object.defineProperty(URL, "createObjectURL", {
  writable: true,
  value: jest.fn().mockReturnValue("blob:test"),
});

Object.defineProperty(URL, "revokeObjectURL", {
  writable: true,
  value: jest.fn(),
});

jest.mock("antd/es/upload/Dragger", () => ({
  __esModule: true,
  default: ({
    children,
    onChange,
  }: {
    children?: React.ReactNode;
    onChange?: (info: unknown) => void;
  }) => {
    const handleInteraction = () =>
      onChange?.({
        fileList: [
          {
            uid: "1",
            originFileObj: new File(["yaml"], "test.yaml", {
              type: "text/yaml",
            }),
          },
        ],
      });
    return (
      <button data-testid="dragger" type="button" onClick={handleInteraction}>
        {children}
      </button>
    );
  },
}));

jest.mock("../../../src/api/api", () => ({
  api: {
    getImportInstructions: jest.fn(),
    addImportInstruction: jest.fn(),
    updateImportInstruction: jest.fn(),
    deleteImportInstructions: jest.fn(),
    uploadImportInstructions: jest.fn(),
    exportImportInstructions: jest.fn(),
  },
}));

const mockApi = jest.requireMock("../../../src/api/api").api as {
  getImportInstructions: jest.Mock;
  addImportInstruction: jest.Mock;
  updateImportInstruction: jest.Mock;
  deleteImportInstructions: jest.Mock;
  uploadImportInstructions: jest.Mock;
  exportImportInstructions: jest.Mock;
};

const mockRequestFailed = jest.fn();
const mockSuccess = jest.fn();

// Stable reference prevents useCallback/useEffect infinite loop when
// notificationService is in the dependency array of fetchInstructions.
const stableNotificationService = {
  requestFailed: mockRequestFailed,
  success: mockSuccess,
};

jest.mock("../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => stableNotificationService,
}));

jest.mock("../../../src/components/admin_tools/CommonStyle.module.css", () => ({
  __esModule: true,
  default: {
    container: "container",
    title: "title",
    icon: "icon",
    "table-wrapper": "table-wrapper",
  },
}));

jest.mock("../../../src/components/table/EditableCell.module.css", () => ({
  __esModule: true,
  default: {
    editingWrapper: "editing-wrapper",
    editingButtons: "editing-buttons",
    editableCellWrapper: "editable-cell-wrapper",
    inlineIcon: "inline-icon",
  },
}));

const sampleInstructions: GeneralImportInstructions = {
  chains: {
    ignore: [{ id: "chain-1", name: "Chain One" }],
    override: [],
    delete: [],
  },
  services: { ignore: [], delete: [] },
  specificationGroups: { delete: [], ignore: [] },
  specifications: { delete: [], ignore: [] },
  commonVariables: {
    ignore: [{ id: "var-1", name: "Var One" }],
    delete: [],
  },
};

const ContextProviders: React.FC<PropsWithChildren> = ({ children }) => {
  return (
    <UserPermissionsContext.Provider value={getAllPermissions()}>
      {children}
    </UserPermissionsContext.Provider>
  );
};

describe("ImportInstructions", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockRequestFailed.mockClear();
    mockSuccess.mockClear();
    mockApi.getImportInstructions.mockResolvedValue(sampleInstructions);
    mockApi.exportImportInstructions.mockResolvedValue(
      new File(["content"], "qip-import-instructions.yaml"),
    );
  });

  afterEach(() => {
    jest.restoreAllMocks();
  });

  it("renders page title and loads instructions", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    expect(screen.getByText(/import/i)).toBeInTheDocument();
    expect(screen.getByText(/instructions/i)).toBeInTheDocument();

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalledTimes(1);
    });

    await waitFor(() => {
      expect(screen.getByText("Chains")).toBeInTheDocument();
      expect(screen.getByText("Services")).toBeInTheDocument();
      expect(screen.getByText("Common Variables")).toBeInTheDocument();
    });
  }, 10000);

  it("shows Add, Export, Upload buttons", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    expect(screen.getByRole("button", { name: /add/i })).toBeInTheDocument();
    expect(screen.getByPlaceholderText("Search...")).toBeInTheDocument();
  });

  it("displays chain and variable items from instructions", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(screen.getByText("Chains")).toBeInTheDocument();
    });

    expect(screen.getByText("Chain One")).toBeInTheDocument();
    expect(screen.getByText("Var One")).toBeInTheDocument();
  });

  it("fetches export on Export button click", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    const exportButton = screen.getByTestId("import-instructions-export");
    fireEvent.click(exportButton);

    await waitFor(() => {
      expect(mockApi.exportImportInstructions).toHaveBeenCalled();
    });
  });

  it("opens Add modal when Add button is clicked", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    const addButton = screen.getByRole("button", { name: /add/i });
    fireEvent.click(addButton);

    expect(screen.getByText("Add Instruction")).toBeInTheDocument();
  });

  it("opens Upload modal when Upload button is clicked", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    const uploadButton = screen.getByTestId("import-instructions-upload");
    fireEvent.click(uploadButton);

    expect(
      screen.getByText("Upload Instructions (yaml, yml)"),
    ).toBeInTheDocument();
  });

  it("displays column settings button", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    const columnSettingsButton = screen.getByTestId(
      "import-instructions-column-settings",
    );
    expect(columnSettingsButton).toBeInTheDocument();
  });

  it("calls requestFailed when fetchInstructions throws", async () => {
    mockApi.getImportInstructions.mockRejectedValue(new Error("Network error"));
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalled();
    });
  });

  it("calls requestFailed when handleExport throws", async () => {
    mockApi.exportImportInstructions.mockRejectedValue(
      new Error("Export failed"),
    );
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    const exportButton = screen.getByTestId("import-instructions-export");
    fireEvent.click(exportButton);

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalled();
    });
  });

  it("Delete button is disabled when no rows are selected", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    const deleteButton = screen.getByTestId("import-instructions-delete");
    expect(deleteButton).toBeDisabled();
  });

  it("search input filters visible data by id", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(screen.getByText("Chain One")).toBeInTheDocument();
      expect(screen.getByText("Var One")).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText("Search...");
    fireEvent.change(searchInput, { target: { value: "chain" } });

    await waitFor(() => {
      expect(screen.getByText("Chain One")).toBeInTheDocument();
    });
  });

  it("search with no match still shows group headers", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(screen.getByText("Chains")).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText("Search...");
    fireEvent.change(searchInput, { target: { value: "nonexistent-xyz" } });

    await waitFor(() => {
      expect(screen.getByText("Chains")).toBeInTheDocument();
    });
  });

  it("opens AddInstructionModal with form when Add button is clicked", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByRole("button", { name: /add/i }));

    expect(screen.getByText("Add Instruction")).toBeInTheDocument();
    expect(screen.getByLabelText("Id")).toBeInTheDocument();
  });

  it("shows Id is required validation error when submitting empty form", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByRole("button", { name: /^add$/i }));

    expect(screen.getByText("Add Instruction")).toBeInTheDocument();

    const addInModalButton = screen
      .getAllByRole("button", { name: /^add$/i })
      .find((b) => b.closest(".ant-modal-footer"));
    if (addInModalButton) {
      fireEvent.click(addInModalButton);
    }

    await waitFor(() => {
      expect(screen.queryByText("Id is required")).toBeInTheDocument();
    });

    expect(mockApi.addImportInstruction).not.toHaveBeenCalled();
  }, 10000);

  it("calls addImportInstruction when form is filled and submitted", async () => {
    mockApi.addImportInstruction.mockResolvedValue({});
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByRole("button", { name: /^add$/i }));

    const idInput = screen.getByLabelText("Id");
    fireEvent.change(idInput, { target: { value: "my-chain-id" } });

    const addInModalButton = screen
      .getAllByRole("button", { name: /^add$/i })
      .find((b) => b.closest(".ant-modal-footer"));
    if (addInModalButton) {
      fireEvent.click(addInModalButton);
    }

    await waitFor(() => {
      expect(mockApi.addImportInstruction).toHaveBeenCalledWith(
        expect.objectContaining({ id: "my-chain-id" }),
      );
    });
  }, 10000);

  it("UploadInstructionsModal Upload button is disabled when no file selected", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByTestId("import-instructions-upload"));

    expect(
      screen.getByText("Upload Instructions (yaml, yml)"),
    ).toBeInTheDocument();

    const uploadButton = screen
      .getAllByRole("button", { name: /^upload$/i })
      .find((b) => b.closest(".ant-modal-footer"));
    expect(uploadButton).toBeDefined();
    expect(uploadButton).toBeDisabled();
  });

  it("handleExport: URL.createObjectURL is called with the exported file blob", async () => {
    const createObjectURL = URL.createObjectURL as jest.Mock;
    createObjectURL.mockClear();

    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByTestId("import-instructions-export"));

    await waitFor(() => {
      expect(mockApi.exportImportInstructions).toHaveBeenCalled();
    });

    await waitFor(() => {
      expect(createObjectURL).toHaveBeenCalled();
    });
  });

  it("renders labels as Tag components and modifiedWhen as formatted date", async () => {
    mockApi.getImportInstructions.mockResolvedValueOnce({
      chains: {
        ignore: [
          {
            id: "chain-10",
            name: "Tagged Chain",
            labels: ["tag1", "tag2"],
            modifiedWhen: 1700000000000,
          },
        ],
        override: [],
        delete: [],
      },
      services: { ignore: [], delete: [] },
      specificationGroups: { ignore: [], delete: [] },
      specifications: { ignore: [], delete: [] },
      commonVariables: { ignore: [], delete: [] },
    });

    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(screen.getByText("Tagged Chain")).toBeInTheDocument();
      expect(screen.getByText("tag1")).toBeInTheDocument();
      expect(screen.getByText("tag2")).toBeInTheDocument();
      expect(screen.getByText(/2023-11-1/)).toBeInTheDocument();
    });
  });

  it("inline editing Action: click trigger shows Select, Apply calls updateImportInstruction", async () => {
    mockApi.updateImportInstruction.mockResolvedValue({});

    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(screen.getByText("Chain One")).toBeInTheDocument();
    });

    const cellTriggers = document.querySelectorAll(".editable-cell-wrapper");
    expect(cellTriggers.length).toBeGreaterThan(0);

    fireEvent.click(cellTriggers[0]);

    await waitFor(() => {
      const editingButtons = document.querySelectorAll(
        ".editing-buttons button",
      );
      expect(editingButtons.length).toBeGreaterThan(0);
    });

    const applyButton = document.querySelector(".editing-buttons button");
    expect(applyButton).toBeTruthy();
    fireEvent.click(applyButton!);

    await waitFor(() => {
      expect(mockApi.updateImportInstruction).toHaveBeenCalledWith(
        expect.objectContaining({ id: "chain-1" }),
      );
    });
  });

  it("inline editing Overridden By: click trigger shows Input, Apply calls updateImportInstruction", async () => {
    mockApi.updateImportInstruction.mockResolvedValue({});
    mockApi.getImportInstructions.mockResolvedValueOnce({
      chains: {
        ignore: [],
        override: [
          { id: "chain-2", name: "Chain Two", overriddenById: "other-chain" },
        ],
        delete: [],
      },
      services: { ignore: [], delete: [] },
      specificationGroups: { ignore: [], delete: [] },
      specifications: { ignore: [], delete: [] },
      commonVariables: { ignore: [], delete: [] },
    });

    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(screen.getByText("Chain Two")).toBeInTheDocument();
    });

    const cellTriggers = document.querySelectorAll(".editable-cell-wrapper");
    expect(cellTriggers.length).toBeGreaterThanOrEqual(2);

    fireEvent.click(cellTriggers[1]);

    await waitFor(() => {
      const editingButtons = document.querySelectorAll(
        ".editing-buttons button",
      );
      expect(editingButtons.length).toBeGreaterThan(0);
    });

    const applyButton = document.querySelector(".editing-buttons button");
    expect(applyButton).toBeTruthy();
    fireEvent.click(applyButton!);

    await waitFor(() => {
      expect(mockApi.updateImportInstruction).toHaveBeenCalledWith(
        expect.objectContaining({
          id: "chain-2",
          action: "OVERRIDE",
        }),
      );
    });
  });

  it("handleDelete: select row, confirm delete modal, deleteImportInstructions called", async () => {
    mockApi.deleteImportInstructions.mockResolvedValue(undefined);

    const confirmSpy = jest
      .spyOn(Modal, "confirm")
      .mockImplementation((props = {}) => {
        const onOk = props.onOk as
          | ((...args: unknown[]) => unknown)
          | undefined;
        if (onOk) onOk();
        return { destroy: jest.fn(), update: jest.fn() };
      });

    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(screen.getByText("Chain One")).toBeInTheDocument();
    });

    const enabledCheckbox = document.querySelector<HTMLInputElement>(
      'tbody input[type="checkbox"]:not([disabled])',
    );
    expect(enabledCheckbox).toBeTruthy();
    fireEvent.click(enabledCheckbox!);

    const deleteButton = screen.getByTestId("import-instructions-delete");
    await waitFor(() => {
      expect(deleteButton).not.toBeDisabled();
    });

    fireEvent.click(deleteButton);

    await waitFor(() => {
      expect(mockApi.deleteImportInstructions).toHaveBeenCalled();
    });

    confirmSpy.mockRestore();
  });

  it("UploadInstructionsModal: selecting file enables Upload button and triggers uploadImportInstructions", async () => {
    mockApi.uploadImportInstructions.mockResolvedValue([
      {
        id: "c1",
        name: "Chain 1",
        entityType: "CHAIN",
        status: "CREATED",
        errorMessage: null,
      },
    ]);

    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByTestId("import-instructions-upload"));

    await waitFor(() => {
      expect(screen.getByTestId("dragger")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("dragger"));

    await waitFor(() => {
      const uploadButtons = screen
        .queryAllByRole("button", { name: /^upload$/i })
        .filter((b) => b.closest(".ant-modal-footer"));
      const enabledUpload = uploadButtons.find(
        (b) => !(b as HTMLButtonElement).disabled,
      );
      expect(enabledUpload).toBeDefined();
    });

    const uploadButton = screen
      .queryAllByRole("button", { name: /^upload$/i })
      .find(
        (b) =>
          b.closest(".ant-modal-footer") && !(b as HTMLButtonElement).disabled,
      );
    fireEvent.click(uploadButton!);

    await waitFor(() => {
      expect(mockApi.uploadImportInstructions).toHaveBeenCalled();
    });
  }, 15000);

  it("UploadInstructionsModal: Close button refetches instructions", async () => {
    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalledTimes(1);
    });

    fireEvent.click(screen.getByTestId("import-instructions-upload"));

    await waitFor(() => {
      expect(
        screen.getByText("Upload Instructions (yaml, yml)"),
      ).toBeInTheDocument();
    });

    // The footer "Close" button has text content "Close" (the X icon button has no text)
    fireEvent.click(screen.getByText("Close"));

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalledTimes(2);
    });
  });

  it("AddInstructionModal: calls requestFailed when addImportInstruction throws", async () => {
    mockApi.addImportInstruction.mockRejectedValue(new Error("server error"));

    const { ImportInstructions } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await waitFor(() => {
      expect(mockApi.getImportInstructions).toHaveBeenCalled();
    });

    fireEvent.click(screen.getByRole("button", { name: /^add$/i }));

    const idInput = screen.getByLabelText("Id");
    fireEvent.change(idInput, { target: { value: "some-id" } });

    const addInModalButton = screen
      .getAllByRole("button", { name: /^add$/i })
      .find((b) => b.closest(".ant-modal-footer"));
    expect(addInModalButton).toBeDefined();
    fireEvent.click(addInModalButton!);

    await waitFor(() => {
      expect(mockApi.addImportInstruction).toHaveBeenCalled();
    });

    await waitFor(() => {
      expect(mockRequestFailed).toHaveBeenCalled();
    });
  }, 15000);
});

describe("buildTableData", () => {
  it("returns empty array for undefined input", async () => {
    const { buildTableData } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    expect(buildTableData(undefined)).toEqual([]);
  });

  it("returns three group rows for empty instructions", async () => {
    const { buildTableData } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );

    const result = buildTableData({
      chains: { ignore: [], override: [], delete: [] },
      services: { ignore: [], delete: [] },
      specificationGroups: { ignore: [], delete: [] },
      specifications: { ignore: [], delete: [] },
      commonVariables: { ignore: [], delete: [] },
    });

    expect(result).toHaveLength(3);
    expect(result[0].key).toBe("Chain");
    expect(result[1].key).toBe("Service");
    expect(result[2].key).toBe("Common Variable");
    expect(result[0].isGroup).toBe(true);
    result.forEach((r) => expect(r.children).toBeUndefined());
  });

  it("maps chain-override into Chains group with OVERRIDE action", async () => {
    const { buildTableData } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );
    const { ImportInstructionAction, ImportEntityType } = await import(
      "../../../src/api/apiTypes"
    );

    const result = buildTableData({
      chains: {
        ignore: [],
        override: [{ id: "c1", name: "Chain One" }],
        delete: [],
      },
      services: { ignore: [], delete: [] },
      specificationGroups: { ignore: [], delete: [] },
      specifications: { ignore: [], delete: [] },
      commonVariables: { ignore: [], delete: [] },
    });

    const chainGroup = result[0];
    expect(chainGroup.children).toBeDefined();
    expect(chainGroup.children).toHaveLength(1);
    const child = chainGroup.children![0];
    expect(child.key).toBe("Chain-OVERRIDE-c1");
    expect(child.action).toBe(ImportInstructionAction.OVERRIDE);
    expect(child.entityTypeForApi).toBe(ImportEntityType.CHAIN);
    expect(child.isGroup).toBe(false);
  });

  it("maps chain-ignore into Chains group with IGNORE action", async () => {
    const { buildTableData } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );
    const { ImportInstructionAction } = await import(
      "../../../src/api/apiTypes"
    );

    const result = buildTableData({
      chains: {
        ignore: [{ id: "c2" }],
        override: [],
        delete: [],
      },
      services: { ignore: [], delete: [] },
      specificationGroups: { ignore: [], delete: [] },
      specifications: { ignore: [], delete: [] },
      commonVariables: { ignore: [], delete: [] },
    });

    const child = result[0].children![0];
    expect(child.key).toBe("Chain-IGNORE-c2");
    expect(child.action).toBe(ImportInstructionAction.IGNORE);
    expect(child.name).toBe("c2");
  });

  it("maps service-delete into Services group with DELETE action", async () => {
    const { buildTableData } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );
    const { ImportInstructionAction } = await import(
      "../../../src/api/apiTypes"
    );

    const result = buildTableData({
      chains: { ignore: [], override: [], delete: [] },
      services: { ignore: [], delete: [{ id: "s1", name: "Svc One" }] },
      specificationGroups: { ignore: [], delete: [] },
      specifications: { ignore: [], delete: [] },
      commonVariables: { ignore: [], delete: [] },
    });

    const serviceGroup = result[1];
    expect(serviceGroup.children).toHaveLength(1);
    expect(serviceGroup.children![0].action).toBe(
      ImportInstructionAction.DELETE,
    );
    expect(serviceGroup.children![0].key).toBe("Service-DELETE-s1");
  });

  it("maps commonVariable-ignore into Common Variable group", async () => {
    const { buildTableData } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );
    const { ImportInstructionAction, ImportEntityType } = await import(
      "../../../src/api/apiTypes"
    );

    const result = buildTableData({
      chains: { ignore: [], override: [], delete: [] },
      services: { ignore: [], delete: [] },
      specificationGroups: { ignore: [], delete: [] },
      specifications: { ignore: [], delete: [] },
      commonVariables: { ignore: [{ id: "v1" }], delete: [] },
    });

    const varGroup = result[2];
    expect(varGroup.children).toHaveLength(1);
    expect(varGroup.children![0].action).toBe(ImportInstructionAction.IGNORE);
    expect(varGroup.children![0].entityTypeForApi).toBe(
      ImportEntityType.COMMON_VARIABLE,
    );
    expect(varGroup.children![0].key).toBe("Common Variable-IGNORE-v1");
  });

  it("maps chain-delete into Chains group with DELETE action", async () => {
    const { buildTableData } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );
    const { ImportInstructionAction, ImportEntityType } = await import(
      "../../../src/api/apiTypes"
    );

    const result = buildTableData({
      chains: {
        ignore: [],
        override: [],
        delete: [{ id: "c1", name: "Chain Del" }],
      },
      services: { ignore: [], delete: [] },
      specificationGroups: { ignore: [], delete: [] },
      specifications: { ignore: [], delete: [] },
      commonVariables: { ignore: [], delete: [] },
    });

    const chainGroup = result[0];
    expect(chainGroup.children).toHaveLength(1);
    expect(chainGroup.children![0].key).toBe("Chain-DELETE-c1");
    expect(chainGroup.children![0].action).toBe(ImportInstructionAction.DELETE);
    expect(chainGroup.children![0].entityTypeForApi).toBe(
      ImportEntityType.CHAIN,
    );
  });

  it("maps service-ignore into Services group with IGNORE action", async () => {
    const { buildTableData } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );
    const { ImportInstructionAction, ImportEntityType } = await import(
      "../../../src/api/apiTypes"
    );

    const result = buildTableData({
      chains: { ignore: [], override: [], delete: [] },
      services: { ignore: [{ id: "s1", name: "Svc Ign" }], delete: [] },
      specificationGroups: { ignore: [], delete: [] },
      specifications: { ignore: [], delete: [] },
      commonVariables: { ignore: [], delete: [] },
    });

    const serviceGroup = result[1];
    expect(serviceGroup.children).toHaveLength(1);
    expect(serviceGroup.children![0].key).toBe("Service-IGNORE-s1");
    expect(serviceGroup.children![0].action).toBe(
      ImportInstructionAction.IGNORE,
    );
    expect(serviceGroup.children![0].entityTypeForApi).toBe(
      ImportEntityType.SERVICE,
    );
  });

  it("maps commonVariable-delete into Common Variable group with DELETE action", async () => {
    const { buildTableData } = await import(
      "../../../src/components/admin_tools/ImportInstructions"
    );
    const { ImportInstructionAction, ImportEntityType } = await import(
      "../../../src/api/apiTypes"
    );

    const result = buildTableData({
      chains: { ignore: [], override: [], delete: [] },
      services: { ignore: [], delete: [] },
      specificationGroups: { ignore: [], delete: [] },
      specifications: { ignore: [], delete: [] },
      commonVariables: { ignore: [], delete: [{ id: "v1", name: "Var Del" }] },
    });

    const varGroup = result[2];
    expect(varGroup.children).toHaveLength(1);
    expect(varGroup.children![0].key).toBe("Common Variable-DELETE-v1");
    expect(varGroup.children![0].action).toBe(ImportInstructionAction.DELETE);
    expect(varGroup.children![0].entityTypeForApi).toBe(
      ImportEntityType.COMMON_VARIABLE,
    );
  });
});
