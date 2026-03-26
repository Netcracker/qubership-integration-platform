/**
 * @jest-environment jsdom
 */
import React, { PropsWithChildren } from "react";
import { render, screen, fireEvent, within, act } from "@testing-library/react";
import "@testing-library/jest-dom";
import { Modal } from "antd";
import type { GeneralImportInstructions } from "../../../src";
import {
  ImportInstructions,
  buildTableData,
} from "../../../src/components/admin_tools/ImportInstructions";
import { ImportInstructionAction } from "../../../src/api/apiTypes";
import { UserPermissionsContext } from "../../../src/permissions/UserPermissionsContext.tsx";
import { getAllPermissions } from "../../../src/permissions/funcs.ts";

Object.defineProperty(URL, "createObjectURL", {
  writable: true,
  value: jest.fn().mockReturnValue("blob:test"),
});

Object.defineProperty(URL, "revokeObjectURL", {
  writable: true,
  value: jest.fn(),
});

jest.mock("antd", () => {
  const actual: Record<string, unknown> = jest.requireActual("antd");
  const mock: Record<string, unknown> = jest.requireActual(
    "../../__mocks__/LightweightTable",
  );
  return { ...actual, Table: mock.LightweightTable };
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

const mockApiModule: { api: Record<string, jest.Mock> } = jest.requireMock(
  "../../../src/api/api",
);
const mockApi = mockApiModule.api;

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
    header: "header",
    title: "title",
    icon: "icon",
    iconInline: "icon-inline",
    actions: "actions",
    searchField: "search-field",
    "table-wrapper": "table-wrapper",
  },
}));

jest.mock("../../../src/components/InlineEdit.module.css", () => ({
  __esModule: true,
  default: {
    inlineEditValueWrap: "inline-edit-value-wrap",
    inlineEditButtons: "inline-edit-buttons",
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

async function flushPromises(): Promise<void> {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe("ImportInstructions", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockApi.getImportInstructions.mockResolvedValue(sampleInstructions);
    mockApi.exportImportInstructions.mockResolvedValue(
      new File(["content"], "qip-import-instructions.yaml"),
    );
  });

  afterEach(() => {
    // restoreAllMocks is needed to undo jest.spyOn (e.g. confirmSpy)
    jest.restoreAllMocks();
  });

  it("renders page title and loads instructions", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    expect(screen.getByText(/import/i)).toBeInTheDocument();
    expect(screen.getByText(/instructions/i)).toBeInTheDocument();

    await flushPromises();
    expect(screen.getByText("Chains")).toBeInTheDocument();
    expect(screen.getByText("Services")).toBeInTheDocument();
    expect(screen.getByText("Common Variables")).toBeInTheDocument();
    expect(mockApi.getImportInstructions).toHaveBeenCalledTimes(1);
  });

  it("shows Add, Export, Upload buttons", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(screen.getByRole("button", { name: /add/i })).toBeInTheDocument();
    expect(screen.getByPlaceholderText("Search...")).toBeInTheDocument();
  });

  it("displays chain and variable items from instructions", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(screen.getByText("Chains")).toBeInTheDocument();
    expect(screen.getByText("Chain One")).toBeInTheDocument();
    expect(screen.getByText("Var One")).toBeInTheDocument();
  });

  it("fetches export on Export button click", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(mockApi.getImportInstructions).toHaveBeenCalled();

    const exportButton = screen.getByTestId("import-instructions-export");
    fireEvent.click(exportButton);

    await flushPromises();
    expect(mockApi.exportImportInstructions).toHaveBeenCalled();
  });

  it("opens Add modal when Add button is clicked", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    const addButton = screen.getByRole("button", { name: /^add$/i });
    fireEvent.click(addButton);

    expect(screen.getByText("Add Instruction")).toBeInTheDocument();
  });

  it("opens Upload modal when Upload button is clicked", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    fireEvent.click(screen.getByTestId("import-instructions-upload"));

    expect(
      screen.getByText("Upload Instructions (yaml, yml)"),
    ).toBeInTheDocument();
  });

  it("displays column settings button", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(
      screen.getByTestId("import-instructions-column-settings"),
    ).toBeInTheDocument();
  });

  it("calls requestFailed when fetchInstructions throws", async () => {
    mockApi.getImportInstructions.mockRejectedValue(new Error("Network error"));
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(mockRequestFailed).toHaveBeenCalled();
  });

  it("calls requestFailed when handleExport throws", async () => {
    mockApi.exportImportInstructions.mockRejectedValue(
      new Error("Export failed"),
    );
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    fireEvent.click(screen.getByTestId("import-instructions-export"));

    await flushPromises();
    expect(mockRequestFailed).toHaveBeenCalled();
  });

  it("Delete button is disabled when no rows are selected", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    const deleteButton = screen.getByTestId("import-instructions-delete");
    expect(deleteButton).toBeDisabled();
  });

  it("search input filters visible data by id", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(screen.getByText("Chain One")).toBeInTheDocument();
    expect(screen.getByText("Var One")).toBeInTheDocument();

    const searchInput = screen.getByPlaceholderText("Search...");
    fireEvent.change(searchInput, { target: { value: "chain" } });

    expect(screen.getByText("Chain One")).toBeInTheDocument();
  });

  it("search with no match still shows group headers", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(screen.getByText("Chains")).toBeInTheDocument();
    const searchInput = screen.getByPlaceholderText("Search...");
    fireEvent.change(searchInput, { target: { value: "nonexistent-xyz" } });
    expect(screen.getByText("Chains")).toBeInTheDocument();
  });

  it("opens AddInstructionModal with form when Add button is clicked", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    const addButton = screen
      .getAllByRole("button", { name: /^add$/i })
      .find((b) => !b.closest('[role="dialog"]'));
    expect(addButton).toBeDefined();
    fireEvent.click(addButton!);

    const dialog = screen.getByRole("dialog");
    expect(within(dialog).getByPlaceholderText("Enter id")).toBeInTheDocument();
  });

  it("UploadInstructionsModal Upload button is disabled when no file selected", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
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
    const createObjectURLMock = (URL as unknown as Record<string, jest.Mock>)
      .createObjectURL;
    createObjectURLMock.mockClear();

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    fireEvent.click(screen.getByTestId("import-instructions-export"));

    await flushPromises();
    expect(mockApi.exportImportInstructions).toHaveBeenCalled();
    expect(createObjectURLMock).toHaveBeenCalled();
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

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(screen.getByText("Tagged Chain")).toBeInTheDocument();
    expect(screen.getByText("tag1")).toBeInTheDocument();
    expect(screen.getByText("tag2")).toBeInTheDocument();
    expect(screen.getByText(/2023-11-1/)).toBeInTheDocument();
  });

  it("inline editing Action: click trigger shows Select, selecting Ignore calls updateImportInstruction", async () => {
    mockApi.updateImportInstruction.mockResolvedValue({});
    mockApi.getImportInstructions.mockResolvedValueOnce({
      chains: {
        ignore: [],
        override: [{ id: "chain-1", name: "Chain One" }],
        delete: [],
      },
      services: { ignore: [], delete: [] },
      specificationGroups: { delete: [], ignore: [] },
      specifications: { delete: [], ignore: [] },
      commonVariables: { ignore: [], delete: [] },
    });

    const { container } = render(<ImportInstructions />, {
      wrapper: ContextProviders,
    });

    await flushPromises();
    expect(screen.getByText("Chain One")).toBeInTheDocument();

    const cellTriggers = document.querySelectorAll(".inline-edit-value-wrap");
    expect(cellTriggers.length).toBeGreaterThan(0);

    fireEvent.click(cellTriggers[0]);

    await flushPromises();
    expect(container.querySelector(".ant-select")).toBeTruthy();

    const ignoreOption = screen.getByText("Ignore");
    fireEvent.click(ignoreOption);

    await flushPromises();
    expect(mockApi.updateImportInstruction).toHaveBeenCalledWith(
      expect.objectContaining({ id: "chain-1", action: "IGNORE" }),
    );
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

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(screen.getByText("Chain Two")).toBeInTheDocument();

    const cellTriggers = document.querySelectorAll(".inline-edit-value-wrap");
    expect(cellTriggers.length).toBeGreaterThanOrEqual(2);

    fireEvent.click(cellTriggers[1]);

    await flushPromises();
    const input = screen.getByDisplayValue("other-chain");
    fireEvent.change(input, { target: { value: "other-chain-updated" } });

    const applyButton = document.querySelector(".inline-edit-buttons button");
    expect(applyButton).toBeTruthy();
    fireEvent.click(applyButton!);

    await flushPromises();
    expect(mockApi.updateImportInstruction).toHaveBeenCalledWith(
      expect.objectContaining({
        id: "chain-2",
        action: "OVERRIDE",
      }),
    );
  });

  it("handleDelete: select row, confirm delete modal, deleteImportInstructions called", async () => {
    mockApi.deleteImportInstructions.mockResolvedValue(undefined);

    jest.spyOn(Modal, "confirm").mockImplementation((props = {}) => {
        const onOk = props.onOk as
          | ((...args: unknown[]) => unknown)
          | undefined;
        if (onOk) onOk();
        return { destroy: jest.fn(), update: jest.fn() };
      });

    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(screen.getByText("Chain One")).toBeInTheDocument();

    const enabledCheckbox = document.querySelector<HTMLInputElement>(
      'tbody input[type="checkbox"]:not([disabled])',
    );
    expect(enabledCheckbox).toBeTruthy();
    fireEvent.click(enabledCheckbox!);

    const deleteButton = screen.getByTestId("import-instructions-delete");
    expect(deleteButton).not.toBeDisabled();

    fireEvent.click(deleteButton);

    await flushPromises();
    expect(mockApi.deleteImportInstructions).toHaveBeenCalled();
  });

  it("UploadInstructionsModal: Close button refetches instructions", async () => {
    render(<ImportInstructions />, { wrapper: ContextProviders });

    await flushPromises();
    expect(mockApi.getImportInstructions).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByTestId("import-instructions-upload"));
    expect(
      screen.getByText("Upload Instructions (yaml, yml)"),
    ).toBeInTheDocument();

    fireEvent.click(screen.getByText("Close"));

    await flushPromises();
    expect(mockApi.getImportInstructions).toHaveBeenCalledTimes(2);
  });

});

describe("buildTableData", () => {
  it("returns empty array for undefined input", () => {
    expect(buildTableData(undefined)).toEqual([]);
  });

  it("returns three group rows for empty instructions", () => {
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

  it("maps chain-override into Chains group with OVERRIDE action", () => {
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
    expect(child.entityType).toBe("Chain");
    expect(child.isGroup).toBe(false);
  });

  it("maps chain-ignore into Chains group with IGNORE action", () => {
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

  it("maps service-delete into Services group with DELETE action", () => {
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

  it("maps commonVariable-ignore into Common Variable group", () => {
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
    expect(varGroup.children![0].entityType).toBe("Common Variable");
    expect(varGroup.children![0].key).toBe("Common Variable-IGNORE-v1");
  });

  it("maps chain-delete into Chains group with DELETE action", () => {
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
    expect(chainGroup.children![0].entityType).toBe("Chain");
  });

  it("maps service-ignore into Services group with IGNORE action", () => {
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
    expect(serviceGroup.children![0].entityType).toBe("Service");
  });

  it("maps commonVariable-delete into Common Variable group with DELETE action", () => {
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
    expect(varGroup.children![0].entityType).toBe("Common Variable");
  });
});
