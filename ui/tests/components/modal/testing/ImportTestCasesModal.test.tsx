/**
 * @jest-environment jsdom
 */
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import {
  TestingImportResult,
  TestingImportStatus,
} from "../../../../src/api/apiTypes.ts";
import { ImportTestCasesModal } from "../../../../src/components/modal/testing/ImportTestCasesModal.tsx";

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

const mockImportTestCases = jest.fn<Promise<TestingImportResult[]>, [File[]]>();

jest.mock("../../../../src/api/api.ts", () => ({
  api: {
    importTestCases: (files: File[]) => mockImportTestCases(files),
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

jest.mock("../../../../src/icons/IconProvider.tsx", () => ({
  OverridableIcon: ({ name }: { name: string }) => (
    <span data-testid={`icon-${name}`} />
  ),
}));

function importResult(
  overrides: Partial<TestingImportResult> = {},
): TestingImportResult {
  return {
    archive: "cases.zip",
    fileName: "case-1.json",
    entityId: "case-1",
    entityName: "First case",
    result: TestingImportStatus.CREATED,
    message: "",
    ...overrides,
  };
}

function renderModal() {
  const onImported = jest.fn();
  const utils = render(<ImportTestCasesModal onImported={onImported} />);
  return { ...utils, onImported };
}

// The modal renders into a portal, so its nodes live outside the render container.
function attach(...files: File[]): void {
  const input =
    document.body.querySelector<HTMLInputElement>('input[type="file"]');
  if (!input) {
    throw new Error("upload input not rendered");
  }
  fireEvent.change(input, { target: { files } });
}

function importButton(): HTMLElement {
  return screen.getByRole("button", { name: /^import$/i });
}

async function uploadAndImport(...files: File[]): Promise<void> {
  attach(...files);
  await waitFor(() => expect(importButton()).toBeEnabled());
  fireEvent.click(importButton());
  await waitFor(() => expect(mockImportTestCases).toHaveBeenCalled());
}

beforeEach(() => {
  jest.clearAllMocks();
  mockImportTestCases.mockResolvedValue([importResult()]);
});

describe("ImportTestCasesModal", () => {
  it("should keep Import disabled until an archive is attached", () => {
    renderModal();

    expect(importButton()).toBeDisabled();
  });

  it("should send every attached archive and show the results", async () => {
    renderModal();
    const first = new File(["{}"], "cases.zip");
    const second = new File(["{}"], "more.zip");

    await uploadAndImport(first, second);

    expect(
      mockImportTestCases.mock.calls[0][0].map((file) => file.name),
    ).toEqual(["cases.zip", "more.zip"]);
    expect(await screen.findByText("case-1.json")).toBeInTheDocument();
    expect(screen.getByText("First case")).toBeInTheDocument();
    expect(screen.getByText("Created")).toBeInTheDocument();
    expect(screen.getByText("Close")).toBeInTheDocument();
    expect(
      screen.queryByRole("button", { name: /^import$/i }),
    ).not.toBeInTheDocument();
  });

  it("should refresh the list when something was created or updated", async () => {
    mockImportTestCases.mockResolvedValue([
      importResult({ result: TestingImportStatus.ERROR, message: "broken" }),
      importResult({ result: TestingImportStatus.UPDATED }),
    ]);
    const { onImported } = renderModal();

    await uploadAndImport(new File(["{}"], "cases.zip"));

    await waitFor(() => expect(onImported).toHaveBeenCalledTimes(1));
  });

  it("should leave the list alone when every entry failed", async () => {
    mockImportTestCases.mockResolvedValue([
      importResult({
        result: TestingImportStatus.ERROR,
        entityId: null,
        entityName: null,
        message: "unreadable archive",
      }),
    ]);
    const { onImported } = renderModal();

    await uploadAndImport(new File(["{}"], "cases.zip"));

    expect(await screen.findByText("unreadable archive")).toBeInTheDocument();
    expect(onImported).not.toHaveBeenCalled();
  });

  it("should filter the results by the search term", async () => {
    mockImportTestCases.mockResolvedValue([
      importResult(),
      importResult({
        fileName: "case-2.json",
        entityId: "case-2",
        entityName: "Second case",
        result: TestingImportStatus.UPDATED,
      }),
    ]);
    renderModal();

    await uploadAndImport(new File(["{}"], "cases.zip"));
    expect(await screen.findByText("Second case")).toBeInTheDocument();

    fireEvent.change(screen.getByTestId("import-results-search"), {
      target: { value: "second" },
    });

    expect(screen.queryByText("First case")).not.toBeInTheDocument();
    expect(screen.getByText("Second case")).toBeInTheDocument();
  });

  it("should report a failed upload and stay on the upload phase", async () => {
    mockImportTestCases.mockRejectedValue(new Error("nope"));
    const { onImported } = renderModal();

    await uploadAndImport(new File(["{}"], "cases.zip"));

    await waitFor(() =>
      expect(mockRequestFailed).toHaveBeenCalledWith(
        "Failed to import test cases",
        expect.any(Error),
      ),
    );
    expect(onImported).not.toHaveBeenCalled();
    expect(importButton()).toBeInTheDocument();
  });
});
