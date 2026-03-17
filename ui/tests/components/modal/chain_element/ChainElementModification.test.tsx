/**
 * @jest-environment jsdom
 */
import React from "react";
import { render, screen, waitFor, fireEvent } from "@testing-library/react";
import "@testing-library/jest-dom";
import type { ChainGraphNode } from "../../../../src/components/graph/nodes/ChainGraphNodeTypes";
import type { UserPermissions } from "../../../../src/permissions/types";
import { UserPermissionsContext } from "../../../../src/permissions/UserPermissionsContext";
import { ChainElementModification } from "../../../../src/components/modal/chain_element/ChainElementModification";

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
  useModalContext: () => ({ closeContainingModal: mockCloseContainingModal }),
}));

const mockUpdateElement = jest.fn().mockResolvedValue({
  id: "el-1",
  name: "My Script",
  description: "",
  type: "script",
  properties: {},
});
jest.mock("../../../../src/hooks/useElement", () => ({
  useElement: () => ({ updateElement: mockUpdateElement }),
}));

const mockNotificationService = {
  errorWithDetails: jest.fn(),
  requestFailed: jest.fn(),
};
jest.mock("../../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => mockNotificationService,
}));

const mockOpenElementDoc = jest.fn();
jest.mock("../../../../src/hooks/useDocumentation", () => ({
  useDocumentation: () => ({ openElementDoc: mockOpenElementDoc }),
}));

const mockShowModal = jest.fn();
jest.mock("../../../../src/Modals", () => ({
  useModalsContext: () => ({ showModal: mockShowModal }),
}));

jest.mock("../../../../src/hooks/useLibraryElement", () => ({
  useLibraryElement: () => ({
    isLoading: false,
    libraryElement: { title: "Script" },
  }),
}));

const mockScriptSchema =
  "type: object\ntitle: Script\ndescription: Script element\nproperties:\n  type:\n    type: string\n  name:\n    type: string\n  description:\n    type: string\n  properties:\n    type: object\nrequired: [type]";

const defaultSchemaModules = {
  "/node_modules/@netcracker/qip-schemas/assets/script.schema.yaml":
    mockScriptSchema,
};

let schemaModulesOverride: Record<string, string> | null = null;

jest.mock(
  "../../../../src/components/modal/chain_element/chainElementSchemaModules",
  () => ({
    getSchemaModules: () => schemaModulesOverride ?? defaultSchemaModules,
  }),
);

jest.mock("@rjsf/validator-ajv8", () => ({
  __esModule: true,
  default: {
    validateFormData: () => ({ errors: [] }),
  },
}));

jest.mock(
  "../../../../src/components/modal/chain_element/DescriptionTooltipFieldTemplate",
  () => ({
    __esModule: true,
    default: ({ children }: { children?: React.ReactNode }) => (
      <div data-testid="field-template">{children}</div>
    ),
  }),
);

jest.mock(
  "../../../../src/components/modal/chain_element/field/MappingField",
  () => ({
    __esModule: true,
    default: () => <div data-testid="mapping-field" />,
  }),
);

jest.mock(
  "../../../../src/components/modal/chain_element/field/CustomArrayField",
  () => ({
    __esModule: true,
    default: () => <div data-testid="custom-array-field" />,
  }),
);

jest.mock(
  "../../../../src/components/modal/chain_element/field/ScriptField",
  () => ({
    __esModule: true,
    default: () => <div data-testid="script-field" />,
  }),
);

jest.mock(
  "../../../../src/components/modal/chain_element/field/JsonField",
  () => ({
    __esModule: true,
    default: () => <div data-testid="json-field" />,
  }),
);

jest.mock(
  "../../../../src/components/modal/chain_element/field/CustomOneOfField",
  () => ({
    __esModule: true,
    default: () => <div data-testid="custom-one-of-field" />,
  }),
);

let formMockShouldTriggerOnChange = false;
jest.mock("@rjsf/antd", () => {
  const FormMock = React.forwardRef<
    { validateForm?: () => void },
    {
      schema?: unknown;
      formData?: unknown;
      onChange?: (e: { formData?: unknown }) => void;
    }
  >(({ schema, formData, onChange }, ref) => {
    React.useImperativeHandle(ref, () => ({ validateForm: () => {} }));
    React.useEffect(() => {
      if (formMockShouldTriggerOnChange && onChange) {
        onChange({ formData: { ...(formData as object), __testDirty: true } });
      }
    }, []);
    return (
      <form data-testid="rjsf-form" id="elementModificationForm">
        {schema && formData ? "Form" : null}
      </form>
    );
  });
  FormMock.displayName = "FormMock";
  return {
    __esModule: true,
    default: FormMock,
  };
});

jest.mock("../../../../src/icons/IconProvider", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid="icon" data-icon={name} />
  ),
}));

jest.mock("../../../../src/components/modal/FullscreenButton", () => ({
  FullscreenButton: ({ onClick }: { onClick?: () => void }) => (
    <button data-testid="fullscreen-btn" type="button" onClick={onClick} />
  ),
}));

jest.mock("../../../../src/components/InlineEdit.module.css", () => ({
  __esModule: true,
  default: {
    inlineEditValueWrap: "inline-edit-value-wrap",
    inlineEditButtons: "inline-edit-buttons",
    inlineEditFormWrap: "inline-edit-form-wrap",
  },
}));

jest.mock(
  "../../../../src/components/modal/chain_element/ChainElementModification.module.css",
  () => ({
    __esModule: true,
    default: {
      modal: "modal",
      "modal-fullscreen": "modal-fullscreen",
      "modal-body": "modal-body",
      "modal-body-fullscreen": "modal-body-fullscreen",
      "modal-footer": "modal-footer",
      "modal-header": "modal-header",
      "parameters-form": "parameters-form",
      "element-name-edit-wrapper": "element-name-edit-wrapper",
      "element-name-inline-editor": "element-name-inline-editor",
      "element-name-input": "element-name-input",
      "modal-title-name": "modal-title-name",
      "modal-title-type": "modal-title-type",
      "element-name-edit-icon": "element-name-edit-icon",
      "element-name-inline-viewer": "element-name-inline-viewer",
    },
  }),
);

const mockNode: ChainGraphNode = {
  id: "el-1",
  data: {
    elementType: "script",
    label: "My Script",
    description: "",
    properties: {},
    typeTitle: "Script",
  },
  parentId: undefined,
  position: { x: 0, y: 0 },
  type: "unit",
};

const defaultProps = {
  node: mockNode,
  chainId: "chain-1",
  elementId: "el-1",
  onSubmit: jest.fn(),
  onClose: jest.fn(),
};

function renderWithPermissions(
  permissions: UserPermissions,
  props = defaultProps,
) {
  return render(
    <UserPermissionsContext.Provider value={permissions}>
      <ChainElementModification {...props} />
    </UserPermissionsContext.Provider>,
  );
}

describe("ChainElementModification", () => {
  beforeEach(() => {
    jest.clearAllMocks();
    formMockShouldTriggerOnChange = false;
    schemaModulesOverride = null;
    mockUpdateElement.mockResolvedValue({
      id: "el-1",
      name: "My Script",
      description: "",
      type: "script",
      properties: {},
    });
  });

  it("renders modal with name and type in header", async () => {
    renderWithPermissions({ chain: ["update"] });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    expect(screen.getByText("My Script")).toBeInTheDocument();
    expect(screen.getByText("Script")).toBeInTheDocument();
  });

  it("shows Save button in footer", async () => {
    renderWithPermissions({ chain: ["update"] });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    const saveButton = screen.getByRole("button", { name: /save/i });
    expect(saveButton).toBeInTheDocument();
  });

  it("ElementNameInlineEdit has no Edit button when permissions empty", async () => {
    renderWithPermissions({});

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    expect(
      screen.queryByRole("button", { name: /edit name/i }),
    ).not.toBeInTheDocument();
    expect(screen.getByText("My Script")).toBeInTheDocument();
  });

  it("Save button is disabled when permissions empty", async () => {
    renderWithPermissions({});

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    const saveButton = screen.getByRole("button", { name: /save/i });
    expect(saveButton).toBeDisabled();
  });

  it("ElementNameInlineEdit has Edit button when chain update permission present", async () => {
    renderWithPermissions({ chain: ["update"] });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    expect(
      screen.getByRole("button", { name: /edit name/i }),
    ).toBeInTheDocument();
  });

  it("Save button is not disabled when chain update permission present and no validation errors", async () => {
    renderWithPermissions({ chain: ["update"] });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    const saveButton = screen.getByRole("button", { name: /save/i });
    expect(saveButton).not.toBeDisabled();
  });

  it("Help button calls openElementDoc with elementType", async () => {
    renderWithPermissions({ chain: ["update"] });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    const helpBtn = screen.getByRole("button", { name: /help/i });
    fireEvent.click(helpBtn);

    expect(mockOpenElementDoc).toHaveBeenCalledWith("script");
  });

  it("FullscreenButton toggles fullscreen styles on modal", async () => {
    renderWithPermissions({ chain: ["update"] });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByTestId("fullscreen-btn"));

    await waitFor(() => {
      const modalContent = document.querySelector(".modal-fullscreen");
      expect(modalContent).toBeInTheDocument();
    });
  });

  it("Cancel button calls closeContainingModal and onClose when no changes", async () => {
    renderWithPermissions({ chain: ["update"] });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole("button", { name: /cancel/i }));

    expect(mockCloseContainingModal).toHaveBeenCalled();
    expect(defaultProps.onClose).toHaveBeenCalled();
  });

  it("Save button calls updateElement and onSubmit on success", async () => {
    renderWithPermissions({ chain: ["update"] });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() => {
      expect(mockUpdateElement).toHaveBeenCalledWith(
        "chain-1",
        "el-1",
        expect.objectContaining({
          name: "My Script",
          type: "script",
        }),
      );
    });

    await waitFor(() => {
      expect(defaultProps.onSubmit).toHaveBeenCalled();
      expect(mockCloseContainingModal).toHaveBeenCalled();
    });
  });

  it("Save button calls errorWithDetails when updateElement rejects", async () => {
    mockUpdateElement.mockRejectedValueOnce(new Error("API error"));
    renderWithPermissions({ chain: ["update"] });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole("button", { name: /save/i }));

    await waitFor(() => {
      expect(mockNotificationService.errorWithDetails).toHaveBeenCalledWith(
        "Save element failed",
        "Failed to save element",
        expect.any(Error),
      );
    });

    expect(mockCloseContainingModal).toHaveBeenCalled();
  });

  it("handleNameSave: inline name edit calls updateElement and onSubmit", async () => {
    renderWithPermissions({ chain: ["update"] });

    await waitFor(() => {
      expect(screen.getByRole("dialog")).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole("button", { name: /edit name/i }));

    const input = screen.getByTestId("element-name-input");
    fireEvent.change(input, { target: { value: "Updated Script Name" } });
    fireEvent.click(screen.getByTestId("element-name-apply"));

    await waitFor(() => {
      expect(mockUpdateElement).toHaveBeenCalledWith(
        "chain-1",
        "el-1",
        expect.objectContaining({
          name: "Updated Script Name",
          type: "script",
        }),
      );
    });

    await waitFor(() => {
      expect(defaultProps.onSubmit).toHaveBeenCalled();
    });
  });

  describe("Schema errors", () => {
    const nodeWithUnknownType: ChainGraphNode = {
      ...mockNode,
      data: {
        ...mockNode.data,
        elementType: "unknown-type",
        typeTitle: "Unknown",
      },
    };

    it("calls errorWithDetails when schema is not found", async () => {
      schemaModulesOverride = {};
      render(
        <UserPermissionsContext.Provider value={{ chain: ["update"] }}>
          <ChainElementModification
            {...defaultProps}
            node={nodeWithUnknownType}
          />
        </UserPermissionsContext.Provider>,
      );

      await waitFor(() => {
        expect(mockNotificationService.errorWithDetails).toHaveBeenCalledWith(
          "Schema not found",
          expect.stringContaining("unknown-type"),
          undefined,
        );
      });
    });

    it("calls errorWithDetails when schema YAML is invalid", async () => {
      schemaModulesOverride = {
        "/node_modules/@netcracker/qip-schemas/assets/script.schema.yaml":
          "invalid: {{",
      };
      renderWithPermissions({ chain: ["update"] });

      await waitFor(() => {
        expect(mockNotificationService.errorWithDetails).toHaveBeenCalledWith(
          "Failed to load schema",
          expect.any(String),
          expect.any(Error),
        );
      });
    });
  });
});
