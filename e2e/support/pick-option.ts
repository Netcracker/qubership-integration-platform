import { expect, type Locator, type Page } from "@playwright/test";

/**
 * Picks the option titled `title` in an antd select list that has just opened.
 *
 * antd opens the list with a slide-up animation that scales it up from 0.8, so a click during it can
 * press on one option and release over another. No option is picked then, and the list stays open:
 * Playwright checks the hit target on the press only. The click is repeated until no combobox reads
 * `aria-expanded="true"`, and a pick in a single select closes the list, so a click that landed is
 * never repeated. A multiple select stays open after a pick, and a second click would clear it, so
 * its caller passes `picked`, a locator that turns visible as soon as the pick lands.
 *
 * The check is on the combobox rather than on the option because a single select also renders the
 * picked value under the option's title.
 */
export async function pickOption(page: Page, title: string, picked?: Locator): Promise<void> {
  const option = page.getByTitle(title, { exact: true });
  const open = page.getByRole("combobox", { expanded: true });
  await expect(async () => {
    await option.click();
    await (picked ? expect(picked).toBeVisible({ timeout: 1_000 }) : expect(open).toHaveCount(0, { timeout: 1_000 }));
  }).toPass({ timeout: 10_000 });
}
