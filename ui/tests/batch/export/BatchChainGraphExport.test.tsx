/**
 * @jest-environment jsdom
 */
import { render, waitFor } from "@testing-library/react";
import "@testing-library/jest-dom";
import { ReactFlowProvider } from "@xyflow/react";
import { BatchChainGraphExport } from "../../../src/batch/export/BatchChainGraphExport";

jest.mock("../../../src/batch/export/prepareBatchGraphExport", () => ({
  prepareBatchGraphExport: jest.fn(),
}));

jest.mock("../../../src/hooks/graph/captureGraphImage", () => ({
  captureGraphImage: jest.fn(),
}));

jest.mock("../../../src/api/rest/vscodeExtensionApi", () => ({
  VSCodeExtensionApi: class VSCodeExtensionApi {},
  isVsCode: false,
}));

jest.mock("../../../src/batch/export/vscodeExportSink", () => ({
  reportExportImagesProgress: jest.fn(),
  saveExportedImageToVsCode: jest.fn(),
}));

jest.mock("../../../src/hooks/graph/exportTheme", () => ({
  isDarkThemeActive: jest.fn(() => false),
  applyLightThemeFlip: jest.fn(() => jest.fn()),
}));

import { captureGraphImage } from "../../../src/hooks/graph/captureGraphImage";
import { prepareBatchGraphExport } from "../../../src/batch/export/prepareBatchGraphExport";
import { reportExportImagesProgress } from "../../../src/batch/export/vscodeExportSink";

const expandAllContainers = jest.fn();
const waitForNextAutoArrange = jest.fn().mockResolvedValue(undefined);
const getNodes = jest.fn(() => [{ id: "n1", hidden: false }]);

let chainElements: Array<{ id: string }> = [{ id: "n1" }];
let graphNodes: Array<{ id: string; position: { x: number; y: number }; data: object }> =
  [{ id: "n1", position: { x: 0, y: 0 }, data: {} }];

jest.mock("../../../src/hooks/useChain", () => ({
  useChain: () => ({
    chain: {
      id: "chain-1",
      elements: chainElements,
      dependencies: [],
    },
    updateChain: jest.fn(),
    getChain: jest.fn(),
    isLoading: false,
  }),
}));

jest.mock("../../../src/hooks/graph/useChainGraph", () => ({
  useChainGraph: () => ({
    nodes: graphNodes,
    edges: [],
    decorativeEdges: [],
    isLoading: false,
    expandAllContainers,
    waitForNextAutoArrange,
  }),
}));

jest.mock("@xyflow/react", () => ({
  ReactFlowProvider: ({ children }: { children: React.ReactNode }) => children,
  ReactFlow: () => <div className="react-flow__viewport" />,
  useReactFlow: () => ({ getNodes }),
}));

describe("BatchChainGraphExport", () => {
  beforeEach(() => {
    chainElements = [{ id: "n1" }];
    graphNodes = [{ id: "n1", position: { x: 0, y: 0 }, data: {} }];
    jest.mocked(captureGraphImage).mockReset();
    jest.mocked(prepareBatchGraphExport).mockReset();
    reportExportImagesProgress.mockReset();
    getNodes.mockReturnValue([{ id: "n1", hidden: false }]);
    jest.mocked(prepareBatchGraphExport).mockImplementation(async ({ container }) =>
      container.querySelector(".react-flow__viewport"),
    );
    jest.mocked(captureGraphImage).mockResolvedValue(new Uint8Array([1, 2, 3]).buffer);
  });

  test("skips empty chains without treating them as errors", async () => {
    chainElements = [];
    graphNodes = [];
    const onError = jest.fn();
    const onComplete = jest.fn();
    const onSkip = jest.fn();

    render(
      <ReactFlowProvider>
        <BatchChainGraphExport
          target={{ chainId: "empty-chain", outputName: "empty-chain" }}
          onComplete={onComplete}
          onSkip={onSkip}
          onError={onError}
        />
      </ReactFlowProvider>,
    );

    await waitFor(() => {
      expect(onSkip).toHaveBeenCalledWith({
        chainId: "empty-chain",
        outputName: "empty-chain",
      });
    });
    expect(onComplete).not.toHaveBeenCalled();
    expect(onError).not.toHaveBeenCalled();
  });

  test("exports a non-empty chain to base64 image content", async () => {
    const onComplete = jest.fn();
    const onError = jest.fn();
    const onSkip = jest.fn();

    render(
      <ReactFlowProvider>
        <BatchChainGraphExport
          target={{ chainId: "chain-1", outputName: "chain-1" }}
          imageFormat="svg"
          onComplete={onComplete}
          onSkip={onSkip}
          onError={onError}
        />
      </ReactFlowProvider>,
    );

    await waitFor(() => {
      expect(onComplete).toHaveBeenCalledWith({
        target: { chainId: "chain-1", outputName: "chain-1" },
        fileName: "chain-1.svg",
        contentBase64: expect.any(String),
      });
    });
    expect(prepareBatchGraphExport).toHaveBeenCalledWith(
      expect.objectContaining({
        expandAllContainers,
        waitForNextAutoArrange,
      }),
    );
    expect(captureGraphImage).toHaveBeenCalledWith(
      expect.objectContaining({ format: "svg", nodes: [{ id: "n1", hidden: false }] }),
    );
    expect(onError).not.toHaveBeenCalled();
    expect(onSkip).not.toHaveBeenCalled();
  });

  test("reports layout timeout warnings from prepareBatchGraphExport", async () => {
    jest.mocked(prepareBatchGraphExport).mockImplementation(async (params) => {
      params.onLayoutWaitTimeout?.();
      return document.createElement("div");
    });

    const onComplete = jest.fn();

    render(
      <ReactFlowProvider>
        <BatchChainGraphExport
          target={{ chainId: "chain-1", outputName: "chain-1" }}
          onComplete={onComplete}
          onSkip={jest.fn()}
          onError={jest.fn()}
        />
      </ReactFlowProvider>,
    );

    await waitFor(() => {
      expect(reportExportImagesProgress).toHaveBeenCalledWith(
        "exportImagesItemWarning",
        expect.objectContaining({ reason: "layout_timeout" }),
      );
    });
    expect(onComplete).toHaveBeenCalled();
  });

  test("calls onError when capture fails", async () => {
    jest.mocked(captureGraphImage).mockRejectedValue(new Error("capture failed"));
    const onError = jest.fn();

    render(
      <ReactFlowProvider>
        <BatchChainGraphExport
          target={{ chainId: "chain-1", outputName: "chain-1" }}
          onComplete={jest.fn()}
          onSkip={jest.fn()}
          onError={onError}
        />
      </ReactFlowProvider>,
    );

    await waitFor(() => {
      expect(onError).toHaveBeenCalledWith(expect.any(Error));
    });
  });

  test("calls onError when the graph viewport is missing", async () => {
    jest.mocked(prepareBatchGraphExport).mockResolvedValue(null);
    const onError = jest.fn();

    render(
      <ReactFlowProvider>
        <BatchChainGraphExport
          target={{ chainId: "chain-1", outputName: "chain-1" }}
          onComplete={jest.fn()}
          onSkip={jest.fn()}
          onError={onError}
        />
      </ReactFlowProvider>,
    );

    await waitFor(() => {
      expect(onError).toHaveBeenCalledWith(
        expect.objectContaining({
          message: "Chain chain-1 has no exportable graph",
        }),
      );
    });
  });
});
