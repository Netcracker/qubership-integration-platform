import type { Locator, Page } from "@playwright/test";

export type ChainTab = "snapshots" | "deployments" | "sessions" | "logging-settings" | "masking" | "properties";

/** The chain page's tabs at `/chains/:chainId/<tab>`, and the header they share. */
export class ChainTabsPage {
  readonly page: Page;
  /** The header's tag for the catalog's `unsavedChanges` flag. */
  readonly unsavedChanges: Locator;
  readonly createSnapshot: Locator;
  readonly createDeployment: Locator;
  /** The deployment dialog the Deployments toolbar opens. */
  readonly deploymentDialog: Locator;
  readonly sessionSearch: Locator;
  /**
   * The header's submit button of the Logging and Properties forms. Not an exact name: after a save
   * antd can leave the hidden loading icon in the button, which then reads `loading Apply`.
   */
  readonly apply: Locator;

  constructor(page: Page) {
    this.page = page;
    this.unsavedChanges = page.getByTestId("chain-unsaved-changes");
    this.createSnapshot = page.getByTestId("snapshots-create");
    this.createDeployment = page.getByTestId("deployments-create");
    this.deploymentDialog = page.getByRole("dialog", { name: "Deployment" });
    this.sessionSearch = page.getByRole("textbox", { name: "Search sessions..." });
    this.apply = page.getByRole("button", { name: /Apply$/ });
  }

  async goto(chainId: string, tab: ChainTab): Promise<void> {
    await this.page.goto(`/chains/${chainId}/${tab}`);
  }

  /** A form field by its label, which antd names with a trailing colon and, when required, a leading `* `. */
  field(role: "textbox" | "combobox", label: string): Locator {
    return this.page.getByRole(role, { name: new RegExp(`^(\\* )?${label} :$`) });
  }

  /** An item of the header's breadcrumb, whose last item is the chain's name. */
  breadcrumbItem(text: string): Locator {
    return this.page.getByRole("navigation").getByText(text, { exact: true });
  }

  /** A tab of the chain page by its label, such as `Graph` or `Properties`. */
  tab(label: string): Locator {
    return this.page.getByRole("tab", { name: label, exact: true });
  }

  /**
   * Deploys a snapshot to the `default` domain. Since issue #981 the dialog preselects the domains
   * the chain is deployed to, so a chain with no deployment starts with none and Deploy disabled.
   */
  async deploy(snapshotName: string): Promise<void> {
    await this.createDeployment.click();
    // Snapshot first: the Domains dropdown opens over the Snapshot field below it.
    await this.deploymentDialog.getByRole("combobox", { name: "Snapshot" }).click();
    // antd renders the visible options apart from its listbox, whose options stay hidden.
    await this.page.getByTitle(snapshotName, { exact: true }).click();
    await this.deploymentDialog.getByRole("combobox", { name: "Domains" }).click();
    await this.page.getByTitle("default", { exact: true }).click();
    // A multiple select stays open after a pick. Escape closes it without closing the dialog, where
    // Tab would pick the active option again and so clear it.
    await this.page.keyboard.press("Escape");
    await this.deploymentDialog.getByRole("button", { name: "Deploy" }).click();
  }
}
