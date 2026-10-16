/**
 * The three explicit theme modes: text contrast on the chain list and the chain editor, and the mode
 * surviving a reload.
 *
 * What the API cannot see: the colors the browser resolves from `ui/src/styles/theme-variables.css`,
 * the antd overrides, and the tokens `getThemeTokens` reads back from the document. No golden
 * images: each case reads computed styles.
 *
 * The contrast check covers a short, named list of surfaces instead of the whole page. antd renders
 * disabled text, placeholders, and secondary text below 4.5:1 on purpose, so a whole-page sweep
 * would either stay red or need an exception list nobody maintains. Links and the selected tab are
 * left out for the same reason: in light mode they use antd's default `#1677ff`, measured at 4.11:1
 * on white.
 *
 * The switcher's fourth state, System, is the default when nothing is saved, and the page then
 * follows `prefers-color-scheme`. Each case therefore emulates the opposite system scheme before it
 * picks a mode, so a mode that was not saved shows up after the reload as the wrong theme.
 */
import type { Locator, Page } from "@playwright/test";
import { test, expect } from "../../support/page-guard.js";
import { ChainsPage } from "../../pages/ChainsPage.js";
import { ChainGraphPage } from "../../pages/ChainGraphPage.js";
import { readCorpusState, seedChain } from "../../support/corpus.js";

/** `ThemeMode` in `ui/src/theme/themeInit.ts`, with the title the switcher gives each one. */
const MODES = [
  { mode: "light", title: "Light", system: "dark" },
  { mode: "dark", title: "Dark", system: "light" },
  { mode: "high-contrast", title: "HC", system: "light" },
] as const;
type Mode = (typeof MODES)[number]["mode"];

/** WCAG 2.1 AA for body text; high contrast is held to AAA, which is what the mode is for. */
const MIN_CONTRAST: Record<Mode, number> = { light: 4.5, dark: 4.5, "high-contrast": 7 };

/** What `measureContrast` reads for one surface. */
interface Contrast {
  ratio: number;
  foreground: string;
  background: string;
  /** Whether the text is darker than what is behind it. */
  darkOnLight: boolean;
}

/**
 * The WCAG contrast of an element's text against the background it is drawn on.
 *
 * Runs in the page. A transparent or translucent background lets its ancestors show through, so
 * the layers are collected upward to the first opaque one and composited back down, and the text
 * color is composited over the result. Throws while a color transition runs on any of those
 * elements, so the caller's retry reads the settled colors.
 */
function measureContrast(element: Element): Contrast {
  type Rgba = [number, number, number, number];
  const parse = (value: string): Rgba => {
    const match = /^rgba?\(([\d.]+), ([\d.]+), ([\d.]+)(?:, ([\d.]+))?\)$/.exec(value);
    if (!match) throw new Error(`cannot read the color ${value}`);
    return [Number(match[1]), Number(match[2]), Number(match[3]), match[4] === undefined ? 1 : Number(match[4])];
  };
  const over = (top: Rgba, bottom: Rgba): Rgba => [
    top[0] * top[3] + bottom[0] * (1 - top[3]),
    top[1] * top[3] + bottom[1] * (1 - top[3]),
    top[2] * top[3] + bottom[2] * (1 - top[3]),
    1,
  ];
  const luminance = ([r, g, b]: Rgba) => {
    const channel = (value: number) => {
      const c = value / 255;
      return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
    };
    return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
  };
  const css = (color: Rgba) => `rgb(${color.slice(0, 3).map(Math.round).join(", ")})`;

  const layers: Rgba[] = [];
  for (let node: Element | null = element; node; node = node.parentElement) {
    // Measured: a color read right after a change is the old one, so a broken color passed.
    if (node.getAnimations().some((each) => each instanceof CSSTransition && each.playState === "running")) {
      throw new Error("a color transition is still running");
    }
    const layer = parse(getComputedStyle(node).backgroundColor);
    if (layer[3] > 0) layers.push(layer);
    if (layer[3] === 1) break;
  }
  const opaque = layers.pop();
  if (!opaque || opaque[3] !== 1) throw new Error("no ancestor of the element paints an opaque background");
  const background = layers.reduceRight((below, layer) => over(layer, below), opaque);
  const foreground = over(parse(getComputedStyle(element).color), background);

  const [lighter, darker] = [luminance(foreground), luminance(background)].sort((a, b) => b - a);
  return {
    ratio: (lighter + 0.05) / (darker + 0.05),
    foreground: css(foreground),
    background: css(background),
    darkOnLight: luminance(foreground) < luminance(background),
  };
}

/** Records every `theme-variables-updated` event, which `applyThemeToDOM` fires once it settles. */
async function watchThemeEvents(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const events: string[] = [];
    Object.assign(window, { qipThemeEvents: events });
    window.addEventListener("theme-variables-updated", (event) => {
      events.push((event as CustomEvent<{ theme: string }>).detail.theme);
    });
  });
}

/**
 * Waits until `mode` is applied and settled: `applyThemeToDOM` sets `data-theme`, rewrites the
 * variables two animation frames later, and 50 ms after that drops `theme-switching` and fires
 * `theme-variables-updated`.
 */
async function waitForTheme(page: Page, mode: Mode): Promise<void> {
  await expect
    .poll(() =>
      page.evaluate(() => {
        const root = document.documentElement;
        const events = (window as unknown as { qipThemeEvents?: string[] }).qipThemeEvents ?? [];
        return { theme: root.dataset.theme, switching: root.classList.contains("theme-switching"), settled: events.at(-1) };
      }),
    )
    .toEqual({ theme: mode, switching: false, settled: mode });
}

async function chooseTheme(page: Page, title: string): Promise<void> {
  const menu = page.getByRole("menu").filter({ hasText: "Theme" });
  await page.getByRole("button", { name: "User menu" }).click();
  // The segments carry their names only as a tooltip `title`; the radios themselves are unnamed.
  await menu.getByRole("radiogroup").getByTitle(title, { exact: true }).click();
  await page.keyboard.press("Escape");
  await expect(menu).toBeHidden();
}

/** A named surface: text the page draws, and whether it sits on the page background. */
interface Surface {
  name: string;
  text: Locator;
  /** Page text follows the mode's polarity; a button or a node keeps colors of its own. */
  onPage: boolean;
}

async function expectReadable(surfaces: Surface[], mode: Mode): Promise<void> {
  for (const { name, text, onPage } of surfaces) {
    // Retried until the colors settle: antd re-renders its tokens after the theme event.
    await expect(async () => {
      const contrast = await text.evaluate(measureContrast);
      const where = `${name} in ${mode}: ${contrast.foreground} on ${contrast.background}`;
      expect(contrast.ratio, where).toBeGreaterThanOrEqual(MIN_CONTRAST[mode]);
      if (onPage) expect(contrast.darkOnLight, `${where} has the wrong polarity`).toBe(mode === "light");
    }).toPass({ timeout: 10_000 });
  }
}

for (const { mode, title, system } of MODES) {
  test(`the chain list and the chain editor stay readable in ${mode} mode, which survives a reload`, { tag: ["@ui", "@tier2"] }, async ({ page }) => {
    const chain = seedChain(readCorpusState(), "http-echo");
    const header = chain.elements["Header Modification"];
    const chains = new ChainsPage(page);
    const graph = new ChainGraphPage(page);
    const listSurfaces: Surface[] = [
      { name: "the Name column header", text: page.getByRole("columnheader", { name: "Name" }), onPage: true },
      { name: "the Create button", text: page.getByRole("button", { name: "Create" }), onPage: false },
      { name: "the search field", text: chains.searchField, onPage: false },
    ];
    // The node's label renders before its type badge, which repeats the same text at 8 px.
    const nodeLabel = graph.node(header).getByText("Header Modification", { exact: true }).first();
    const editorSurfaces: Surface[] = [
      { name: "the Snapshots tab", text: page.getByRole("tab", { name: "Snapshots" }), onPage: true },
      { name: "the element node's label", text: nodeLabel, onPage: false },
    ];

    await watchThemeEvents(page);
    await page.emulateMedia({ colorScheme: system });
    await chains.goto();
    await waitForTheme(page, system);

    await chooseTheme(page, title);
    await waitForTheme(page, mode);
    await expectReadable(listSurfaces, mode);

    await page.reload();
    await waitForTheme(page, mode);
    await expectReadable(listSurfaces, mode);

    await graph.goto(chain.id);
    await waitForTheme(page, mode);
    await expectReadable(editorSurfaces, mode);
  });
}
