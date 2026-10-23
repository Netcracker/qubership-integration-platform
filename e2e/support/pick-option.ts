import { expect, type Locator, type Page } from "@playwright/test";

/**
 * Picks the option titled `title` in an antd select list that has just opened.
 *
 * antd opens the list with a slide-up animation that scales it up from 0.8, so a click during it can
 * press on one option and release over another. No option is picked then, and the list stays open:
 * Playwright checks the hit target on the press only. The click is repeated until no combobox reads
 * `aria-expanded="true"`, and a pick in a single select closes the list, so a click that landed is
 * never repeated. A multiple select stays open after a pick, so its caller passes `picked`, a
 * locator that turns visible once the pick lands. That can take longer than one attempt, and a
 * second click would clear the pick, so the click is repeated only while the list reports no
 * option of that name selected.
 *
 * The check is on the combobox rather than on the option because a single select also renders the
 * picked value under the option's title. The option is looked up beside the open listbox for the
 * same reason: a multiple select renders each picked value as an item carrying its title too.
 */
export async function pickOption(page: Page, title: string, picked?: Locator): Promise<void> {
  // rc-select renders a visually hidden listbox beside the visible options, so its parent holds both.
  const listbox = page.getByRole("listbox");
  const option = listbox.locator("..").getByTitle(title, { exact: true });
  const selected = listbox.getByRole("option", { name: title, exact: true, selected: true });
  const open = page.getByRole("combobox", { expanded: true });
  await expect(async () => {
    if (!picked || (await selected.count()) === 0) await option.click();
    await (picked ? expect(picked).toBeVisible({ timeout: 1_000 }) : expect(open).toHaveCount(0, { timeout: 1_000 }));
  }).toPass({ timeout: 10_000 });
}
