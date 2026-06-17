/**
 * @jest-environment jsdom
 */
import type { Node } from "@xyflow/react";

const domToBlob = jest.fn();
const domToForeignObjectSvg = jest.fn();

jest.mock("modern-screenshot", () => ({
  domToBlob,
  domToForeignObjectSvg,
}));

jest.mock("@xyflow/react", () => ({
  getNodesBounds: jest.fn(() => ({ x: 10, y: 20, width: 100, height: 80 })),
}));

jest.mock("../../../src/hooks/graph/graphCaptureBridge.ts", () => ({
  getGraphCaptureSource: jest.fn(),
}));

jest.mock("../../../src/hooks/graph/exportTheme.ts", () => ({
  readBackgroundColor: jest.fn(() => "#ffffff"),
  isDarkThemeActive: jest.fn(() => false),
  applyLightThemeFlip: jest.fn(() => jest.fn()),
}));

import { getNodesBounds } from "@xyflow/react";
import { getGraphCaptureSource } from "../../../src/hooks/graph/graphCaptureBridge.ts";
import {
  applyLightThemeFlip,
  isDarkThemeActive,
} from "../../../src/hooks/graph/exportTheme.ts";
import {
  captureChainGraphImage,
  captureGraphImage,
} from "../../../src/hooks/graph/captureGraphImage.ts";

const node: Node = {
  id: "n1",
  position: { x: 0, y: 0 },
  data: {},
};

describe("captureGraphImage", () => {
  beforeEach(() => {
    domToBlob.mockReset();
    domToForeignObjectSvg.mockReset();
    domToBlob.mockResolvedValue({
      arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer,
    });
    domToForeignObjectSvg.mockResolvedValue(document.createElementNS("http://www.w3.org/2000/svg", "svg"));
  });

  test("throws when the graph has no nodes", async () => {
    await expect(
      captureGraphImage({
        viewportEl: document.createElement("div"),
        nodes: [],
        format: "png",
      }),
    ).rejects.toThrow("Graph is empty");
  });

  test("captures PNG bytes with scale and background options", async () => {
    const viewportEl = document.createElement("div");

    const buffer = await captureGraphImage({
      viewportEl,
      nodes: [node],
      format: "png",
    });

    expect(getNodesBounds).toHaveBeenCalledWith([node]);
    expect(domToBlob).toHaveBeenCalledWith(
      viewportEl,
      expect.objectContaining({
        type: "image/png",
        backgroundColor: "#ffffff",
        scale: 1.25,
      }),
    );
    expect(buffer).toBeInstanceOf(ArrayBuffer);
  });

  test("reduces PNG scale when the framed graph exceeds MAX_SIDE", async () => {
    (getNodesBounds as jest.Mock).mockReturnValueOnce({
      x: 0,
      y: 0,
      width: 10_000,
      height: 10_000,
    });
    const viewportEl = document.createElement("div");

    await captureGraphImage({
      viewportEl,
      nodes: [node],
      format: "png",
    });

    expect(domToBlob).toHaveBeenCalledWith(
      viewportEl,
      expect.objectContaining({
        scale: expect.closeTo(12000 / (10000 + 96), 5),
      }),
    );
  });

  test("captures SVG as encoded XML bytes", async () => {
    const viewportEl = document.createElement("div");

    const buffer = await captureGraphImage({
      viewportEl,
      nodes: [node],
      format: "svg",
    });

    expect(domToForeignObjectSvg).toHaveBeenCalled();
    expect(domToBlob).not.toHaveBeenCalled();
    const text = new TextDecoder().decode(buffer);
    expect(text).toContain('<?xml version="1.0" encoding="UTF-8"?>');
    expect(text).toContain("<svg");
  });
});

describe("captureChainGraphImage", () => {
  const restorePrepare = jest.fn();
  const restoreTheme = jest.fn();

  beforeEach(() => {
    jest.mocked(isDarkThemeActive).mockReturnValue(false);
    jest.mocked(applyLightThemeFlip).mockReturnValue(restoreTheme);
    domToBlob.mockResolvedValue({
      arrayBuffer: async () => new Uint8Array([9]).buffer,
    });
  });

  test("throws when no graph capture source is registered", async () => {
    jest.mocked(getGraphCaptureSource).mockReturnValue(null);

    await expect(captureChainGraphImage()).rejects.toThrow(
      "No chain graph mounted to capture",
    );
  });

  test("throws when the capture source has no viewport element", async () => {
    jest.mocked(getGraphCaptureSource).mockReturnValue({
      isMounted: () => true,
      getViewportEl: () => null,
      getNodes: () => [node],
      prepareForExport: async () => restorePrepare,
    });

    await expect(captureChainGraphImage()).rejects.toThrow(
      "No chain graph mounted to capture",
    );
    expect(restorePrepare).toHaveBeenCalled();
  });

  test("captures through the registered source and restores theme state", async () => {
    const viewportEl = document.createElement("div");
    jest.mocked(isDarkThemeActive).mockReturnValue(true);
    jest.mocked(getGraphCaptureSource).mockReturnValue({
      isMounted: () => true,
      getViewportEl: () => viewportEl,
      getNodes: () => [node, { ...node, id: "hidden", hidden: true }],
      prepareForExport: async () => restorePrepare,
    });

    const buffer = await captureChainGraphImage("png");

    expect(applyLightThemeFlip).toHaveBeenCalled();
    expect(restoreTheme).toHaveBeenCalled();
    expect(restorePrepare).toHaveBeenCalled();
    expect(domToBlob).toHaveBeenCalledWith(
      viewportEl,
      expect.objectContaining({ type: "image/png" }),
    );
    expect(buffer).toBeInstanceOf(ArrayBuffer);
  });
});
