import type { Locator, Page } from "@playwright/test";

/** The Filter dialog a table toolbar's Filters button opens, with one row of column, condition, and value. */
export class FilterDialog {
  readonly page: Page;
  readonly dialog: Locator;

  constructor(page: Page) {
    this.page = page;
    this.dialog = page.getByRole("dialog", { name: "Filter" });
  }

  /** Filters the table on one text column with the column's default condition, Contains. */
  async apply(column: string, value: string): Promise<void> {
    await this.page.getByRole("button", { name: "Filters" }).click();
    await this.dialog.getByRole("combobox", { name: "Column" }).click();
    // antd renders the visible options apart from its listbox, whose options stay hidden.
    await this.page.getByTitle(column, { exact: true }).click();
    await this.dialog.getByRole("textbox", { name: "Value" }).fill(value);
    await this.dialog.getByRole("button", { name: "Apply" }).click();
    await this.dialog.waitFor({ state: "hidden" });
  }
}
