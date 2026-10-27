import type { FrameLocator, Locator, Page } from "@playwright/test";

/**
 * The chain editor at `/chains/:chainId/graph`: the canvas, the element library, the element form.
 * In VS Code it sits in a webview, so `page` is the webview's content frame there.
 */
export class ChainGraphPage {
  readonly page: Page | FrameLocator;
  /** Every element node on the canvas. `@xyflow/react` ids each one `rf__node-<element id>`. */
  readonly nodes: Locator;
  /** The `@xyflow/react` canvas, whose wrapper carries `role="application"`. */
  readonly canvas: Locator;
  readonly library: Locator;
  /** The element form. Its accessible name comes from the title's `Edit name` button. */
  readonly elementForm: Locator;

  constructor(page: Page | FrameLocator) {
    this.page = page;
    this.nodes = page.getByTestId(/^rf__node-/);
    this.canvas = page.getByRole("application");
    this.library = page.getByRole("complementary");
    this.elementForm = page.getByRole("dialog", { name: "Edit name" });
  }

  async goto(chainId: string): Promise<void> {
    if (!("goto" in this.page)) throw new Error("a webview has no URL to go to; open the chain file through VsCodePage.openFile");
    await this.page.goto(`/chains/${chainId}/graph`);
  }

  node(elementId: string): Locator {
    return this.page.getByTestId(`rf__node-${elementId}`);
  }

  /** The edge `@xyflow/react` draws for a dependency, named by the ids of its two elements. */
  edge(fromId: string, toId: string): Locator {
    return this.page.getByRole("group", { name: `Edge from ${fromId} to ${toId}`, exact: true });
  }

  /**
   * Drags from the output handle of one element to the input handle of another, the only way the
   * canvas creates a dependency. `@xyflow/react` marks each handle with `data-handlepos`; the source
   * handle sits on the right and the target on the left.
   */
  async connect(fromId: string, toId: string): Promise<void> {
    await this.node(fromId)
      .locator('[data-handlepos="right"]')
      .dragTo(this.node(toId).locator('[data-handlepos="left"]'));
  }

  /**
   * Clicks an edge at its middle. A straight edge has a box of zero height, which Playwright takes
   * for hidden, so the click goes to the mouse rather than to the locator.
   */
  async clickEdge(fromId: string, toId: string): Promise<void> {
    const edge = this.edge(fromId, toId);
    const box = await edge.boundingBox();
    if (!box) throw new Error(`the edge from ${fromId} to ${toId} is not on the page`);
    await edge.page().mouse.click(box.x + box.width / 2, box.y + box.height / 2);
  }

  /**
   * Drags a node by its title bar to `position` inside `target`. The canvas starts a drag only on a
   * pointer that moves in steps: a single move, which `dragTo` makes, leaves the node in place.
   */
  async dragNode(elementId: string, target: Locator, position: { x: number; y: number }): Promise<void> {
    const from = await this.node(elementId).boundingBox();
    const to = await target.boundingBox();
    if (!from || !to) throw new Error(`node ${elementId} or its drop target is not on the page`);
    const mouse = target.page().mouse;
    await mouse.move(from.x + from.width / 2, from.y + 10);
    await mouse.down();
    await mouse.move(to.x + position.x, to.y + position.y, { steps: 10 });
    await mouse.up();
  }

  /** Opens an element's form, which is what a double click on its node does. */
  async openElement(elementId: string): Promise<Locator> {
    await this.node(elementId).dblclick();
    return this.elementForm;
  }

  /**
   * Drops a library element on an empty spot of the canvas. A drop onto a node would create the
   * element inside it, so the target is the canvas's lower left corner, clear of the view the graph
   * fits and of the controls.
   */
  async dropFromLibrary(title: string): Promise<void> {
    await this.library.getByRole("textbox").fill(title);
    const box = await this.canvas.boundingBox();
    if (!box) throw new Error("the chain canvas is not on the page");
    await this.library
      .getByRole("menuitem", { name: title, exact: true })
      .dragTo(this.canvas, { targetPosition: { x: 60, y: box.height - 60 } });
  }
}
