import type { Locator, Page } from "@playwright/test";

/** The chains list at `/chains`: the folder tree, its full-text search, and the name links. */
export class ChainsPage {
  readonly page: Page;
  readonly searchField: Locator;

  constructor(page: Page) {
    this.page = page;
    this.searchField = page.getByRole("textbox", { name: "Full text search" });
  }

  async goto(): Promise<void> {
    await this.page.goto("/chains");
  }

  /** Opens the list at one folder, which is where the Create menu puts what it creates. */
  async gotoFolder(folderId: string): Promise<void> {
    await this.page.goto(`/chains?folder=${folderId}`);
  }

  /** Runs the catalog's full-text search, which the list answers with matches and their folders. */
  async search(text: string): Promise<void> {
    await this.searchField.fill(text);
    await this.searchField.press("Enter");
  }

  /** The name link of a chain or a folder row. */
  link(name: string): Locator {
    return this.page.getByRole("row").getByRole("link", { name, exact: true });
  }

  /** The table row of a chain or a folder, by its name link. */
  row(name: string): Locator {
    return this.page.getByRole("row").filter({ has: this.page.getByRole("link", { name, exact: true }) });
  }

  /** Opens the toolbar's Create menu and picks `New Chain` or `New Folder`. */
  async create(item: "New Chain" | "New Folder"): Promise<void> {
    await this.page.getByRole("button", { name: "Create" }).click();
    await this.page.getByRole("menuitem", { name: item }).click();
  }

  /** Picks an item from a row's actions menu, whose button shows only while the row is hovered. */
  async rowAction(name: string, action: string): Promise<void> {
    const row = this.row(name);
    await row.hover();
    await row.getByTestId("chains-row-actions").click();
    await this.page.getByRole("menuitem", { name: action, exact: true }).click();
  }
}
