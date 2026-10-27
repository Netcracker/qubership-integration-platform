import { expect, type FrameLocator, type Locator, type Page } from "@playwright/test";

/**
 * Picks the option titled `title` in an antd select list that has just opened.
 *
 * antd opens the list with a slide-up animation that scales it up from 0.8, so a click during it can
 * press on one option and release over another. No option is picked then, and the list stays open:
 * Playwright checks the hit target on the press only. The click is repeated while a combobox reads
 * `aria-expanded="true"`, and a pick in a single select closes the list, so a click that landed is
 * never repeated, however long the list takes to close. A multiple select stays open after a pick, so its caller passes `picked`, a
 * locator that turns visible once the pick lands. That can take longer than one attempt, and a
 * second click would clear the pick, so the click is repeated only while the list reports no
 * option of that name selected.
 *
 * The check is on the combobox rather than on the option because a single select also renders the
 * picked value under the option's title. The option is looked up beside the open listbox for the
 * same reason: a multiple select renders each picked value as an item carrying its title too.
 *
 * `scope` is the page, or the content frame of a VS Code webview.
 */
export async function pickOption(scope: Page | FrameLocator, title: string, picked?: Locator): Promise<void> {
  // rc-select renders a visually hidden listbox beside the visible options, so its parent holds both.
  const listbox = scope.getByRole("listbox");
  const option = listbox.locator("..").getByTitle(title, { exact: true });
  const selected = listbox.getByRole("option", { name: title, exact: true, selected: true });
  const open = scope.getByRole("combobox", { expanded: true });
  let clicked = false;
  await expect(async () => {
    if (picked ? (await selected.count()) === 0 : !clicked || (await open.count()) > 0) {
      await option.click();
      clicked = true;
    }
    await (picked ? expect(picked).toBeVisible({ timeout: 3_000 }) : expect(open).toHaveCount(0, { timeout: 3_000 }));
  }).toPass({ timeout: 30_000 });
}
