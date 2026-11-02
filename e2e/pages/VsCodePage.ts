import type { FrameLocator, Locator, Page } from "@playwright/test";

/** The deadline of `readAsText`, the expect timeout the suite sets. */
const TEXT_DEADLINE_MS = 30_000;

/** Escapes `text` for use inside a regular expression. */
const literal = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");

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

  constructor(page: Page) {
    this.page = page;
    this.quickInput = page.locator(".quick-input-widget");
    this.webview = page.frameLocator("iframe.webview.ready:visible").frameLocator("#active-frame");
    this.textEditor = page.locator(".editor-group-container.active .monaco-editor[data-uri]");
  }

  async open(): Promise<void> {
    await this.page.goto("/");
    await this.page.locator(".monaco-workbench").waitFor();
  }

  /**
   * Opens a workspace file with its default editor, found in Quick Open by its relative path. The
   * `vscode` project's user agent is Windows Chrome, so the option names the folder with backslashes.
   */
  async openFile(relativePath: string): Promise<void> {
    const slash = relativePath.lastIndexOf("/");
    const name = relativePath.slice(slash + 1);
    const folder = slash < 0 ? "" : ` ${relativePath.slice(0, slash).replaceAll("/", "\\")}`;
    await this.pick("Control+P", relativePath, new RegExp(`^${literal(name + folder)}(,|$)`));
  }

  /** Runs a command from the palette by its full title, such as `View: Close Editor`. */
  async runCommand(title: string): Promise<void> {
    await this.pick("F1", `>${title}`, new RegExp(`^${literal(title)}(,|$)`));
  }

  /**
   * Reopens the active custom editor as text and returns its text, copied through the clipboard with
   * the line breaks turned back into `\n` (the Windows user agent copies `\r\n`). The extension
   * writes a change to the in-memory file system, whose change event fires after a debounce, so with
   * `original` the copy repeats until the text differs from it.
   */
  async readAsText(original?: string): Promise<string> {
    await this.runCommand("View: Reopen Editor with Text Editor");
    const deadline = Date.now() + TEXT_DEADLINE_MS;
    for (;;) {
      await this.textEditor.locator(".view-lines").click();
      await this.page.keyboard.press("Control+A");
      await this.page.keyboard.press("Control+C");
      const text = (await this.page.evaluate(() => navigator.clipboard.readText())).replaceAll("\r\n", "\n");
      if (text !== original) return text;
      if (Date.now() > deadline) throw new Error(`the text editor still shows the original text after ${TEXT_DEADLINE_MS} ms`);
      await this.page.waitForTimeout(250);
    }
  }

  /** Reopens the active editor with the editor `displayName` names, such as `CIP Chain File Visual Editor`. */
  async reopenWith(displayName: string): Promise<void> {
    await this.runCommand("View: Reopen Editor With...");
    await this.pickOpen(new RegExp(`^${literal(displayName)}(,|$)`));
  }

  async close(): Promise<void> {
    await this.runCommand("View: Close Editor");
  }

  /** Closes the active editor and opens `relativePath` again, which loads a new webview. */
  async reopen(relativePath: string): Promise<void> {
    await this.close();
    await this.openFile(relativePath);
  }

  /**
   * Opens the quick input with `key`, types `query`, and clicks the option named by `option`. A key
   * pressed before the workbench registers its keybindings opens nothing, so the key repeats until
   * the input shows.
   */
  private async pick(key: string, query: string, option: RegExp): Promise<void> {
    const input = this.quickInput.getByRole("textbox");
    for (let attempt = 1; ; attempt++) {
      await this.page.keyboard.press(key);
      if (await input.waitFor({ timeout: 500 }).then(() => true, () => false)) break;
      if (attempt === 20) throw new Error(`${key} opened no quick input`);
    }
    await input.fill(query);
    await this.pickOpen(option);
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
