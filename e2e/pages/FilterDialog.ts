import type { Locator, Page } from "@playwright/test";
import { pickOption } from "../support/pick-option.js";

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
    await pickOption(this.page, column);
    await this.dialog.getByRole("textbox", { name: "Value" }).fill(value);
    await this.dialog.getByRole("button", { name: "Apply" }).click();
    await this.dialog.waitFor({ state: "hidden" });
  }
}
