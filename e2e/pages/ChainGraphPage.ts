import type { Locator, Page } from "@playwright/test";

/** The chain editor at `/chains/:chainId/graph`: the canvas, the element library, the element form. */
export class ChainGraphPage {
  readonly page: Page;
  /** Every element node on the canvas. `@xyflow/react` ids each one `rf__node-<element id>`. */
  readonly nodes: Locator;
  /** The `@xyflow/react` canvas, whose wrapper carries `role="application"`. */
  readonly canvas: Locator;
  readonly library: Locator;
  /** The element form. Its accessible name comes from the title's `Edit name` button. */
  readonly elementForm: Locator;

  constructor(page: Page) {
    this.page = page;
    this.nodes = page.getByTestId(/^rf__node-/);
    this.canvas = page.getByRole("application");
    this.library = page.getByRole("complementary");
    this.elementForm = page.getByRole("dialog", { name: "Edit name" });
  }

  async goto(chainId: string): Promise<void> {
    await this.page.goto(`/chains/${chainId}/graph`);
  }

  node(elementId: string): Locator {
    return this.page.getByTestId(`rf__node-${elementId}`);
  }

  /** The edge `@xyflow/react` draws for a dependency, named by the ids of its two elements. */
  edge(fromId: string, toId: string): Locator {
    return this.page.getByRole("group", { name: `Edge from ${fromId} to ${toId}`, exact: true });
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
