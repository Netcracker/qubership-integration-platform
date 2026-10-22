import type { Download, Locator, Page } from "@playwright/test";

/** The chains list at `/chains`: the folder tree, its full-text search, and the name links. */
export class ChainsPage {
  readonly page: Page;
  readonly searchField: Locator;

  constructor(page: Page) {
    this.page = page;
    this.searchField = page.getByRole("textbox", {
      name: "Search chains and folders...",
    });
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

  /** Exports the selected rows through the options dialog the toolbar opens, and returns the download. */
  async exportSelected(): Promise<Download> {
    await this.page.getByTestId("chains-export").click();
    const dialog = this.page.getByRole("dialog", { name: /^Export Chains?$/ });
    const downloaded = this.page.waitForEvent("download");
    await dialog.getByRole("button", { name: "Export", exact: true }).click();
    return downloaded;
  }

  /**
   * Imports an archive through the toolbar's Import dialog: the file, then Next for the preview, then
   * Import. The dialog picks no folder; the archive names it. Returns the dialog, which ends on the
   * results.
   */
  async importArchive(file: string): Promise<Locator> {
    await this.page.getByTestId("chains-import").click();
    const dialog = this.page.getByRole("dialog", { name: "Import" });
    const chooser = this.page.waitForEvent("filechooser");
    await dialog.getByText("Click or drag file to this area to upload").click();
    await (await chooser).setFiles(file);
    await dialog.getByRole("button", { name: "Next", exact: true }).click();
    // The same button turns from Next into Import, and it can still carry its loading icon, whose
    // name then reads `loading Import`.
    await dialog.getByRole("button", { name: /Import$/ }).click();
    return dialog;
  }

  /** Picks an item from a row's actions menu, whose button shows only while the row is hovered. */
  async rowAction(name: string, action: string): Promise<void> {
    const row = this.row(name);
    await row.hover();
    await row.getByTestId("chains-row-actions").click();
    await this.page.getByRole("menuitem", { name: action, exact: true }).click();
  }
}
