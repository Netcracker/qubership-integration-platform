/**
 * @jest-environment jsdom
 */
import { render, screen } from "@testing-library/react";
import "@testing-library/jest-dom";
import { MemoryRouter, Route, Routes } from "react-router";
import { BatchExportRunner } from "../../../src/batch/export/BatchExportRunner";
import type { ExportImagesStartupPayload } from "../../../src/appConfig";

jest.mock("../../../src/api/rest/vscodeExtensionApi", () => ({
  VSCodeExtensionApi: class VSCodeExtensionApi {},
  isVsCode: false,
}));

jest.mock("../../../src/batch/export/vscodeExportSink", () => ({
  reportExportImagesProgress: jest.fn(),
  saveExportedImageToVsCode: jest.fn(),
}));

jest.mock("../../../src/components/LibraryContext", () => ({
  LibraryProvider: ({ children }: { children: React.ReactNode }) => children,
}));

const mockBatchChainGraphExport = jest.fn();
let batchExportBehavior: "complete" | "skip" | "fail-save" | "error" = "complete";

jest.mock("../../../src/batch/export/BatchChainGraphExport", () => {
  const React = require("react");
  return {
    BatchChainGraphExport: (props: {
      onComplete: (result: {
        target: { chainId: string; outputName?: string };
        fileName: string;
        contentBase64: string;
      }) => void;
      onSkip: (target: { chainId: string; outputName?: string }) => void;
      onError: (error: unknown) => void;
    }) => {
      mockBatchChainGraphExport(props);
      React.useEffect(() => {
        if (batchExportBehavior === "skip") {
          props.onSkip({ chainId: "chain-1", outputName: "chain-1" });
          return;
        }
        if (batchExportBehavior === "error") {
          props.onError(new Error("render failed"));
          return;
        }
        props.onComplete({
          target: { chainId: "chain-1", outputName: "chain-1" },
          fileName: "chain-1.png",
          contentBase64: "abc",
        });
      }, []);
      return <div data-testid="batch-chain-export" />;
    },
  };
});

const {
  reportExportImagesProgress,
  saveExportedImageToVsCode,
} = jest.requireMock("../../../src/batch/export/vscodeExportSink") as {
  reportExportImagesProgress: jest.Mock;
  saveExportedImageToVsCode: jest.Mock;
};

const request: ExportImagesStartupPayload = {
  exportConfig: {
    outputDir: "/tmp/export",
    imageFormat: "png",
    targets: [{ chainId: "chain-1", outputName: "chain-1" }],
  },
  targets: [{ chainId: "chain-1", outputName: "chain-1" }],
};

describe("BatchExportRunner", () => {
  beforeEach(() => {
    batchExportBehavior = "complete";
    mockBatchChainGraphExport.mockClear();
    reportExportImagesProgress.mockClear();
    saveExportedImageToVsCode.mockResolvedValue(undefined);
  });

  test("shows error when export request state is missing", () => {
    render(
      <MemoryRouter initialEntries={["/batch-export"]}>
        <Routes>
          <Route path="/batch-export" element={<BatchExportRunner />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText("Export request is missing")).toBeInTheDocument();
  });

  test("renders progress when request state is provided", () => {
    render(
      <MemoryRouter
        initialEntries={[{ pathname: "/batch-export", state: request }]}
      >
        <Routes>
          <Route path="/batch-export" element={<BatchExportRunner />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText("Exporting chain PNG files")).toBeInTheDocument();
    expect(screen.getByTestId("batch-chain-export")).toBeInTheDocument();
  });

  test("shows error when no export targets are provided", () => {
    render(
      <MemoryRouter
        initialEntries={[
          {
            pathname: "/batch-export",
            state: {
              exportConfig: {
                outputDir: "/tmp/export",
                imageFormat: "png",
                targets: [],
              },
              targets: [],
            },
          },
        ]}
      >
        <Routes>
          <Route path="/batch-export" element={<BatchExportRunner />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(screen.getByText("No export targets provided")).toBeInTheDocument();
  });

  test("reports startup and completes a successful export", async () => {
    render(
      <MemoryRouter
        initialEntries={[{ pathname: "/batch-export", state: request }]}
      >
        <Routes>
          <Route path="/batch-export" element={<BatchExportRunner />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(reportExportImagesProgress).toHaveBeenCalledWith(
      "exportImagesStarted",
      expect.objectContaining({ total: 1, outputDir: "/tmp/export" }),
    );

    await screen.findByText("Export completed");
    expect(screen.getByText(/1 of 1 image file\(s\) exported/)).toBeInTheDocument();
    expect(saveExportedImageToVsCode).toHaveBeenCalledWith("/tmp/export", {
      target: { chainId: "chain-1", outputName: "chain-1" },
      fileName: "chain-1.png",
      contentBase64: "abc",
    });
    expect(reportExportImagesProgress).toHaveBeenCalledWith(
      "exportImagesComplete",
      expect.objectContaining({ total: 1, succeeded: 1, skipped: [], failed: [] }),
    );
  });

  test("records skipped chains and shows a warning summary", async () => {
    batchExportBehavior = "skip";

    render(
      <MemoryRouter
        initialEntries={[{ pathname: "/batch-export", state: request }]}
      >
        <Routes>
          <Route path="/batch-export" element={<BatchExportRunner />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(
      await screen.findByText(/1 empty chain\(s\) skipped/),
    ).toBeInTheDocument();
    expect(reportExportImagesProgress).toHaveBeenCalledWith(
      "exportImagesItemWarning",
      expect.objectContaining({ reason: "empty_chain" }),
    );
  });

  test("records save failures and still finishes the batch", async () => {
    saveExportedImageToVsCode.mockRejectedValue(new Error("disk full"));

    render(
      <MemoryRouter
        initialEntries={[{ pathname: "/batch-export", state: request }]}
      >
        <Routes>
          <Route path="/batch-export" element={<BatchExportRunner />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText(/1 failed/)).toBeInTheDocument();
    expect(reportExportImagesProgress).toHaveBeenCalledWith(
      "exportImagesItemFailed",
      expect.objectContaining({ error: "disk full" }),
    );
  });

  test("records render failures from BatchChainGraphExport", async () => {
    batchExportBehavior = "error";

    render(
      <MemoryRouter
        initialEntries={[{ pathname: "/batch-export", state: request }]}
      >
        <Routes>
          <Route path="/batch-export" element={<BatchExportRunner />} />
        </Routes>
      </MemoryRouter>,
    );

    expect(await screen.findByText(/1 failed/)).toBeInTheDocument();
    expect(reportExportImagesProgress).toHaveBeenCalledWith(
      "exportImagesItemFailed",
      expect.objectContaining({ error: "render failed" }),
    );
  });
});
