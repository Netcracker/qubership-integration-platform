/**
 * VS Code API Singleton
 * Manages VS Code API instance to avoid "already acquired" errors
 */

type AcquireVsCodeApi = () => VsCodeApi;

type ExtensionContextLike = {
  vscode?: VsCodeApi;
} & Record<string, unknown>;

type VsCodeApi = {
  postMessage: (message: unknown) => void;
  setState?: (state: unknown) => void;
  getState?: () => unknown;
} & Record<string, unknown>;

type WindowRecord = Window &
  typeof globalThis &
  Record<string, unknown> & {
    vscode?: VsCodeApi;
    vscodeApi?: VsCodeApi;
    __vscode?: VsCodeApi;
    __vscodeApi?: VsCodeApi;
    vscodeWebview?: VsCodeApi;
    acquireVsCodeApi?: AcquireVsCodeApi | VsCodeApi;
    extensionContext?: ExtensionContextLike;
  };

type GlobalRecord = typeof globalThis &
  Record<string, unknown> & {
    vscode?: VsCodeApi;
    vscodeApi?: VsCodeApi;
    __vscode?: VsCodeApi;
    __vscodeApi?: VsCodeApi;
    vscodeWebview?: VsCodeApi;
    acquireVsCodeApi?: AcquireVsCodeApi | VsCodeApi;
    extensionContext?: ExtensionContextLike;
  };

const isRecord = (value: unknown): value is Record<string, unknown> =>
  typeof value === "object" && value !== null;

const isVsCodeApi = (value: unknown): value is VsCodeApi =>
  isRecord(value) && typeof value.postMessage === "function";

const isAcquireVsCodeApi = (value: unknown): value is AcquireVsCodeApi =>
  typeof value === "function";

class VSCodeApiSingleton {
  private static instance: VSCodeApiSingleton;
  private api: VsCodeApi | null = null;
  private isInitialized = false;

  private constructor() {}

  public static getInstance(): VSCodeApiSingleton {
    if (!VSCodeApiSingleton.instance) {
      VSCodeApiSingleton.instance = new VSCodeApiSingleton();
    }
    return VSCodeApiSingleton.instance;
  }

  public getApi(): VsCodeApi | null {
    if (this.api) {
      return this.api;
    }

    if (this.isInitialized) {
      console.warn(
        "VSCodeApiSingleton: API already initialized but not available",
      );
      return null;
    }

    this.initializeApi();
    return this.api;
  }

  public isAvailable(): boolean {
    return this.api !== null;
  }

  public sendMessage(message: unknown): void {
    if (!this.api) {
      console.error(
        "VSCodeApiSingleton: Cannot send message, API not available",
      );
      return;
    }

    this.api.postMessage(message);
  }

  public reset(): void {
    this.api = null;
    this.isInitialized = false;
    const win = this.getWindowRecord();
    if ("vscodeApi" in win) {
      win.vscodeApi = undefined;
    }
    console.log("VSCodeApiSingleton: Reset");
  }

  public forceFindApi(): boolean {
    console.log("VSCodeApiSingleton: Force searching for API...");
    this.reset();
    this.findExistingApi();

    if (this.api) {
      console.log("VSCodeApiSingleton: API found after force search");
      return true;
    }

    console.warn("VSCodeApiSingleton: API not found after force search");
    return false;
  }

  private initializeApi(): void {
    if (this.isInitialized) {
      return;
    }

    try {
      console.log("VSCodeApiSingleton: Initializing VS Code API...", {
        hasWindowVscode: Boolean(this.getWindowRecord().vscode),
        hasAcquireVsCodeApi: isAcquireVsCodeApi(
          this.getWindowRecord().acquireVsCodeApi,
        ),
        hasGlobalVscodeApi: Boolean(this.getWindowRecord().vscodeApi),
      });

      if (!this.tryKnownLocations() && !this.tryAcquireApi()) {
        console.warn(
          "VSCodeApiSingleton: No VS Code API available during init, searching fallback sources",
        );
        this.findExistingApi();
      }

      this.isInitialized = true;

      if (this.api) {
        console.log("VSCodeApiSingleton: API initialized successfully");
      } else {
        console.error("VSCodeApiSingleton: API initialization failed");
      }
    } catch (error) {
      console.error("VSCodeApiSingleton: Error initializing API:", error);
      this.isInitialized = true;
    }
  }

  private getWindowRecord(): WindowRecord {
    return window as WindowRecord;
  }

  private getGlobalRecord(): GlobalRecord {
    return globalThis as GlobalRecord;
  }

  private assignApi(candidate: unknown, source: string): boolean {
    if (!isVsCodeApi(candidate)) {
      return false;
    }

    this.api = candidate;
    this.isInitialized = true;

    const win = this.getWindowRecord();
    win.vscodeApi = candidate;

    console.log(`VSCodeApiSingleton: Found VS Code API at ${source}`);
    return true;
  }

  private describeError(error: unknown): string {
    if (error instanceof Error && error.message) {
      return error.message;
    }
    return String(error);
  }

  private tryAcquireApi(): boolean {
    const win = this.getWindowRecord();
    const acquireCandidate = win.acquireVsCodeApi;

    if (isAcquireVsCodeApi(acquireCandidate)) {
      try {
        const api = acquireCandidate();
        return this.assignApi(api, "window.acquireVsCodeApi()");
      } catch (error) {
        console.warn(
          "VSCodeApiSingleton: acquireVsCodeApi failed:",
          this.describeError(error),
        );
        return false;
      }
    }

    return this.assignApi(acquireCandidate, "window.acquireVsCodeApi");
  }

  private tryExtensionContext(
    context: ExtensionContextLike | undefined,
    source: string,
  ): boolean {
    if (!context) {
      return false;
    }

    return this.assignApi(context.vscode, `${source}.vscode`);
  }

  private tryKnownLocations(): boolean {
    const win = this.getWindowRecord();
    const knownWindowKeys = [
      "vscodeApi",
      "vscode",
      "__vscode",
      "__vscodeApi",
      "vscodeWebview",
    ] as const;

    for (const key of knownWindowKeys) {
      if (this.assignApi(win[key], `window.${key}`)) {
        return true;
      }
    }

    if (
      this.tryExtensionContext(win.extensionContext, "window.extensionContext")
    ) {
      return true;
    }

    const globalRecord = this.getGlobalRecord();

    for (const key of knownWindowKeys) {
      if (this.assignApi(globalRecord[key], `globalThis.${key}`)) {
        return true;
      }
    }

    return this.tryExtensionContext(
      globalRecord.extensionContext,
      "globalThis.extensionContext",
    );
  }

  private scanRecord(record: Record<string, unknown>, source: string): boolean {
    const keys = Object.keys(record);
    console.log("VSCodeApiSingleton: Available keys sample:", {
      source,
      keys: keys.slice(0, 10),
    });

    for (const key of keys) {
      try {
        const value = record[key];
        if (this.assignApi(value, `${source}.${key}`)) {
          return true;
        }

        if (isRecord(value)) {
          const nestedCandidate = value["vscode"];
          if (this.assignApi(nestedCandidate, `${source}.${key}.vscode`)) {
            return true;
          }
        }
      } catch (error) {
        console.warn(
          `VSCodeApiSingleton: Skipping ${source}.${key} due to error:`,
          this.describeError(error),
        );
      }
    }

    return false;
  }

  private tryParentWindow(): boolean {
    try {
      if (window.parent && window.parent !== window) {
        const parentRecord = window.parent as WindowRecord;
        return (
          this.assignApi(parentRecord.vscode, "window.parent.vscode") ||
          this.scanRecord(parentRecord, "window.parent")
        );
      }
    } catch (error) {
      console.warn(
        "VSCodeApiSingleton: Error checking parent window:",
        this.describeError(error),
      );
    }

    return false;
  }

  private findExistingApi(): void {
    console.log("VSCodeApiSingleton: Searching for existing API...");

    if (this.tryKnownLocations()) {
      return;
    }

    const win = this.getWindowRecord();
    const allKeys = Object.keys(win);
    const functionKeys = allKeys.filter(
      (key) => typeof win[key] === "function",
    );

    console.log(
      "VSCodeApiSingleton: Function keys sample:",
      functionKeys.slice(0, 5),
    );

    if (this.scanRecord(win, "window")) {
      return;
    }

    if (this.tryParentWindow()) {
      return;
    }

    if (this.scanRecord(this.getGlobalRecord(), "globalThis")) {
      return;
    }

    console.warn("VSCodeApiSingleton: No existing API found in any location");
  }
}

export default VSCodeApiSingleton;
