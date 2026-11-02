import type { FrameLocator, Locator, Page } from "@playwright/test";

/** How long `openElement` and `dropFromLibrary` wait for the state they watch for, in ms. */
const SETTLE_MS = 10_000;

interface FocusWatch {
  e2eFormFocused?: Promise<void>;
  e2eStopFocusWatch?: () => void;
}

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

  /**
   * Opens an element's form, which is what a double click on its node does.
   *
   * When its open animation ends, the form moves focus to its body, even from a field inside it, so
   * a select opened before that closes again (#1236). This returns once the form has moved focus:
   * the page notes the end of the dialog's animation and the first `focus()` call inside the dialog
   * after it. It fails after 10 s without that move, which is what the fix of #1236 may bring.
   */
  async openElement(elementId: string): Promise<Locator> {
    const body = this.page.locator("body");
    await body.evaluate(() => {
      const page = window as unknown as FocusWatch;
      const focus = HTMLElement.prototype.focus;
      let opened = false;
      let focused = () => {};
      const onAnimationEnd = (event: AnimationEvent) => {
        if ((event.target as Element).getAttribute("role") === "dialog") opened = true;
      };
      page.e2eStopFocusWatch = () => {
        HTMLElement.prototype.focus = focus;
        window.removeEventListener("animationend", onAnimationEnd, true);
      };
      page.e2eFormFocused = new Promise<void>((resolve) => (focused = resolve));
      window.addEventListener("animationend", onAnimationEnd, true);
      HTMLElement.prototype.focus = function (this: HTMLElement, options?: FocusOptions) {
        if (opened && this.closest('[role="dialog"]')) {
          page.e2eStopFocusWatch?.();
          focused();
        }
        focus.call(this, options);
      };
    });
    try {
      await this.node(elementId).dblclick();
      await body.evaluate(
        (_, ms) =>
          Promise.race([
            (window as unknown as FocusWatch).e2eFormFocused,
            new Promise<never>((_, reject) =>
              setTimeout(() => reject(new Error(`the element form moved no focus into itself within ${ms} ms of its open animation (#1236)`)), ms),
            ),
          ]),
        SETTLE_MS,
      );
    } finally {
      await body.evaluate(() => (window as unknown as FocusWatch).e2eStopFocusWatch?.());
    }
    return this.elementForm;
  }

  /**
   * Drops a library element on an empty spot of the canvas. A drop onto a node would create the
   * element inside it, so the target is the canvas's lower left corner, clear of the view the graph
   * fits and of the controls.
   *
   * The search opens the element's folder with a height transition that clips the list. A drag
   * that starts during it scrolls the clipped list to reach the element, and the list scrolls back
   * to the top as it grows. The element then moves out from under the press, and Chromium starts no
   * drag. So the drag waits until the element is fully visible.
   */
  async dropFromLibrary(title: string): Promise<void> {
    await this.library.getByRole("textbox").fill(title);
    const item = this.library.getByRole("menuitem", { name: title, exact: true });
    await item.evaluate(
      (element, ms) =>
        new Promise<void>((resolve, reject) => {
          const observer = new IntersectionObserver(
            ([entry]) => {
              if (entry.intersectionRatio < 1) return;
              observer.disconnect();
              clearTimeout(timer);
              resolve();
            },
            { threshold: 1 },
          );
          const timer = setTimeout(() => {
            observer.disconnect();
            reject(new Error(`the library item ${element.textContent ?? ""} was not fully visible within ${ms} ms`));
          }, ms);
          observer.observe(element);
        }),
      SETTLE_MS,
      { timeout: SETTLE_MS },
    );
    const box = await this.canvas.boundingBox();
    if (!box) throw new Error("the chain canvas is not on the page");
    await item.dragTo(this.canvas, { targetPosition: { x: 60, y: box.height - 60 } });
  }
}
