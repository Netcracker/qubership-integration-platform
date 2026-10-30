import { getNodesBounds, type Node } from "@xyflow/react";
import {
  domToBlob,
  domToForeignObjectSvg,
  type Options,
} from "modern-screenshot";
import { getGraphCaptureSource } from "./graphCaptureBridge.ts";
import {
  applyLightThemeFlip,
  isDarkThemeActive,
  readBackgroundColor,
} from "./exportTheme.ts";

export type ImageFormat = "png" | "svg";

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

/**
 * Snapshots a React Flow graph into image bytes (PNG raster or true-vector SVG).
 *
 * Pure DOM→bytes: the caller supplies the already-mounted `.react-flow__viewport`
 * element and the graph's nodes (for framing). The live graph is never moved — the
 * size/transform overrides are applied to the rendered CLONE via the `style` option.
 *
 * {@link captureChainGraphImage} wraps this for callers that just want "snapshot the
 * currently mounted chain graph". Both stay free of React/hook state so they can run
 * outside React.
 */
export async function captureGraphImage(params: {
  viewportEl: HTMLElement;
  nodes: Node[];
  format: ImageFormat;
}): Promise<ArrayBuffer> {
  const { viewportEl, nodes, format } = params;

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
    backgroundColor: readBackgroundColor(), // matches the (possibly light-flipped) document
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
}

/**
 * Captures the chain graph currently mounted in the webview to image bytes (PNG by
 * default). The single function an external consumer of the extension calls: it
 * needs no arguments — it resolves the mounted graph (via {@link getGraphCaptureSource}),
 * mounts every off-screen node so the WHOLE graph is captured, then snapshots its
 * viewport with {@link captureGraphImage} (scale 1.25, editor-matched background).
 *
 * Throws if no chain graph is mounted, so the caller knows to render one first.
 */
export async function captureChainGraphImage(
  format: ImageFormat = "png",
): Promise<ArrayBuffer> {
  const source = getGraphCaptureSource();
  if (!source) {
    throw new Error("No chain graph mounted to capture");
  }

  // Disable culling and wait for all nodes to mount/measure, so off-screen nodes are
  // in the DOM before the snapshot; restore afterwards.
  const restore = await source.prepareForExport();
  try {
    const viewportEl = source.getViewportEl();
    if (!viewportEl) {
      throw new Error("No chain graph mounted to capture");
    }
    // Export always looks light: when the active theme is dark, switch the document
    // to the app's light theme for the snapshot; when it's already light, leave it.
    // (Container fill opacity is flipped via ThemeContext during export regardless.)
    const restoreTheme = isDarkThemeActive() ? applyLightThemeFlip() : null;
    try {
      return await captureGraphImage({
        viewportEl,
        nodes: source.getNodes().filter((node) => !node.hidden),
        format,
      });
    } finally {
      restoreTheme?.();
    }
  } finally {
    restore();
  }
}
