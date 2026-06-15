import type { Node } from "@xyflow/react";

/**
 * Bridges live React Flow graphs to {@link captureChainGraphImage}, which runs
 * outside React and so cannot call `useReactFlow()`.
 *
 * Each mounted graph page registers a source that ties together ITS OWN viewport
 * element and node accessor, so the capture function never crosses one graph's
 * viewport with another's nodes (e.g. the side-by-side diff view mounts two graphs).
 */
export type GraphCaptureSource = {
  /** Whether this graph is still in the DOM. */
  isMounted: () => boolean;
  /** This graph's `.react-flow__viewport` element, or null. */
  getViewportEl: () => HTMLElement | null;
  /** This graph's current nodes (for framing). */
  getNodes: () => Node[];
  /**
   * Mounts off-screen nodes (disables React Flow culling) so a full-graph capture
   * sees every node, and resolves once they are mounted and measured. Returns a
   * cleanup that restores culling.
   */
  prepareForExport: () => Promise<() => void>;
};

// Insertion-ordered, so "the first mounted graph" is deterministic.
const sources = new Set<GraphCaptureSource>();

/** Registers a graph as a capture source; returns an unregister cleanup. */
export function registerGraphCaptureSource(
  source: GraphCaptureSource,
): () => void {
  sources.add(source);
  return () => {
    sources.delete(source);
  };
}

/** The single mounted graph, or the first one if several are mounted, else null. */
export function getGraphCaptureSource(): GraphCaptureSource | null {
  for (const source of sources) {
    if (source.isMounted()) return source;
  }
  return null;
}
