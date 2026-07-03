import type { Node } from "@xyflow/react";

const LAYOUT_WAIT_MS = 15_000;

function waitAnimationFrames(count: number): Promise<void> {
  return new Promise((resolve) => {
    let remaining = count;
    const step = () => {
      remaining -= 1;
      if (remaining <= 0) {
        resolve();
        return;
      }
      requestAnimationFrame(step);
    };
    requestAnimationFrame(step);
  });
}

function hasCollapsedContainers(nodes: Node[]): boolean {
  return nodes.some(
    (node) =>
      node.type === "container" &&
      !!(node.data as { collapsed?: boolean })?.collapsed,
  );
}

export async function prepareBatchGraphExport(params: {
  container: HTMLElement;
  getNodes: () => Node[];
  expandAllContainers: () => void;
  waitForNextAutoArrange: () => Promise<void>;
  onLayoutWaitTimeout?: () => void;
}): Promise<HTMLElement | null> {
  const {
    container,
    getNodes,
    expandAllContainers,
    waitForNextAutoArrange,
    onLayoutWaitTimeout,
  } = params;

  const needsLayout = hasCollapsedContainers(getNodes());
  expandAllContainers();

  if (needsLayout) {
    try {
      await Promise.race([
        waitForNextAutoArrange(),
        new Promise<void>((_, reject) => {
          globalThis.setTimeout(
            () =>
              reject(
                new Error(`Layout did not finish within ${LAYOUT_WAIT_MS}ms`),
              ),
            LAYOUT_WAIT_MS,
          );
        }),
      ]);
    } catch {
      onLayoutWaitTimeout?.();
    }
  }

  await waitAnimationFrames(2);

  return container.querySelector<HTMLElement>(".react-flow__viewport");
}
