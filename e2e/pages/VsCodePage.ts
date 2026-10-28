import type { FrameLocator, Locator, Page } from "@playwright/test";
import { readUntil } from "../support/poll.js";

/** The deadline of `readText`, the expect timeout the suite sets. */
const TEXT_DEADLINE_MS = 30_000;

/** Escapes `text` for use inside a regular expression. */
const literal = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

/**
 * The name of a file's option in Quick Open and the file picker. The `vscode` project's user agent
 * is Windows Chrome, so the option names the folder with backslashes.
 */
function fileOption(relativePath: string): RegExp {
  const slash = relativePath.lastIndexOf("/");
  const name = relativePath.slice(slash + 1);
  const folder = slash < 0 ? "" : ` ${relativePath.slice(0, slash).replaceAll("/", "\\")}`;
  return new RegExp(`^${literal(name + folder)}(,|$)`);
}

/**
 * VS Code for the web on 3917, with the extension and the fixture workspace the `vscode-server`
 * project serves.
 *
 * The workbench is located by its own classes: VS Code is pinned to one build in
 * `support/vscode-server.ts`, and its quick input and editors carry no role a locator could use.
 */
export class VsCodePage {
  readonly page: Page;
  /** Quick Open and the command palette, which share one widget. */
  readonly quickInput: Locator;
  /** The content frame of the visible webview. A hidden webview stays in the DOM. */
  readonly webview: FrameLocator;
  /** The text editor of the active editor group, once its model is attached. */
  readonly textEditor: Locator;
  /** The tab of the active editor, named by the file it shows. */
  readonly activeTab: Locator;
  /** The notification toasts, progress included. */
  readonly notifications: Locator;
  readonly statusBar: Locator;

  constructor(page: Page) {
    this.page = page;
    this.quickInput = page.locator(".quick-input-widget");
    this.webview = page.frameLocator("iframe.webview.ready:visible").frameLocator("#active-frame");
    this.textEditor = page.locator(".editor-group-container.active .monaco-editor[data-uri]");
    this.activeTab = page.locator(".editor-group-container.active .tab.active");
    this.notifications = page.locator(".notifications-toasts");
    this.statusBar = page.locator(".statusbar");
  }

  async open(): Promise<void> {
    await this.page.goto("/");
    await this.page.locator(".monaco-workbench").waitFor();
  }

  /** Opens a workspace file with its default editor, found in Quick Open by its relative path. */
  async openFile(relativePath: string): Promise<void> {
    await this.pick("Control+P", relativePath, fileOption(relativePath));
  }

  /** Whether Quick Open lists `relativePath`; a file that is gone leaves it on `No matching results`. */
  async listsFile(relativePath: string): Promise<boolean> {
    await this.openQuickInput("Control+P");
    await this.quickInput.getByRole("textbox").fill(relativePath);
    const option = this.quickInput.getByRole("option", { name: fileOption(relativePath) });
    await option.or(this.quickInput.getByText("No matching results", { exact: true })).first().waitFor();
    const listed = await option.isVisible();
    await this.page.keyboard.press("Escape");
    await this.quickInput.waitFor({ state: "hidden" });
    return listed;
  }

  /** Opens the diff of the active file with `relativePath` through `File: Compare Active File With...`. */
  async compareActiveFileWith(relativePath: string): Promise<void> {
    await this.runCommand("File: Compare Active File With...");
    await this.quickInput.getByRole("textbox").fill(relativePath);
    await this.pickOpen(fileOption(relativePath));
  }

  /**
   * Picks a folder in the workbench's own folder dialog: types `folderPath`, such as `/exports/`,
   * and clicks the button `openLabel` names.
   */
  async selectFolder(folderPath: string, openLabel: string): Promise<void> {
    await this.quickInput.getByRole("textbox").fill(folderPath);
    await this.quickInput.getByRole("button", { name: openLabel }).click();
    await this.quickInput.waitFor({ state: "hidden" });
  }

  /** Runs a command from the palette by its full title, such as `View: Close Editor`. */
  async runCommand(title: string): Promise<void> {
    await this.pick("F1", `>${title}`, new RegExp(`^${literal(title)}(,|$)`));
  }

  /** Reopens the active custom editor as text and returns its text, as `readText` reads it. */
  async readAsText(until?: (text: string) => boolean): Promise<string> {
    await this.runCommand("View: Reopen Editor with Text Editor");
    return await this.readText(until);
  }

  /**
   * Returns the text of the active text editor, copied through the clipboard with the line breaks
   * turned back into `\n` (the Windows user agent copies `\r\n`). The extension writes a change to
   * the in-memory file system, whose change event fires after a debounce, so with `until` the copy
   * repeats until `until` returns true for the text. A copy `until` throws on, such as an empty one
   * that does not parse, counts as false.
   */
  async readText(until?: (text: string) => boolean): Promise<string> {
    const copy = async () => {
      await this.textEditor.locator(".view-lines").click();
      await this.page.keyboard.press("Control+A");
      await this.page.keyboard.press("Control+C");
      return (await this.page.evaluate(() => navigator.clipboard.readText())).replaceAll("\r\n", "\n");
    };
    if (!until) return await copy();
    const holds = (text: string) => {
      try {
        return until(text);
      } catch {
        return false;
      }
    };
    const text = await readUntil(copy, holds, TEXT_DEADLINE_MS, 250);
    if (!holds(text)) throw new Error(`the text editor shows no text the case waits for after ${TEXT_DEADLINE_MS} ms:\n${text}`);
    return text;
  }

  /** Reopens the active editor with the editor `displayName` names, such as `CIP Chain File Visual Editor`. */
  async reopenWith(displayName: string): Promise<void> {
    await this.runCommand("View: Reopen Editor With...");
    await this.pickOpen(new RegExp(`^${literal(displayName)}(,|$)`));
  }

  /**
   * Opens the quick input with `key`, types `query`, and clicks the option named by `option`. A key
   * pressed before the workbench registers its keybindings opens nothing, and the workbench shows
   * no state that marks the registration, so the key repeats until the input shows: an exception
   * to rule 9, listed in `browser.md`. A press that opened the input is not repeated.
   */
  private async pick(key: string, query: string, option: RegExp): Promise<void> {
    await this.openQuickInput(key);
    await this.quickInput.getByRole("textbox").fill(query);
    await this.pickOpen(option);
  }

  private async openQuickInput(key: string): Promise<void> {
    const input = this.quickInput.getByRole("textbox");
    for (let attempt = 1; ; attempt++) {
      await this.page.keyboard.press(key);
      if (await input.waitFor({ timeout: 500 }).then(() => true, () => false)) return;
      if (attempt === 20) throw new Error(`${key} opened no quick input`);
    }
  }

  /**
   * Clicks the option named by `option` in the open quick input, and waits for the option to hide:
   * the input closes, or a command such as `View: Reopen Editor With...` replaces the list.
   */
  private async pickOpen(option: RegExp): Promise<void> {
    const picked = this.quickInput.getByRole("option", { name: option });
    await picked.click();
    await picked.waitFor({ state: "hidden" });
  }
}
