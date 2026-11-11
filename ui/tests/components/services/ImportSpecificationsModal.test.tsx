/**
 * @jest-environment jsdom
 */
// The import modal serves two backends, and its file picker is the one place that has to tell them
// apart. The catalog unpacks a zip server side (`MultipartFileUtils.extractArchives`); the VS Code
// extension has no unpacker, and an archive that reaches its import is refused as a format whose
// protocol cannot be detected. So the list of offered extensions narrows where the api is the
// extension's, and these cases pin that it narrows there and nowhere else.
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { ImportSpecificationsModal } from "../../../src/components/services/modals/ImportSpecificationsModal.tsx";

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

// The three calls the modal makes on mount, answered empty: two chain lookups for the http-trigger
// list, and the API-contract list the extension context adds.
const mockApiCalls = {
  getRootFolders: jest.fn().mockResolvedValue([]),
  getElementsByType: jest.fn().mockResolvedValue([]),
  getSpecApiFiles: jest.fn().mockResolvedValue([]),
};

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

jest.mock("../../../src/hooks/useNotificationService", () => ({
  useNotificationService: () => ({
    requestFailed: jest.fn(),
    warning: jest.fn(),
  }),
}));

jest.mock("../../../src/ModalContextProvider", () => ({
  useModalContext: () => ({ closeContainingModal: jest.fn() }),
}));

const { VSCodeExtensionApi } = jest.requireMock<{
  VSCodeExtensionApi: new () => unknown;
}>("../../../src/api/rest/vscodeExtensionApi.ts");

function renderModal(context: "catalog" | "extension") {
  mockApi =
    context === "extension"
      ? Object.assign(Object.create(VSCodeExtensionApi.prototype), mockApiCalls)
      : mockApiCalls;
  // Not group mode: the extension context otherwise opens on its API-contract tab, and the file
  // picker this suite is about lives on the file tab.
  return render(
    <ImportSpecificationsModal
      systemId="service-1"
      specificationGroupId="group-1"
      groupMode={false}
    />,
  );
}

// The modal renders into a portal on `document.body`, not into the container `render` returns.
function fileInput(): HTMLInputElement {
  const input = document.body.querySelector('input[type="file"]');
  if (!input) {
    throw new Error("the modal rendered no file input");
  }
  return input as HTMLInputElement;
}

describe("ImportSpecificationsModal", () => {
  beforeEach(() => {
    mockWarning.mockClear();
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
});
