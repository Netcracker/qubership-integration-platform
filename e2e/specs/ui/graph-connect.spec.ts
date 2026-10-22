/**
 * The canvas: a dependency drawn by dragging between two handles.
 *
 * What the API cannot see: that a drag from one element's output handle to another's input handle
 * sends `POST /v1/chains/{id}/dependencies` with the elements in that order, and that the canvas
 * draws the edge from the answer with no reload. The drag is the only way the canvas creates a
 * dependency (`onConnect` in `useChainGraph.tsx`).
 *
 * The elements are created through the API, and both are plain ones: a container hides its handles.
 * The case fails when the drag sends nothing, sends the elements in reverse, or leaves the canvas
 * without the edge.
 */
import { test, expect } from "../../support/page-guard.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import { tokenized } from "../../support/run.js";

test.describe("canvas connections", { tag: ["@ui"] }, () => {
  test("a drag between two handles saves the dependency", async ({ page, catalog, folder, run }) => {
    const chain = await catalog.createChain(tokenized(run, "ui-graph-connect"), folder.id);
    const from = await catalog.createElement(chain.id, "log-record");
    const to = await catalog.createElement(chain.id, "log-record");
    const graph = new ChainGraphPage(page);

    await graph.goto(chain.id);
    await expect(graph.nodes).toHaveCount(2);
    await graph.connect(from.id, to.id);

    await expect(graph.edge(from.id, to.id)).toBeAttached();
    await expect.poll(async () => (await catalog.listDependencies(chain.id)).map(({ from, to }) => ({ from, to }))).toEqual([{ from: from.id, to: to.id }]);
  });
});
