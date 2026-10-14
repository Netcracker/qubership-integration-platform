import type { Locator, Page } from "@playwright/test";

/** The one antd table a list page shows, read through its roles. */
export class TableView {
  readonly page: Page;
  /** Rows that hold data cells. The header row holds column headers only. */
  readonly rows: Locator;

  constructor(page: Page) {
    this.page = page;
    this.rows = page.getByRole("row").filter({ has: page.getByRole("cell") });
  }

  /** A row by text it shows, such as a snapshot's name, a label, or a test case's name. */
  row(text: string): Locator {
    return this.page.getByRole("row").filter({ hasText: text });
  }

  header(name: string): Locator {
    return this.page.getByRole("columnheader", { name, exact: true });
  }

  /** Turns the mouse wheel over `over`, which scrolls the table body the way a user does. */
  async wheel(over: Locator, deltaX: number, deltaY: number): Promise<void> {
    await over.hover();
    await this.page.mouse.wheel(deltaX, deltaY);
  }

  /** The left edge and width of each of `cells`, rounded to whole pixels. */
  static async columns(cells: Locator): Promise<Array<{ left: number; width: number }>> {
    return cells.evaluateAll((elements) =>
      elements.map((element) => {
        const box = element.getBoundingClientRect();
        return { left: Math.round(box.x), width: Math.round(box.width) };
      }),
    );
  }
}
