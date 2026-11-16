/**
 * @jest-environment jsdom
 */
// The import modal serves two backends, and its file picker is the one place that has to tell them
// apart. The catalog unpacks a zip server side (`MultipartFileUtils.extractArchives`); the VS Code
// extension has no unpacker, and an archive that reaches its import is refused as a format whose
// protocol cannot be detected. So the list of offered extensions narrows where the api is the
// extension's, and these cases pin that it narrows there and nowhere else.
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from "@testing-library/react";
import "@testing-library/jest-dom";
import { AddSpecificationModal } from "../../../src/components/services/modals/AddSpecificationModal.tsx";
import {
  ApiSpecificationFormat,
  ApiSpecificationType,
  ElementWithChainName,
  ImportSpecificationResult,
  SpecApiFile,
} from "../../../src/api/apiTypes.ts";
import { openSelect } from "../../helpers/antdSelect.ts";

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

// A class of its own rather than the real one: the modal only asks `api instanceof
// VSCodeExtensionApi`, and the real module pulls the whole api layer in behind it.
jest.mock("../../../src/api/rest/vscodeExtensionApi.ts", () => {
  class VSCodeExtensionApi {}
  return { __esModule: true, VSCodeExtensionApi, isVsCode: false };
});

function doneImport(
  overrides: Partial<ImportSpecificationResult> = {},
): ImportSpecificationResult {
  return {
    id: "import-1",
    done: true,
    specificationGroupId: "group-new",
    ...overrides,
  };
}

type ApiCalls = {
  getRootFolders: jest.Mock;
  getElementsByType: jest.Mock;
  getSpecApiFiles: jest.Mock;
  importSpecification: jest.Mock;
  importSpecificationGroup: jest.Mock;
  getImportSpecificationResult: jest.Mock;
  generateApiSpecification: jest.Mock;
  modifyHttpTriggerProperties: jest.Mock;
  readSpecificationFileContent: jest.Mock;
};

function createApiCalls(): ApiCalls {
  return {
    getRootFolders: jest.fn().mockResolvedValue([]),
    getElementsByType: jest.fn().mockResolvedValue([]),
    getSpecApiFiles: jest.fn().mockResolvedValue([]),
    importSpecification: jest.fn().mockResolvedValue(doneImport()),
    importSpecificationGroup: jest.fn().mockResolvedValue(doneImport()),
    getImportSpecificationResult: jest.fn().mockResolvedValue(doneImport()),
    generateApiSpecification: jest
      .fn()
      .mockResolvedValue(new File(["openapi: 3.0.0"], "openapi.yaml")),
    modifyHttpTriggerProperties: jest.fn().mockResolvedValue(undefined),
    readSpecificationFileContent: jest.fn().mockResolvedValue("openapi: 3.0.0"),
  };
}

let mockApiCalls = createApiCalls();
let mockApi: unknown = mockApiCalls;

jest.mock("../../../src/api/api.ts", () => ({
  get api() {
    return mockApi;
  },
}));

const mockWarning = jest.fn();
jest.mock("../../../src/misc/antd-app.ts", () => ({
  message: {
    warning: (text?: string) => mockWarning(text),
    error: jest.fn(),
    success: jest.fn(),
  },
}));

const mockRequestFailed = jest.fn();
const mockNotifyWarning = jest.fn();

jest.mock("../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: (description: string, error: unknown) =>
      mockRequestFailed(description, error),
    warning: (message: string, description?: string) =>
      mockNotifyWarning(message, description),
  }),
}));

const mockCloseContainingModal = jest.fn();

jest.mock("../../../src/ModalContextProvider", () => ({
  useModalContext: () => ({
    closeContainingModal: () => mockCloseContainingModal(),
  }),
}));

const { VSCodeExtensionApi } = jest.requireMock<{
  VSCodeExtensionApi: new () => unknown;
}>("../../../src/api/rest/vscodeExtensionApi.ts");

function renderModal(
  context: "catalog" | "extension",
  props: Partial<{
    groupMode: boolean;
    isImplementedService: boolean;
    systemId?: string;
    specificationGroupId?: string;
    onSuccess?: () => void;
  }> = {},
  overrides: Partial<ApiCalls> = {},
) {
  mockApiCalls = { ...createApiCalls(), ...overrides };
  mockApi =
    context === "extension"
      ? Object.assign(Object.create(VSCodeExtensionApi.prototype), mockApiCalls)
      : mockApiCalls;
  // Not group mode: the extension context otherwise opens on its API-contract tab, and the file
  // picker this suite is about lives on the file tab.
  return render(
    <AddSpecificationModal
      systemId="service-1"
      specificationGroupId="group-1"
      groupMode={false}
      {...props}
    />,
  );
}

function trigger(
  overrides: Partial<ElementWithChainName> = {},
): ElementWithChainName {
  return {
    id: "trigger-1",
    name: "Trigger 1",
    description: "",
    type: "http-trigger",
    chainId: "chain-1",
    chainName: "Chain 1",
    arentElementId: "",
    originalId: "",
    properties: {},
    children: [],
    swimlaneId: "",
    mandatoryChecksPassed: true,
    ...overrides,
  };
}

const apiFiles: SpecApiFile[] = [
  {
    id: "api-1",
    name: "Orders API",
    protocol: "openapi",
    specificationFilePath: "contracts/orders.yaml",
    fileUri: "file:///contracts/orders.yaml",
  },
];

async function findApiContractOption(): Promise<HTMLElement> {
  return waitFor(() => {
    const option = document.querySelector<HTMLElement>(
      '.ant-select-item-option[label="Orders API"]',
    );
    if (!option) throw new Error("the API contract option is missing");
    return option;
  });
}

// The modal renders into a portal on `document.body`, not into the container `render` returns.
function fileInput(): HTMLInputElement {
  const input = document.body.querySelector('input[type="file"]');
  if (!input) {
    throw new Error("the modal rendered no file input");
  }
  return input as HTMLInputElement;
}

describe("AddSpecificationModal", () => {
  beforeEach(() => {
    mockWarning.mockClear();
    mockRequestFailed.mockClear();
    mockNotifyWarning.mockClear();
    mockCloseContainingModal.mockClear();
  });

  it("should offer an archive when the api is the catalog's", async () => {
    renderModal("catalog");

    expect(
      await screen.findByText(/Supported file types:.*\.zip/),
    ).toBeInTheDocument();
    expect(fileInput().accept).toContain(".zip");
  });

  it("should offer no archive when the api is the extension's", async () => {
    renderModal("extension");

    const hint = await screen.findByText(/Supported file types:/);
    expect(hint).toHaveTextContent(".wsdl");
    expect(hint).not.toHaveTextContent(".zip");
    const accept = fileInput().accept;
    expect(accept).toContain(".graphqls");
    expect(accept).not.toContain(".zip");
  });

  it("should refuse an archive by name when the api is the extension's", async () => {
    renderModal("extension");
    await screen.findByText(/Supported file types:/);

    fireEvent.change(fileInput(), {
      target: { files: [new File(["PK"], "orders.zip")] },
    });

    // `validateFiles` names the file and the list it was checked against, so the message itself
    // tells the user an archive is not one of them.
    await waitFor(() =>
      expect(mockWarning).toHaveBeenCalledWith(
        expect.stringContaining("Unsupported file type(s): orders.zip"),
      ),
    );
    expect(mockWarning.mock.calls[0][0]).not.toContain(".zip,");
  });

  it("should show the file content with no tab strip when the file tab is the only tab", async () => {
    renderModal("catalog");
    expect(
      await screen.findByText(/Supported file types:/),
    ).toBeInTheDocument();
    expect(screen.queryByRole("tab")).toBeNull();
  });

  it("should place Cancel before Add", async () => {
    renderModal("catalog");
    const cancel = screen.getByRole("button", { name: "Cancel" });
    const add = screen.getByRole("button", { name: "Add" });
    expect(
      cancel.compareDocumentPosition(add) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
    fireEvent.click(cancel);
    expect(mockCloseContainingModal).toHaveBeenCalledTimes(1);
  });

  it("should label the file submit Add once one file is chosen", async () => {
    renderModal("catalog");
    await screen.findByText(/Supported file types:/);
    fireEvent.change(fileInput(), {
      target: { files: [new File(["a"], "a.yaml")] },
    });
    expect(
      await screen.findByRole("button", { name: "Add" }),
    ).toBeInTheDocument();
  });

  it("should keep the Add label when several files are chosen", async () => {
    renderModal("catalog");
    await screen.findByText(/Supported file types:/);
    fireEvent.change(fileInput(), {
      target: { files: [new File(["a"], "a.yaml"), new File(["b"], "b.yaml")] },
    });
    expect(
      await screen.findByRole("button", { name: "Add" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /Add \d+ Files/ })).toBeNull();
  });

  it("should name the file and chain tabs for adding", async () => {
    renderModal("catalog", { isImplementedService: true });
    expect(
      await screen.findByRole("tab", { name: "From File" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "From Chain" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("tab", { name: "From Chain" }));
    expect(screen.getByRole("button", { name: "Clear" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add" })).toBeInTheDocument();
  });

  it("should name the API tab for adding", async () => {
    renderModal("extension", { groupMode: true });
    expect(
      await screen.findByRole("tab", { name: "From API" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add" })).toBeInTheDocument();
  });

  it("should import a chosen file, close the modal, and report warnings", async () => {
    const onSuccess = jest.fn();
    let resolveImport!: (value: ImportSpecificationResult) => void;
    const importPromise = new Promise<ImportSpecificationResult>((resolve) => {
      resolveImport = resolve;
    });
    renderModal(
      "catalog",
      { onSuccess },
      {
        importSpecification: jest.fn().mockReturnValue(importPromise),
        getImportSpecificationResult: jest
          .fn()
          .mockResolvedValue(doneImport({ warningMessage: "duplicate path" })),
      },
    );

    expect(await screen.findByText("Add Specification")).toBeInTheDocument();
    fireEvent.change(fileInput(), {
      target: { files: [new File(["a"], "a.yaml")] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(await screen.findByText("Uploading...")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Add" })).toBeDisabled();

    await act(async () => {
      resolveImport(doneImport());
    });

    await waitFor(() =>
      expect(mockCloseContainingModal).toHaveBeenCalledTimes(1),
    );
    expect(mockApiCalls.importSpecification).toHaveBeenCalledWith(
      "group-1",
      [expect.objectContaining({ name: "a.yaml" })],
      "service-1",
    );
    expect(onSuccess).toHaveBeenCalledTimes(1);
    expect(mockNotifyWarning).toHaveBeenCalledWith(
      "Import completed with warnings",
      "duplicate path",
    );
  });

  it("should report a failed file import and let the user retry", async () => {
    renderModal(
      "catalog",
      {},
      {
        importSpecification: jest
          .fn()
          .mockRejectedValue(new Error("import down")),
      },
    );

    await screen.findByText(/Supported file types:/);
    fireEvent.change(fileInput(), {
      target: { files: [new File(["a"], "a.yaml")] },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "import down",
        expect.any(Error),
      ),
    );
    expect(mockCloseContainingModal).not.toHaveBeenCalled();
    expect(screen.getByRole("button", { name: "Add" })).toBeEnabled();
  });

  it("should name a group after the chosen file and import it under an edited name", async () => {
    renderModal("catalog", { groupMode: true });

    expect(
      await screen.findByText("Add Specification Group"),
    ).toBeInTheDocument();
    fireEvent.change(fileInput(), {
      target: { files: [new File(["{}"], "orders.json")] },
    });

    const nameInput = screen.getByPlaceholderText("Enter group name");
    await waitFor(() => expect(nameInput).toHaveValue("orders"));

    fireEvent.change(nameInput, { target: { value: "Orders API" } });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    await waitFor(() =>
      expect(mockApiCalls.importSpecificationGroup).toHaveBeenCalledWith(
        "service-1",
        "Orders API",
        [expect.objectContaining({ name: "orders.json" })],
      ),
    );
    expect(mockApiCalls.importSpecification).not.toHaveBeenCalled();
    expect(mockCloseContainingModal).toHaveBeenCalledTimes(1);
  });

  it("should filter chain triggers and generate a specification from the valid selection", async () => {
    const onSuccess = jest.fn();
    renderModal(
      "catalog",
      { isImplementedService: true, onSuccess },
      {
        getRootFolders: jest
          .fn()
          .mockResolvedValue([{ id: "chain-1" }, { id: "chain-2" }]),
        getElementsByType: jest.fn().mockResolvedValue([
          trigger({ id: "trigger-a", name: "Trigger A" }),
          trigger({
            id: "trigger-b",
            name: "Trigger B",
            chainId: "chain-2",
            chainName: "Chain 2",
            properties: { httpMethodRestrict: "GET,POST" },
          }),
          trigger({
            id: "trigger-bound",
            name: "Bound Trigger",
            properties: {
              integrationOperationId: "op-1",
              integrationSpecificationGroupId: "group-1",
            },
          }),
          trigger({
            id: "trigger-empty",
            name: "Empty Trigger",
            properties: undefined,
          }),
          trigger({
            id: "trigger-out",
            name: "Outside Trigger",
            chainId: "chain-3",
            chainName: "Chain 3",
          }),
        ]),
      },
    );

    fireEvent.click(await screen.findByRole("tab", { name: "From Chain" }));
    expect(await screen.findByText("Trigger A")).toBeInTheDocument();
    expect(screen.getByText("Trigger B")).toBeInTheDocument();
    expect(screen.queryByText("Bound Trigger")).toBeNull();
    expect(screen.queryByText("Empty Trigger")).toBeNull();
    expect(screen.queryByText("Outside Trigger")).toBeNull();
    expect(mockApiCalls.getElementsByType).toHaveBeenCalledWith(
      "any-chain",
      "http-trigger",
    );
    expect(mockApiCalls.getRootFolders).toHaveBeenCalledWith(
      JSON.stringify({
        chainsWithHttpTriggers: true,
        externalRoutesOnly: true,
      }),
      "",
    );

    const externalRoutes = screen.getByRole("checkbox", {
      name: "External routes only",
    });
    fireEvent.click(externalRoutes);
    await waitFor(() =>
      expect(mockApiCalls.getRootFolders).toHaveBeenCalledWith(
        JSON.stringify({
          chainsWithHttpTriggers: true,
          externalRoutesOnly: false,
        }),
        "",
      ),
    );

    fireEvent.change(screen.getByPlaceholderText("Enter group name"), {
      target: { value: "Generated API" },
    });

    const invalidCard = screen
      .getByText("Trigger B")
      .closest<HTMLElement>(".ant-card");
    if (!invalidCard) throw new Error("the invalid trigger card is missing");
    fireEvent.click(within(invalidCard).getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    expect(
      await screen.findByText(
        "Single HTTP method has to be specified for next trigger(s):",
      ),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Trigger B on Chain 2" }),
    ).toBeInTheDocument();
    expect(mockApiCalls.generateApiSpecification).not.toHaveBeenCalled();

    const closeAlert = document.querySelector<HTMLElement>(
      ".ant-alert-close-icon",
    );
    if (!closeAlert) throw new Error("the validation alert has no close icon");
    fireEvent.click(closeAlert);
    await waitFor(() =>
      expect(
        screen.queryByText(
          "Single HTTP method has to be specified for next trigger(s):",
        ),
      ).toBeNull(),
    );

    const validCard = screen
      .getByText("Trigger A")
      .closest<HTMLElement>(".ant-card");
    if (!validCard) throw new Error("the valid trigger card is missing");
    fireEvent.click(within(validCard).getByRole("checkbox"));
    expect(screen.getByRole("button", { name: "Add" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Clear" }));
    expect(screen.getByRole("button", { name: "Add" })).toBeDisabled();
    expect(within(validCard).getByRole("checkbox")).not.toBeChecked();

    fireEvent.click(within(validCard).getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    await waitFor(() =>
      expect(mockApiCalls.generateApiSpecification).toHaveBeenCalledWith(
        [],
        [],
        ["chain-1"],
        ["trigger-a"],
        false,
        ApiSpecificationType.OpenAPI,
        ApiSpecificationFormat.YAML,
      ),
    );
    await waitFor(() =>
      expect(mockCloseContainingModal).toHaveBeenCalledTimes(1),
    );
    expect(mockApiCalls.modifyHttpTriggerProperties).toHaveBeenCalledWith(
      "any-chain",
      "group-new",
      ["trigger-a"],
    );
    expect(onSuccess).toHaveBeenCalledTimes(1);
  }, 60000);

  it("should report chain loading and generation failures", async () => {
    renderModal(
      "catalog",
      { isImplementedService: true },
      {
        getRootFolders: jest.fn().mockRejectedValue(new Error("folders down")),
        generateApiSpecification: jest
          .fn()
          .mockRejectedValue(new Error("generation down")),
      },
    );

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "folders down",
        expect.any(Error),
      ),
    );
    fireEvent.click(await screen.findByRole("tab", { name: "From Chain" }));
    expect(await screen.findByText("No chains found")).toBeInTheDocument();

    mockApiCalls.getRootFolders.mockResolvedValue([{ id: "chain-1" }]);
    mockApiCalls.getElementsByType.mockResolvedValue([
      trigger({ id: "trigger-a", name: "Trigger A" }),
    ]);
    fireEvent.click(
      screen.getByRole("checkbox", { name: "External routes only" }),
    );
    expect(await screen.findByText("Trigger A")).toBeInTheDocument();

    const card = screen
      .getByText("Trigger A")
      .closest<HTMLElement>(".ant-card");
    if (!card) throw new Error("the trigger card is missing");
    fireEvent.click(within(card).getByRole("checkbox"));
    fireEvent.click(screen.getByRole("button", { name: "Add" }));
    expect(await screen.findByText("Name is required")).toBeInTheDocument();
    expect(mockApiCalls.generateApiSpecification).not.toHaveBeenCalled();

    fireEvent.change(screen.getByPlaceholderText("Enter group name"), {
      target: { value: "Broken API" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "generation down",
        expect.any(Error),
      ),
    );
    expect(mockCloseContainingModal).not.toHaveBeenCalled();
  });

  it("should import a specification from the extension API contract", async () => {
    const onSuccess = jest.fn();
    renderModal(
      "extension",
      { groupMode: true, onSuccess },
      { getSpecApiFiles: jest.fn().mockResolvedValue(apiFiles) },
    );

    fireEvent.click(await screen.findByRole("tab", { name: "From API" }));
    await waitFor(() =>
      expect(mockApiCalls.getSpecApiFiles).toHaveBeenCalled(),
    );
    openSelect(document.body);
    fireEvent.click(await findApiContractOption());

    const nameInput = screen.getByPlaceholderText("Enter group name");
    await waitFor(() => expect(nameInput).toHaveValue("Orders API"));

    fireEvent.change(nameInput, { target: { value: "Orders API v2" } });
    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    await waitFor(() =>
      expect(mockApiCalls.importSpecificationGroup).toHaveBeenCalledWith(
        "service-1",
        "Orders API v2",
        [expect.objectContaining({ name: "orders.yaml" })],
      ),
    );
    expect(mockApiCalls.readSpecificationFileContent).toHaveBeenCalledWith(
      "file:///contracts/orders.yaml",
      "contracts/orders.yaml",
    );
    await waitFor(() =>
      expect(mockCloseContainingModal).toHaveBeenCalledTimes(1),
    );
    expect(onSuccess).toHaveBeenCalledTimes(1);
  });

  it("should report when API contract files cannot be loaded", async () => {
    renderModal(
      "extension",
      { groupMode: true },
      {
        getSpecApiFiles: jest
          .fn()
          .mockRejectedValue(new Error("contracts down")),
      },
    );

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "contracts down",
        expect.any(Error),
      ),
    );
    expect(
      await screen.findByText("No API contract files found"),
    ).toBeInTheDocument();
  });

  it("should report a failed API contract import and stay open", async () => {
    renderModal(
      "extension",
      { groupMode: true },
      {
        getSpecApiFiles: jest.fn().mockResolvedValue(apiFiles),
        readSpecificationFileContent: jest
          .fn()
          .mockRejectedValue(new Error("read failed")),
      },
    );

    await waitFor(() =>
      expect(document.querySelector(".ant-select-content")).not.toBeNull(),
    );
    openSelect(document.body);
    fireEvent.click(await findApiContractOption());
    await waitFor(() =>
      expect(screen.getByPlaceholderText("Enter group name")).toHaveValue(
        "Orders API",
      ),
    );

    fireEvent.click(screen.getByRole("button", { name: "Add" }));

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "read failed",
        expect.any(Error),
      ),
    );
    expect(mockCloseContainingModal).not.toHaveBeenCalled();
    expect(mockApiCalls.importSpecificationGroup).not.toHaveBeenCalled();
  });
});
