/// <reference types="vscode" />
/// <reference path="./../../vscode.proposed.customEditorDiffs.d.ts" />
import * as vscode from "vscode";
import {
  CancellationToken,
  CustomTextEditorProvider,
  ExtensionContext,
  TextDocument,
  Uri,
  Webview,
  WebviewPanel,
} from "vscode";
import { CHAIN_DIFF_PATH, deleteChain, deleteService, getApiResponse, listChainExportTargets } from "./response";
import {
  setPendingExportImagesRequest,
  startExportImagesProgress,
} from "./response/apiRouter";
import { setFileApi } from "./response/file";
import { VSCodeFileApi } from "./response/file/fileApiImpl";
import { CipExplorerProvider } from "./cipExplorer";
import {
  VSCodeMessage,
  VSCodeResponse,
  type ExportImagesStartupPayload,
} from "@netcracker/qip-ui";
import type { ExportImagesCommandConfig } from "./exportImageTypes";
import { FileCacheService } from "./services/FileCacheService";
import {
  getAndClearNavigationStateValue,
  initNavigationState,
  updateNavigationStateValue,
} from "./response/navigationUtils";
import {
  buildExportImagesStartupPayload,
  getExportTargetFromFilePath,
  resolveExportPaths,
} from "./exportImagesHandler";
import {registerChainDiffMessageHandlers} from "./chainDiffEditor";
import {openDocumentInEditor} from "./editorViewTypes";

type VSCodeMessageWrapper = {
  command: string;
  data: VSCodeMessage<any>;
};

let globalCipProvider: CipExplorerProvider | null = null;
const activeWebviewPanels = new Map<string, WebviewPanel>();
let currentExtensionVersion = "unknown";

interface ThemePayload {
  kind: vscode.ColorThemeKind;
  isDark: boolean;
  isLight: boolean;
  isHighContrast: boolean;
  themeName: string;
  colors: Record<string, string>;
  fonts: {
    fontFamily: string;
    fontSize: number;
    fontWeight: string;
    lineHeight: number;
  };
  ui: {
    tabSize: number;
    wordWrap: string;
    minimap: {
      enabled: boolean;
      maxColumn: number;
    };
    scrollbar: {
      vertical: string;
      horizontal: string;
      verticalScrollbarSize: number;
      horizontalScrollbarSize: number;
    };
  };
  accessibility: {
    highContrast: boolean;
    reducedMotion: string;
  };
  debug: {
    timestamp: string;
    extensionVersion: string;
    vscodeVersion: string;
    themeKindValues: Record<string, number>;
  };
}

function getThemeData(): ThemePayload {
  const activeTheme = vscode.window.activeColorTheme;
  const editorConfig = vscode.workspace.getConfiguration("editor");

  const kind = activeTheme.kind;
  const isHighContrast =
    kind === vscode.ColorThemeKind.HighContrast ||
    kind === vscode.ColorThemeKind.HighContrastLight;
  const isDark = kind === vscode.ColorThemeKind.Dark || isHighContrast;

  const colorOverrides: Record<string, string> = {};
  const customColors =
    vscode.workspace
      .getConfiguration()
      .get<Record<string, unknown>>("workbench.colorCustomizations") ?? {};
  const activeThemeLabel = (activeTheme as { label?: string }).label ?? "";

  Object.entries(customColors).forEach(([key, value]) => {
    if (typeof value === "string") {
      colorOverrides[key] = value;
      return;
    }

    if (
      key.startsWith("[") &&
      key.endsWith("]") &&
      typeof value === "object" &&
      value
    ) {
      const themeName = key.slice(1, -1).trim();
      if (themeName && activeThemeLabel && themeName === activeThemeLabel) {
        Object.entries(value as Record<string, unknown>).forEach(
          ([nestedKey, nestedValue]) => {
            if (typeof nestedValue === "string") {
              colorOverrides[nestedKey] = nestedValue;
            }
          },
        );
      }
    }
  });

  return {
    kind,
    isDark,
    isLight: !isDark,
    isHighContrast,
    themeName:
      (activeTheme as { label?: string }).label ??
      (kind === vscode.ColorThemeKind.Light
        ? "Light"
        : kind === vscode.ColorThemeKind.Dark
          ? "Dark"
          : "High Contrast"),
    colors: colorOverrides,
    fonts: {
      fontFamily: editorConfig.get<string>(
        "fontFamily",
        'Consolas, "Courier New", monospace',
      ),
      fontSize: editorConfig.get<number>("fontSize", 14),
      fontWeight: editorConfig.get<string>("fontWeight", "normal"),
      lineHeight: editorConfig.get<number>("lineHeight", 0) || 1.5,
    },
    ui: {
      tabSize: editorConfig.get<number>("tabSize", 4),
      wordWrap: editorConfig.get<string>("wordWrap", "off"),
      minimap: {
        enabled: editorConfig.get<boolean>("minimap.enabled", true),
        maxColumn: editorConfig.get<number>("minimap.maxColumn", 120),
      },
      scrollbar: {
        vertical: editorConfig.get<string>("scrollbar.vertical", "auto"),
        horizontal: editorConfig.get<string>("scrollbar.horizontal", "auto"),
        verticalScrollbarSize: editorConfig.get<number>(
          "scrollbar.verticalScrollbarSize",
          14,
        ),
        horizontalScrollbarSize: editorConfig.get<number>(
          "scrollbar.horizontalScrollbarSize",
          10,
        ),
      },
    },
    accessibility: {
      highContrast: isHighContrast,
      reducedMotion: editorConfig.get<string>("accessibilitySupport", "auto"),
    },
    debug: {
      timestamp: new Date().toISOString(),
      extensionVersion: currentExtensionVersion,
      vscodeVersion: vscode.version,
      themeKindValues: {
        Light: vscode.ColorThemeKind.Light,
        Dark: vscode.ColorThemeKind.Dark,
        HighContrast: vscode.ColorThemeKind.HighContrast,
        HighContrastLight: vscode.ColorThemeKind.HighContrastLight,
      },
    },
  };
}

function sendThemeToWebview(panel: WebviewPanel) {
  const themeData = getThemeData();
  panel.webview.postMessage({
    type: "theme-update",
    payload: themeData,
  });
}

function broadcastThemeToAllWebviews() {
  activeWebviewPanels.forEach((panel) => {
    sendThemeToWebview(panel);
  });
}

export function refreshCipExplorer() {
  if (globalCipProvider) {
    globalCipProvider.refresh();
  }
}

class BaseFileEditorProvider implements CustomTextEditorProvider {
  constructor(protected readonly context: ExtensionContext) {}

  async resolveCustomTextEditor(
    document: TextDocument,
    panel: WebviewPanel,
    _token: CancellationToken,
  ): Promise<void> {
    if (!document?.uri || !panel || !this.context?.extensionUri) {
      throw new Error("Invalid parameters for resolveCustomTextEditor");
    }

    try {
      const webview = panel.webview;
      if (!webview) {
        throw new Error("Panel.webview is required but was null or undefined");
      }

      webview.options = {
        localResourceRoots: [this.context.extensionUri],
        enableScripts: true,
        enableCommandUris: true,
      };

      panel.onDidChangeViewState(async (e) => {
        if (e.webviewPanel.active) {
          const path = await getAndClearNavigationStateValue(
            this.context,
            document.uri,
          );

          if (path) {
            const navigateMessage: VSCodeMessage<any> = {
              requestId: crypto.randomUUID(),
              type: "navigate",
              payload: { path: path },
            };

            const response: VSCodeResponse<any> = {
              requestId: navigateMessage.requestId,
              type: navigateMessage.type,
            };

            response.payload = await getApiResponse(
              navigateMessage,
              document.uri,
              this.context,
            );

            panel.webview.postMessage(response);
          }
        }
      });

      await enrichWebview(panel, this.context, document.uri);
    } catch (error) {
      throw error;
    }
  }
}

class ChainFileEditorProvider extends BaseFileEditorProvider {
  constructor(context: ExtensionContext) {
    super(context);
  }

  async resolveCustomTextEditorInlineDiff(
    documents: vscode.CustomEditorDiffDocuments<vscode.TextDocument>,
    panel: WebviewPanel,
    _token: CancellationToken,
  ): Promise<void> {
    if (!panel || !this.context?.extensionUri) {
      throw new Error("Invalid parameters for resolveCustomEditorInlineDiff");
    }

    try {
      const webview = panel.webview;
      if (!webview) {
        throw new Error("Panel.webview is required but was null or undefined");
      }

      webview.options = {
        localResourceRoots: [this.context.extensionUri],
        enableScripts: true,
        enableCommandUris: true,
      };

      const diffListener = registerChainDiffMessageHandlers({
        context: this.context,
        panel,
        documents,
      });
      panel.onDidDispose(() => diffListener.dispose());

      await enrichWebview(panel, this.context, Uri.parse(CHAIN_DIFF_PATH));
    } catch (error) {
      throw error;
    }
  }
}

const DIFF_EDITOR_ASSOCIATIONS_SETTING = "workbench.diffEditorAssociations";
const CHAIN_DIFF_ASSOCIATION_GLOB = "*.chain.cip.yaml";

/**
 * Keeps `workbench.diffEditorAssociations` in sync with the
 * `cipExtension.useDefaultDiffView` toggle.
 *
 * The extension's `configurationDefaults` map chain file diffs to the visual
 * editor. When the toggle is on, a user-level `"default"` association
 * overrides that default so chain diffs open in the built-in text diff; when
 * the toggle is off, the entry is removed and the default applies again.
 * Associations for other globs are left untouched.
 */
async function syncChainDiffAssociation(): Promise<void> {
  const useDefaultDiffView = vscode.workspace
    .getConfiguration("cipExtension")
    .get<boolean>("useDefaultDiffView", false);

  const configuration = vscode.workspace.getConfiguration();
  const associations = {
    ...configuration.inspect<Record<string, string>>(
      DIFF_EDITOR_ASSOCIATIONS_SETTING,
    )?.globalValue,
  };

  if (useDefaultDiffView) {
    if (associations[CHAIN_DIFF_ASSOCIATION_GLOB] === "default") {
      return;
    }
    associations[CHAIN_DIFF_ASSOCIATION_GLOB] = "default";
  } else {
    if (!(CHAIN_DIFF_ASSOCIATION_GLOB in associations)) {
      return;
    }
    delete associations[CHAIN_DIFF_ASSOCIATION_GLOB];
  }

  await configuration.update(
    DIFF_EDITOR_ASSOCIATIONS_SETTING,
    Object.keys(associations).length > 0 ? associations : undefined,
    vscode.ConfigurationTarget.Global,
  );
}

function openWebviewForElement(
  context: ExtensionContext,
  fileUri: Uri,
  elementType: "chain" | "service",
) {
  const panel = vscode.window.createWebviewPanel(
    "cipWebView",
    `CIP ${elementType === "chain" ? "Chain" : "Service"} Editor`,
    vscode.ViewColumn.One,
    {
      enableScripts: true,
      retainContextWhenHidden: true,
      enableCommandUris: true,
      localResourceRoots: [context.extensionUri],
    },
  );

  enrichWebview(panel, context, fileUri);
}

async function openExportImagesWebview(
  context: ExtensionContext,
  startupPayload: ExportImagesStartupPayload,
) {
  const panel = vscode.window.createWebviewPanel(
    "cipWebView",
    "CIP Export Images",
    vscode.ViewColumn.One,
    {
      enableScripts: true,
      retainContextWhenHidden: true,
      enableCommandUris: true,
      localResourceRoots: [context.extensionUri],
    },
  );

  setPendingExportImagesRequest(panel, startupPayload);
  startExportImagesProgress(panel);
  enrichWebview(panel, context, undefined);
}

async function exportImagesHandler(
  context: ExtensionContext,
  filePath?: string | Uri,
  config?: ExportImagesCommandConfig,
) {
  if (!config?.outputDir) {
    await vscode.window.showErrorMessage("outputDir is required");
    return;
  }

  const paths = resolveExportPaths(filePath, config);

  if (!paths.length) {
    await vscode.window.showErrorMessage("No chain files to export");
    return;
  }

  const targets = await Promise.all(
    paths.map((path) => getExportTargetFromFilePath(path)),
  );

  const startupPayload = buildExportImagesStartupPayload(
    filePath,
    config,
    targets,
  );

  await openExportImagesWebview(context, startupPayload);
}

async function enrichWebview(
  panel: WebviewPanel,
  context: ExtensionContext,
  fileUri: Uri | undefined = undefined,
) {
  if (!panel || !context || !context.extensionUri || !panel.webview) {
    throw new Error("Invalid parameters for enrichWebview");
  }

  panel.webview.html = await getWebviewContent(context, panel.webview);

  const panelId = crypto.randomUUID();
  activeWebviewPanels.set(panelId, panel);

  sendThemeToWebview(panel);
  const themeResend = setTimeout(() => sendThemeToWebview(panel), 300);

  panel.onDidDispose(() => {
    clearTimeout(themeResend);
    activeWebviewPanels.delete(panelId);
  });

  panel.webview.onDidReceiveMessage(async (message: VSCodeMessageWrapper) => {
    if (message.command === "requestTheme") {
      sendThemeToWebview(panel);
      return;
    }

    const response: VSCodeResponse<any> = {
      requestId: message.data.requestId,
      type: message.data.type,
    };

    try {
      response.payload = await getApiResponse(
        message.data,
        fileUri,
        context,
        panel,
      );

      if (message.data.type === "openChainInNewTab") {
        vscode.commands.executeCommand(
          "vscode.openWith",
          response.payload,
          "cip.chainFile.editor",
        );
        return;
      } else if (message.data.type === "navigateInNewTab") {
        const documentUri: Uri = response.payload;
        const path: string = message.data.payload;
        await updateNavigationStateValue(context, documentUri, path);
        await openDocumentInEditor(documentUri);
        return;
      }
      if (message.data.type === "comparedDocumentsRequest") {
        return;
      }
    } catch (e) {
      console.error("Failed to fetch data for CIP Extension API", e);
      if (e instanceof Error) {
        response.error = { message: e.message, name: e.name };
      }
    }
    panel.webview.postMessage(response);
  });
}

function setupFileWatchers(context: ExtensionContext): void {
  const cacheService = FileCacheService.getInstance();
  const watcher = vscode.workspace.createFileSystemWatcher("**/*.cip.yaml");

  watcher.onDidChange((uri) => cacheService.invalidateByUri(uri));
  watcher.onDidDelete((uri) => cacheService.invalidateByUri(uri));
  watcher.onDidCreate((uri) => cacheService.invalidateByUri(uri));

  context.subscriptions.push(watcher);
}

export function activate(context: ExtensionContext): void {
  const fileApiImpl = new VSCodeFileApi(context);
  setFileApi(fileApiImpl);

  initNavigationState(context);

  currentExtensionVersion = context.extension.packageJSON.version ?? "unknown";

  setupFileWatchers(context);

  // Register CIP Explorer provider
  const cipProvider = new CipExplorerProvider(context);
  globalCipProvider = cipProvider;

  context.subscriptions.push(
    vscode.window.registerTreeDataProvider("cip-main", cipProvider),
  );

  const editorParams = {
    webviewOptions: {
      retainContextWhenHidden: true,
    },
    supportsMultipleEditorsPerDocument: false,
    supportsDiffEditor: true,
  };

  context.subscriptions.push(
    vscode.window.registerCustomEditorProvider(
      "cip.chainFile.editor",
      new ChainFileEditorProvider(context),
      editorParams,
    ),
  );

  context.subscriptions.push(
    vscode.window.registerCustomEditorProvider(
      "cip.serviceFile.editor",
      new BaseFileEditorProvider(context),
      editorParams,
    ),
  );

  context.subscriptions.push(
    vscode.window.registerCustomEditorProvider(
      "cip.contextServiceFile.editor",
      new BaseFileEditorProvider(context),
      editorParams,
    ),
  );

  context.subscriptions.push(
    vscode.window.registerCustomEditorProvider(
      "cip.mcpServiceFile.editor",
      new BaseFileEditorProvider(context),
      editorParams,
    ),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("cip.open", function () {
      // The code you place here will be executed every time your command is executed

      // Display a message box to the user
      //vscode.window.showInformationMessage('Hello World from qip-visual-studio-extension in a web extension host!');

      const panel = vscode.window.createWebviewPanel(
        "cipWebView", // Identifies the type of the webview
        "CIP Offline Chain Editor", // Title of the panel
        vscode.ViewColumn.One, // Show in the first column
        {
          enableScripts: true, // Allow JavaScript execution
          retainContextWhenHidden: true, // Keep state when hidden
          enableCommandUris: true,
        },
      );

      enrichWebview(panel, context, undefined);
    }),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand(
      "cip.exportImages",
      async (filePath?: string | Uri, config?: ExportImagesCommandConfig) => {
        try {
          await exportImagesHandler(context, filePath, config);
        } catch (error) {
          console.error("Failed to export CIP chain images:", error);
          await vscode.window.showErrorMessage(
            `Failed to export CIP chain images: ${error}`,
          );
        }
      },
    ),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("cip.exportImagesFromExplorer", async () => {
      try {
        const folders = await vscode.window.showOpenDialog({
          canSelectFiles: false,
          canSelectFolders: true,
          canSelectMany: false,
          title: "Select folder for exported chain PNG files",
          openLabel: "Select output folder",
        });
        if (!folders?.[0]) {
          return;
        }

        const outputDir = folders[0].toString();
        const targets = await listChainExportTargets();

        if (targets.length === 0) {
          await vscode.window.showInformationMessage(
            "No chain files were found in the workspace.",
          );
          return;
        }

        await vscode.commands.executeCommand("cip.exportImages", undefined, {
          outputDir,
          filePaths: targets.flatMap((target) =>
            target.filePath ? [target.filePath] : [],
          ),
        } satisfies ExportImagesCommandConfig);
      } catch (error) {
        console.error("CIP export from explorer failed:", error);
        await vscode.window.showErrorMessage(
          `CIP export from explorer failed: ${error}`,
        );
      }
    }),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("cip.createChain", async () => {
      const result = await fileApiImpl.createEmptyChain();
      cipProvider.refresh();
      if (result) {
        openWebviewForElement(context, result.folderUri, "chain");
      }
    }),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("cip.createChainParent", async () => {
      const result = await fileApiImpl.createEmptyChain(true);
      cipProvider.refresh();
      if (result) {
        openWebviewForElement(context, result.folderUri, "chain");
      }
    }),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("cip.createService", async () => {
      const result = await fileApiImpl.createEmptyService();
      cipProvider.refresh();
      if (result) {
        const serviceFileUri = vscode.Uri.joinPath(
          result.folderUri,
          result.fileName,
        );
        openWebviewForElement(context, serviceFileUri, "service");
      }
    }),
  );

  // Register refresh command
  context.subscriptions.push(
    vscode.commands.registerCommand("cip.refreshExplorer", () => {
      cipProvider.refresh();
    }),
  );

  syncChainDiffAssociation().catch((error) => {
    console.error("[CIP] Failed to sync chain diff editor association:", error);
  });

  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (e.affectsConfiguration("cipExtension.useDefaultDiffView")) {
        syncChainDiffAssociation().catch((error) => {
          console.error(
            "[CIP] Failed to sync chain diff editor association:",
            error,
          );
        });
      }
    }),
  );

  context.subscriptions.push(
    vscode.workspace.onDidChangeConfiguration((e) => {
      if (
        e.affectsConfiguration("editor.fontSize") ||
        e.affectsConfiguration("editor.fontFamily") ||
        e.affectsConfiguration("editor.fontWeight") ||
        e.affectsConfiguration("editor.lineHeight") ||
        e.affectsConfiguration("editor.minimap.enabled") ||
        e.affectsConfiguration("editor.minimap.maxColumn") ||
        e.affectsConfiguration("editor.scrollbar.vertical") ||
        e.affectsConfiguration("editor.scrollbar.horizontal") ||
        e.affectsConfiguration("editor.scrollbar.verticalScrollbarSize") ||
        e.affectsConfiguration("editor.scrollbar.horizontalScrollbarSize")
      ) {
        broadcastThemeToAllWebviews();
      }
    }),
  );

  context.subscriptions.push(
    vscode.window.onDidChangeActiveColorTheme(() => {
      broadcastThemeToAllWebviews();
    }),
  );

  // Register reveal in explorer command
  context.subscriptions.push(
    vscode.commands.registerCommand(
      "cip.revealInExplorer",
      async (item: any) => {
        if (item && item.fileUri) {
          try {
            await openDocumentInEditor(item.fileUri);
          } catch (error) {
            console.error("Failed to open file with custom editor:", error);
            // Fallback to text editor if custom editor fails
            try {
              const document = await vscode.workspace.openTextDocument(
                item.fileUri,
              );
              await vscode.window.showTextDocument(document, {
                viewColumn: vscode.ViewColumn.Active,
                preview: false,
              });
            } catch (fallbackError) {
              console.error(
                "Failed to open file in text editor:",
                fallbackError,
              );
              vscode.window.showErrorMessage(
                `Failed to open file: ${fallbackError}`,
              );
            }
          }
        }
      },
    ),
  );

  // Simplified approach - no complex UI refresh mechanism

  // Register delete commands
  context.subscriptions.push(
    vscode.commands.registerCommand("cip.deleteService", async (item: any) => {
      if (item && item.fileUri) {
        const result = await vscode.window.showWarningMessage(
          `Are you sure you want to delete service "${item.label}"?`,
          { modal: true },
          "Delete",
        );
        if (result === "Delete") {
          try {
            await deleteService(item.fileUri);
            cipProvider.refresh();
            vscode.window.showInformationMessage(
              `Service "${item.label}" deleted successfully`,
            );
          } catch (error) {
            vscode.window.showErrorMessage(
              `Failed to delete service: ${error}`,
            );
          }
        }
      }
    }),
  );

  context.subscriptions.push(
    vscode.commands.registerCommand("cip.deleteChain", async (item: any) => {
      if (item && item.fileUri) {
        const result = await vscode.window.showWarningMessage(
          `Are you sure you want to delete chain "${item.label}"?`,
          { modal: true },
          "Delete",
        );
        if (result === "Delete") {
          try {
            await deleteChain(item.fileUri);
            cipProvider.refresh();
            vscode.window.showInformationMessage(
              `Chain "${item.label}" deleted successfully`,
            );
          } catch (error) {
            vscode.window.showErrorMessage(`Failed to delete chain: ${error}`);
          }
        }
      }
    }),
  );

  // Register open in text editor command
  context.subscriptions.push(
    vscode.commands.registerCommand(
      "cip.openInTextEditor",
      async (item: any) => {
        if (item && item.fileUri) {
          try {
            const document = await vscode.workspace.openTextDocument(
              item.fileUri,
            );
            await vscode.window.showTextDocument(document, {
              viewColumn: vscode.ViewColumn.Active,
              preview: false,
            });
          } catch (error) {
            console.error(`Failed to open file in text editor:`, error);
            vscode.window.showErrorMessage(
              `Failed to open file in text editor: ${error}`,
            );
          }
        }
      },
    ),
  );
}

// This method is called when your extension is deactivated
export function deactivate() {}

async function getWebviewContent(context: ExtensionContext, webview: Webview) {
  if (!context || !context.extensionUri || !webview) {
    throw new Error("Invalid parameters for getWebviewContent");
  }

  // Dynamically load the JS and CSS files
  // Use bundled version for VSCode extension (includes React)
  let jsUri: vscode.Uri;
  let cssUri: vscode.Uri;
  let useBundled = false; // Will be set based on file existence

  try {
    // qip-ui assets are copied here by webpack (copy-webpack-plugin) so that
    // hoisted workspace symlinks in node_modules don't break webview loading.
    const qipUiAssetsRoot = vscode.Uri.joinPath(
      context.extensionUri,
      "dist",
      "web",
      "qip-ui",
    );

    // Try bundled version first (includes React)
    let jsFileUri = vscode.Uri.joinPath(qipUiAssetsRoot, "index.bundled.es.js");

    // Check if bundled version exists using VSCode API (works in web extensions)
    // Try to stat the bundled file - if it fails, use external version
    let bundledExists = false;
    try {
      await vscode.workspace.fs.stat(jsFileUri);
      bundledExists = true;
    } catch (error) {
      jsFileUri = vscode.Uri.joinPath(qipUiAssetsRoot, "index.es.js");
    }

    useBundled = bundledExists;
    const cssFileUri = vscode.Uri.joinPath(qipUiAssetsRoot, "cip-ui.css");

    jsUri = webview.asWebviewUri(jsFileUri);
    cssUri = webview.asWebviewUri(cssFileUri);

    if (!jsUri || !cssUri) {
      throw new Error("Failed to convert file URIs to webview URIs");
    }
  } catch (error) {
    throw error;
  }

  const importMapScript = useBundled
    ? ""
    : `
        <script type="importmap">
        {
          "imports": {
            "react": "https://esm.sh/react@18.3.1",
            "react-dom": "https://esm.sh/react-dom@18.3.1",
            "react/jsx-runtime": "https://esm.sh/react@18.3.1/jsx-runtime"
          }
        }
        </script>
    `;

  const html = `
    <!DOCTYPE html>
    <html lang="en">
      <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>CIP Offline Chain Editor</title>
        <link href="${cssUri}" rel="stylesheet">
        ${importMapScript}
        <script type="module" crossorigin src="${jsUri}"></script>
        <style>
          html, body {
            margin: 0 !important;
            padding: 0 !important;
            width: 100% !important;
            height: 100% !important;
            min-height: 100% !important;
            overflow: auto !important;
          }
          body {
            display: flex !important;
            flex-direction: column !important;
          }
          #app-root {
            width: 100% !important;
            height: 100% !important;
            min-height: 100% !important;
            flex: 1 !important;
            display: flex !important;
            flex-direction: column !important;
          }
        </style>
      </head>
      <body>
        <div id="app-root"></div>
      </body>
    </html>
  `;
  return html;
}
