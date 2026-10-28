import { expect, type Page } from "@playwright/test";
import type { Named } from "./catalog-types.js";

/**
 * Asserts that the page shows `chain` inside `folder`. The page loads the chain by id, and its name
 * sets the tab title, while its navigation path fills the breadcrumb.
 */
export async function expectChainOpened(page: Page, chain: Named, folder: Pick<Named, "name">): Promise<void> {
  await expect(page).toHaveURL(new RegExp(`/chains/${chain.id}(/graph)?$`));
  await expect(page).toHaveTitle(chain.name);
  const breadcrumb = page.getByRole("navigation").filter({ hasText: chain.name });
  await expect(breadcrumb.getByRole("link", { name: folder.name, exact: true })).toBeVisible();
}
