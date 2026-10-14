import type { Locator, Page } from "@playwright/test";

export type ChainTab = "snapshots" | "deployments" | "sessions";

/** The chain page's table tabs at `/chains/:chainId/<tab>`, and the header they share. */
export class ChainTabsPage {
  readonly page: Page;
  /** The header's tag for the catalog's `unsavedChanges` flag. */
  readonly unsavedChanges: Locator;
  readonly createSnapshot: Locator;
  readonly createDeployment: Locator;
  /** The deployment dialog the Deployments toolbar opens. */
  readonly deploymentDialog: Locator;
  readonly sessionSearch: Locator;

  constructor(page: Page) {
    this.page = page;
    this.unsavedChanges = page.getByTestId("chain-unsaved-changes");
    this.createSnapshot = page.getByTestId("snapshots-create");
    this.createDeployment = page.getByTestId("deployments-create");
    this.deploymentDialog = page.getByRole("dialog", { name: "Deployment" });
    this.sessionSearch = page.getByRole("textbox", { name: "Search sessions..." });
  }

  async goto(chainId: string, tab: ChainTab): Promise<void> {
    await this.page.goto(`/chains/${chainId}/${tab}`);
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
