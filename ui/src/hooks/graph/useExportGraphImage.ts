import { useCallback, useState } from "react";
import { getNodesBounds, Node, useReactFlow } from "@xyflow/react";
import {
  domToBlob,
  domToForeignObjectSvg,
  type Options,
} from "modern-screenshot";
import { App } from "antd";
import { api } from "../../api/api.ts";
import { VSCodeExtensionApi } from "../../api/rest/vscodeExtensionApi.ts";

type ImageFormat = "png" | "svg";

// Margin (px) around the graph in the exported image.
const PADDING = 48;
// Resolution multiplier (DPI = 96 * scale) for the PNG raster. SVG is vector and
// ignores it. ×1.25 trades a bit of file size for crisper text.
const TARGET_SCALE = 1.25;
// Hard cap on the longest rendered side (px) so huge chains stay reasonable on
// disk; the scale is reduced if the graph would exceed it.
const MAX_SIDE = 12000;

// Cloning the FULL computed style (modern-screenshot diffs ~400 props against a
// reference element per node) is the dominant cost: ~21s on large chains. Listing
// the properties to copy turns it into a plain read and cuts cloning to <1s.
//
// The list was built from an audit of the graph's actual styles (node CSS modules,
// reactflow-theme.css, @xyflow/react base styles, inline styles, antd Typography
// ellipsis, SVG edges/icons) plus closely related longhands, so nothing visible is
// lost. The <1s cloning leaves ample headroom, so a broad list is essentially free.
const CLONED_STYLE_PROPERTIES = [
  // Layout & positioning
  "box-sizing",
  "display",
  "position",
  "inset",
  "top",
  "right",
  "bottom",
  "left",
  "z-index",
  "float",
  "clear",
  // Size
  "width",
  "height",
  "min-width",
  "min-height",
  "max-width",
  "max-height",
  "aspect-ratio",
  // Spacing
  "margin",
  "margin-top",
  "margin-right",
  "margin-bottom",
  "margin-left",
  "padding",
  "padding-top",
  "padding-right",
  "padding-bottom",
  "padding-left",
  // Flexbox (nodes use antd Flex)
  "flex",
  "flex-direction",
  "flex-wrap",
  "flex-grow",
  "flex-shrink",
  "flex-basis",
  "align-items",
  "align-content",
  "align-self",
  "justify-content",
  "justify-items",
  "justify-self",
  "place-items",
  "place-content",
  "gap",
  "row-gap",
  "column-gap",
  "order",
  // Transforms (swimlane labels, react-flow viewport/nodes) + individual props
  "transform",
  "transform-origin",
  "transform-box",
  "translate",
  "rotate",
  "scale",
  // Background (incl. repeating-linear-gradient on deprecated nodes)
  "background",
  "background-color",
  "background-image",
  "background-size",
  "background-position",
  "background-repeat",
  "background-clip",
  "background-origin",
  "background-attachment",
  "background-blend-mode",
  // Borders
  "border",
  "border-width",
  "border-style",
  "border-color",
  "border-top",
  "border-right",
  "border-bottom",
  "border-left",
  "border-top-width",
  "border-right-width",
  "border-bottom-width",
  "border-left-width",
  "border-top-style",
  "border-right-style",
  "border-bottom-style",
  "border-left-style",
  "border-top-color",
  "border-right-color",
  "border-bottom-color",
  "border-left-color",
  "border-radius",
  "border-top-left-radius",
  "border-top-right-radius",
  "border-bottom-left-radius",
  "border-bottom-right-radius",
  // Outline (node selection uses outline + negative outline-offset)
  "outline",
  "outline-width",
  "outline-style",
  "outline-color",
  "outline-offset",
  // Effects & visibility
  "box-shadow",
  "opacity",
  "visibility",
  "filter",
  "backdrop-filter",
  "overflow",
  "overflow-x",
  "overflow-y",
  "overflow-wrap",
  // Color & text
  "color",
  "caret-color",
  "accent-color",
  "font",
  "font-family",
  "font-size",
  "font-weight",
  "font-style",
  "font-variant",
  "font-synthesis",
  "font-feature-settings",
  "-webkit-font-smoothing",
  "line-height",
  "letter-spacing",
  "word-spacing",
  "tab-size",
  "text-align",
  "text-decoration",
  "text-decoration-line",
  "text-decoration-color",
  "text-decoration-style",
  "text-overflow",
  "text-transform",
  "text-shadow",
  "text-indent",
  "text-rendering",
  "white-space",
  "word-break",
  "vertical-align",
  "writing-mode",
  "direction",
  "list-style",
  "content",
  // antd Typography multi-line ellipsis (display:-webkit-box + line-clamp)
  "-webkit-line-clamp",
  "-webkit-box-orient",
  "-webkit-text-fill-color",
  // SVG (edges, arrowheads, node icons)
  "fill",
  "fill-opacity",
  "fill-rule",
  "stroke",
  "stroke-width",
  "stroke-opacity",
  "stroke-linecap",
  "stroke-linejoin",
  "stroke-dasharray",
  "stroke-dashoffset",
  "stroke-miterlimit",
  "paint-order",
  "shape-rendering",
  "color-interpolation",
  // Misc
  "object-fit",
  "object-position",
  "appearance",
  "cursor",
];

const nextFrame = () =>
  new Promise<void>((resolve) => requestAnimationFrame(() => resolve()));

const sanitizeFileName = (name: string) =>
  (name.trim() || "chain").replace(/[\\/:*?"<>|]+/g, "_");

const readBackgroundColor = () => {
  if (typeof window === "undefined") return "#ffffff";
  const root =
    document.querySelector(".vscode-webview") ?? document.documentElement;
  const value = getComputedStyle(root)
    .getPropertyValue("--vscode-editor-background")
    .trim();
  return value || "#ffffff";
};

/**
 * Captures the whole graph (not just the visible viewport) as a PNG and sends
 * the bytes to the VS Code extension host to be written to disk.
 *
 * Uses React Flow's official screenshot recipe: snapshot the `.react-flow__viewport`
 * element while overriding its size and transform on the rendered CLONE (via the
 * `style` option), so the live graph is never moved. The caller must disable
 * `onlyRenderVisibleElements` while `exporting` is true so every off-screen node
 * is mounted into the DOM and therefore cloned.
 */
export function useExportGraphImage(fileName?: string) {
  const { getNodes } = useReactFlow();
  const { message } = App.useApp();
  const [exporting, setExporting] = useState(false);

  const capture = useCallback(
    async (format: ImageFormat): Promise<ArrayBuffer> => {
      const viewportEl = document.querySelector<HTMLElement>(
        ".react-flow__viewport",
      );
      if (!viewportEl) {
        throw new Error("Graph viewport element not found");
      }

      const nodes = getNodes().filter((node: Node) => !node.hidden);
      if (nodes.length === 0) {
        throw new Error("Graph is empty");
      }

      const bounds = getNodesBounds(nodes);
      const width = Math.ceil(bounds.width + PADDING * 2);
      const height = Math.ceil(bounds.height + PADDING * 2);

      // Shared between PNG and SVG: same clone pipeline, same framing.
      const shared: Options = {
        width,
        height,
        backgroundColor: readBackgroundColor(), // WYSIWYG — match the editor background
        font: false as const, // fonts are already available in this browser
        timeout: 4000, // don't stall 30s on unreachable assets
        drawImageInterval: 0, // Safari/Firefox decode fix, not needed in webview
        includeStyleProperties: CLONED_STYLE_PROPERTIES, // avoid full-style diff per node
        style: {
          width: `${width}px`,
          height: `${height}px`,
          // Place the graph at (PADDING, PADDING) at zoom 1 inside the frame.
          transform: `translate(${PADDING - bounds.x}px, ${PADDING - bounds.y}px) scale(1)`,
        },
      };

      if (format === "svg") {
        // True vector (foreignObject) SVG — the same intermediate the PNG path
        // rasterizes, minus rasterization. Resolution-independent, so no DPI scale.
        const svg = await domToForeignObjectSvg(viewportEl, shared);
        const markup = new XMLSerializer().serializeToString(svg);
        return new TextEncoder().encode(
          `<?xml version="1.0" encoding="UTF-8"?>\n${markup}`,
        ).buffer;
      }

      let scale = TARGET_SCALE;
      const longestSide = Math.max(width, height) * scale;
      if (longestSide > MAX_SIDE) scale = MAX_SIDE / Math.max(width, height);

      const blob = await domToBlob(viewportEl, {
        ...shared,
        type: "image/png",
        scale,
      });

      return blob.arrayBuffer();
    },
    [getNodes],
  );

  const exportImage = useCallback(async () => {
    if (exporting) return;

    const vscodeApi = api as VSCodeExtensionApi;
    let target;
    try {
      target = await vscodeApi.chooseImageSavePath(
        sanitizeFileName(fileName ?? "chain"),
      );
    } catch (error) {
      console.error("Failed to open save dialog", error);
      void message.error("Failed to open save dialog");
      return;
    }
    if (!target) return; // user cancelled

    setExporting(true);
    try {
      // Let React commit onlyRenderVisibleElements={false} and ReactFlow mount
      // and measure every node before snapshotting the graph.
      await nextFrame();
      await nextFrame();

      const data = await capture(target.format);
      await vscodeApi.writeImageFile(target.uri, data);
    } catch (error) {
      console.error("Failed to export graph image", error);
      void message.error(
        error instanceof Error
          ? `Failed to export image: ${error.message}`
          : "Failed to export image",
      );
    } finally {
      setExporting(false);
    }
  }, [exporting, fileName, capture, message]);

  return { exporting, exportImage };
}
