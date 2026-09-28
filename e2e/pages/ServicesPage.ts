import type { Locator, Page } from "@playwright/test";

export type ServiceTab = "external" | "internal" | "implemented" | "context" | "mcp";

/** The services list at `/services#<tab>` and a service's API tab under `/services/systems/:id`. */
export class ServicesPage {
  readonly page: Page;
  readonly createService: Locator;
  readonly createDialog: Locator;
  readonly addSpecificationGroup: Locator;
  readonly importGroupDialog: Locator;

  constructor(page: Page) {
    this.page = page;
    this.createService = page.getByTestId("services-create");
    this.createDialog = page.getByRole("dialog", { name: "Create service" });
    this.addSpecificationGroup = page.getByTestId("api-specs-add-group");
    this.importGroupDialog = page.getByRole("dialog", { name: "Import Specification Group" });
  }

  /** The list keeps its tab in the hash, so each tab is its own URL. */
  async goto(tab: ServiceTab): Promise<void> {
    await this.page.goto(`/services#${tab}`);
  }

  async gotoSpecificationGroups(systemId: string): Promise<void> {
    await this.page.goto(`/services/systems/${systemId}/specificationGroups`);
  }

  /** Picks `file` in the import dialog's upload area through the browser's file chooser. */
  async chooseSpecificationFile(file: string): Promise<void> {
    const chooser = this.page.waitForEvent("filechooser");
    await this.importGroupDialog.getByText("Drag one or more specification files or click to choose").click();
    await (await chooser).setFiles(file);
  }

  /** A row of the API tab's tables, by the name of the group, specification or operation it shows. */
  row(name: string): Locator {
    return this.page
      .getByRole("row")
      .filter({ has: this.page.getByRole("cell").getByText(name, { exact: true }) });
  }

  /** Opens a group or a specification: its name is a clickable span, not a link. */
  async open(name: string): Promise<void> {
    await this.row(name).getByText(name, { exact: true }).click();
  }
}
