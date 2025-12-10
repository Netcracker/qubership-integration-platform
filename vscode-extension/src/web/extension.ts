import * as vscode from "vscode";
import {
    CancellationToken,
    CustomTextEditorProvider,
    ExtensionContext,
    TextDocument, Uri,
    Webview,
    WebviewPanel
} from "vscode";
import {getApiResponse} from "./response";
import { setFileApi } from "./response/file";
import { VSCodeFileApi } from "./response/file/fileApiImpl";
import { getExtensionsForUri, setCurrentFileContext, extractFilename, initializeContextFromFile } from "./response/file/fileExtensions";
import { QipExplorerProvider } from "./qipExplorer";
import {VSCodeMessage, VSCodeResponse} from "@netcracker/qip-ui";
import { FileCacheService } from "./services/FileCacheService";
import { ProjectConfigService, CONFIG_FILENAME, ProjectConfig } from "./services/ProjectConfigService";
import { ConfigApiProvider } from "./services/ConfigApiProvider";
import { getAndClearNavigationStateValue, getNavigationStateValue, initNavigationState, updateNavigationStateValue } from "./response/navigationUtils";

export interface QipExtensionAPI {
    loadConfigFromPath(configUri: Uri): Promise<void>;
    registerConfig(appName: string, configData: {
        extensions?: {
            chain?: string;
            service?: string;
            specificationGroup?: string;
            specification?: string;
        };
        schemaUrls?: {
            service?: string;
            chain?: string;
            specification?: string;
            specificationGroup?: string;
        };
    }): void;
    unregisterConfig(appName: string): void;
    getConfig(appName: string): ProjectConfig | undefined;
}

let globalQipProvider: QipExplorerProvider | null = null;
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
    const isHighContrast = kind === vscode.ColorThemeKind.HighContrast || kind === vscode.ColorThemeKind.HighContrastLight;
    const isDark = kind === vscode.ColorThemeKind.Dark || isHighContrast;

    const colorOverrides: Record<string, string> = {};
    const customColors = vscode.workspace.getConfiguration().get<Record<string, unknown>>("workbench.colorCustomizations") ?? {};
    const activeThemeLabel = (activeTheme as { label?: string }).label ?? "";

    Object.entries(customColors).forEach(([key, value]) => {
        if (typeof value === "string") {
            colorOverrides[key] = value;
            return;
        }

        if (key.startsWith("[") && key.endsWith("]") && typeof value === "object" && value) {
            const themeName = key.slice(1, -1).trim();
            if (themeName && activeThemeLabel && themeName === activeThemeLabel) {
                Object.entries(value as Record<string, unknown>).forEach(([nestedKey, nestedValue]) => {
                    if (typeof nestedValue === "string") {
                        colorOverrides[nestedKey] = nestedValue;
                    }
                });
            }
        }
    });

    return {
        kind,
        isDark,
        isLight: !isDark,
        isHighContrast,
        themeName: (activeTheme as { label?: string }).label ?? (kind === vscode.ColorThemeKind.Light
            ? "Light"
            : kind === vscode.ColorThemeKind.Dark
                ? "Dark"
                : "High Contrast"),
        colors: colorOverrides,
        fonts: {
            fontFamily: editorConfig.get<string>("fontFamily", 'Consolas, "Courier New", monospace'),
            fontSize: editorConfig.get<number>("fontSize", 14),
            fontWeight: editorConfig.get<string>("fontWeight", "normal"),
            lineHeight: editorConfig.get<number>("lineHeight", 0) || 1.5
        },
        ui: {
            tabSize: editorConfig.get<number>("tabSize", 4),
            wordWrap: editorConfig.get<string>("wordWrap", "off"),
            minimap: {
                enabled: editorConfig.get<boolean>("minimap.enabled", true),
                maxColumn: editorConfig.get<number>("minimap.maxColumn", 120)
            },
            scrollbar: {
                vertical: editorConfig.get<string>("scrollbar.vertical", "auto"),
                horizontal: editorConfig.get<string>("scrollbar.horizontal", "auto"),
                verticalScrollbarSize: editorConfig.get<number>("scrollbar.verticalScrollbarSize", 14),
                horizontalScrollbarSize: editorConfig.get<number>("scrollbar.horizontalScrollbarSize", 10)
            }
        },
        accessibility: {
            highContrast: isHighContrast,
            reducedMotion: editorConfig.get<string>("accessibilitySupport", "auto")
        },
        debug: {
            timestamp: new Date().toISOString(),
            extensionVersion: currentExtensionVersion,
            vscodeVersion: vscode.version,
            themeKindValues: {
                Light: vscode.ColorThemeKind.Light,
                Dark: vscode.ColorThemeKind.Dark,
                HighContrast: vscode.ColorThemeKind.HighContrast,
                HighContrastLight: vscode.ColorThemeKind.HighContrastLight
            }
        }
    };
}

function sendThemeToWebview(panel: WebviewPanel) {
    const themeData = getThemeData();
    panel.webview.postMessage({
        type: "theme-update",
        payload: themeData
    });
}

function broadcastThemeToAllWebviews() {
    activeWebviewPanels.forEach((panel) => {
        sendThemeToWebview(panel);
    });
}

export function refreshQipExplorer() {
    if (globalQipProvider) {
        globalQipProvider.refresh();
    }
}

class ChainFileEditorProvider implements CustomTextEditorProvider {
    constructor(private readonly context: ExtensionContext) {
    }

    async resolveCustomTextEditor(
        document: TextDocument,
        panel: WebviewPanel,
        _token: CancellationToken
    ): Promise<void> {
        const webview = panel.webview;
        webview.options = {
            localResourceRoots: [this.context.extensionUri],
            enableScripts: true,
            enableCommandUris: true
        };

        panel.onDidChangeViewState(async (e) => {
            if (e.webviewPanel.active) {
                const path = await getAndClearNavigationStateValue(this.context, document.uri);

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

                    response.payload = await getApiResponse(navigateMessage, document.uri, this.context);

                    panel.webview.postMessage(response);
                }
            }
        });

        enrichWebview(panel, this.context, document.uri);
    }
}

function openWebviewForElement(context: ExtensionContext, fileUri: Uri, elementType: 'chain' | 'service') {
    const panel = vscode.window.createWebviewPanel(
        'qipWebView',
        `QIP ${elementType === 'chain' ? 'Chain' : 'Service'} Editor`,
        vscode.ViewColumn.One,
        {
            enableScripts: true,
            retainContextWhenHidden: true,
            enableCommandUris: true,
            localResourceRoots: [context.extensionUri]
        }
    );

    enrichWebview(panel, context, fileUri);
}

function enrichWebview(panel: WebviewPanel, context: ExtensionContext, fileUri: Uri | undefined = undefined) {
    type VSCodeMessageWrapper = {
        command: string;
        data: VSCodeMessage<any>;
    };

    if (fileUri) {
        try {
            initializeContextFromFile(fileUri);
        } catch (error) {
            console.error('Failed to initialize context from file:', error);
        }
    }

    panel.webview.html = getWebviewContent(context, panel.webview);

    const panelId = crypto.randomUUID();
    activeWebviewPanels.set(panelId, panel);

    sendThemeToWebview(panel);
    setTimeout(() => sendThemeToWebview(panel), 300);

    panel.onDidDispose(() => {
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
            response.payload = await getApiResponse(message.data, fileUri, context);

            if (message.data.type === "openChainInNewTab") {
                vscode.commands.executeCommand('vscode.openWith', response.payload, 'qip.chainFile.editor');
                return;
            } else if (message.data.type === "navigateInNewTab") {
                const documentUri: Uri = response.payload;
                const path: string = message.data.payload;

                await updateNavigationStateValue(context, documentUri, path);

                const fileExtensions = getExtensionsForUri();
                let editor = undefined;
                if (documentUri.path.endsWith(fileExtensions.chain)) {
                    editor = 'qip.chainFile.editor';
                } else if (documentUri.path.endsWith(fileExtensions.service)){
                    editor = 'qip.serviceFile.editor';
                }
                if (!editor) {
                    throw new Error(`Unable to find an editor for document: ${documentUri}`);
                }
                await vscode.commands.executeCommand('vscode.openWith', documentUri, editor);
                return;
            }
        } catch (e) {
            console.error("Failed to fetch data for QIP Extension API", e);
            if (e instanceof Error) {
                response.error = e;
            }
        }
        panel.webview.postMessage(response);
    });
}

async function deleteServiceWithRelatedFiles(serviceFileUri: Uri, serviceName: string): Promise<void> {
    const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, '..');
    const rootUri = vscode.workspace.workspaceFolders?.[0]?.uri;
    const cacheService = FileCacheService.getInstance();

    try {
        const entries = await vscode.workspace.fs.readDirectory(serviceFolderUri);
        const ext = getExtensionsForUri(serviceFileUri);

        const filesToDelete: Uri[] = [];

        for (const [fileName, fileType] of entries) {
            if (fileType === vscode.FileType.File) {
                if (fileName.endsWith(ext.specificationGroup) ||
                    fileName.endsWith(ext.specification) ||
                    fileName.endsWith(ext.service)) {
                    filesToDelete.push(vscode.Uri.joinPath(serviceFolderUri, fileName));
                }
            } else if (fileType === vscode.FileType.Directory && fileName === 'resources') {
                filesToDelete.push(vscode.Uri.joinPath(serviceFolderUri, fileName));
            }
        }

        for (const fileUri of filesToDelete) {
            await vscode.workspace.fs.delete(fileUri, { recursive: true });
            cacheService.invalidateByUri(fileUri);
        }

        const isRootFolder = rootUri && serviceFolderUri.fsPath === rootUri.fsPath;

        if (!isRootFolder) {
            const remainingEntries = await vscode.workspace.fs.readDirectory(serviceFolderUri);
            if (remainingEntries.length === 0) {
                await vscode.workspace.fs.delete(serviceFolderUri, { recursive: true });
            }
        }

        vscode.window.showInformationMessage(`Service "${serviceName}" and all related files deleted successfully`);
    } catch (error) {
        throw error;
    }
}

async function setupFileWatchers(context: ExtensionContext): Promise<void> {
    const cacheService = FileCacheService.getInstance();

    const extensionsToWatch = new Set<string>();

    try {
        const configService = ProjectConfigService.getInstance();
        const workspaceUri = vscode.workspace.workspaceFolders?.[0]?.uri;

        if (workspaceUri) {
            await configService.loadWorkspaceConfig(workspaceUri);
        }

        const allConfigs = configService.getAllConfigs();

        if (allConfigs.length > 0) {
            allConfigs.forEach(config => {
                Object.values(config.extensions).forEach((extension: string) => {
                    const pattern = `**/*${extension}`;
                    extensionsToWatch.add(pattern);
                });
            });
        } else {
            const defaultAppNames = ['qip'];
            defaultAppNames.forEach(appName => {
                const defaultConfig = configService.buildDefaultConfig(appName);
                Object.values(defaultConfig.extensions).forEach((extension: string) => {
                    const pattern = `**/*${extension}`;
                    extensionsToWatch.add(pattern);
                });
            });
        }

    } catch (error) {
        console.error('[QIP] Failed to setup file watchers from config, using qip defaults:', error);

        const fallbackConfig = ProjectConfigService.getInstance().buildDefaultConfig('qip');
        Object.values(fallbackConfig.extensions).forEach((extension: string) => {
            const pattern = `**/*${extension}`;
            extensionsToWatch.add(pattern);
        });
    }

    extensionsToWatch.forEach(pattern => {
        const watcher = vscode.workspace.createFileSystemWatcher(pattern);

        watcher.onDidChange(uri => cacheService.invalidateByUri(uri));
        watcher.onDidDelete(uri => cacheService.invalidateByUri(uri));
        watcher.onDidCreate(uri => cacheService.invalidateByUri(uri));

        context.subscriptions.push(watcher);
        console.log(`[QIP] File watcher created for pattern: ${pattern}`);
    });

    console.log(`[QIP] Total file watchers created: ${extensionsToWatch.size}`);

    const configWatcher = vscode.workspace.createFileSystemWatcher(`**/${CONFIG_FILENAME}`);
    configWatcher.onDidChange(() => {
        vscode.window.showInformationMessage(
            'QIP config changed. Reload window to apply new file extensions.',
            'Reload'
        ).then(selection => {
            if (selection === 'Reload') {
                vscode.commands.executeCommand('workbench.action.reloadWindow');
            }
        });
    });
    context.subscriptions.push(configWatcher);
}

export function activate(context: ExtensionContext): QipExtensionAPI {
    const fileApiImpl = new VSCodeFileApi(context);
    setFileApi(fileApiImpl);

    initNavigationState(context);

    currentExtensionVersion = context.extension.packageJSON.version ?? "unknown";

    const projectConfigService = ProjectConfigService.getInstance();
    projectConfigService.setContext(context);

    setupFileWatchers(context).catch(error => {
        console.error('[QIP] Failed to setup file watchers:', error);
    });

    // Register QIP Explorer provider
    const qipProvider = new QipExplorerProvider(context);
    globalQipProvider = qipProvider;

    context.subscriptions.push(
        vscode.window.registerTreeDataProvider('qip-main', qipProvider)
    );

    const editorParams = {
        webviewOptions: {
            retainContextWhenHidden: true,
        },
        supportsMultipleEditorsPerDocument: false,
    };

    context.subscriptions.push(
        vscode.window.registerCustomEditorProvider(
            'qip.chainFile.editor',
            new ChainFileEditorProvider(context),
            editorParams
        )
    );

    context.subscriptions.push(
        vscode.window.registerCustomEditorProvider(
            'qip.serviceFile.editor',
            new ChainFileEditorProvider(context),
            editorParams
        )
    );

    context.subscriptions.push(
        vscode.window.registerCustomEditorProvider(
            'qip.contextServiceFile.editor',
            new ChainFileEditorProvider(context),
            editorParams
        )
    );

    context.subscriptions.push(vscode.commands.registerCommand('qip.open', function () {
		// The code you place here will be executed every time your command is executed

		// Display a message box to the user
		//vscode.window.showInformationMessage('Hello World from qip-visual-studio-extension in a web extension host!');

	   const panel = vscode.window.createWebviewPanel(
          'qipWebView', // Identifies the type of the webview
          'QIP Offline Chain Editor', // Title of the panel
          vscode.ViewColumn.One, // Show in the first column
          {
            enableScripts: true, // Allow JavaScript execution
            retainContextWhenHidden: true, // Keep state when hidden
            enableCommandUris: true
          }
        );

        enrichWebview(panel, context, undefined);
    }));

    context.subscriptions.push(vscode.commands.registerCommand('qip.createChain',
        async () => {
            const result = await fileApiImpl.createEmptyChain();
            qipProvider.refresh();
            if (result) {
                openWebviewForElement(context, result.folderUri, 'chain');
            }
        }
    ));

    context.subscriptions.push(vscode.commands.registerCommand('qip.createChainParent',
        async () => {
            const result = await fileApiImpl.createEmptyChain(true);
            qipProvider.refresh();
            if (result) {
                openWebviewForElement(context, result.folderUri, 'chain');
            }
        }
    ));

    context.subscriptions.push(vscode.commands.registerCommand('qip.createService',
        async () => {
            const result = await fileApiImpl.createEmptyService();
            qipProvider.refresh();
            if (result) {
                const ext = getExtensionsForUri();
                const serviceFileUri = vscode.Uri.joinPath(result.folderUri, `${result.serviceId}${ext.service}`);
                openWebviewForElement(context, serviceFileUri, 'service');
            }
        }
    ));

    context.subscriptions.push(vscode.commands.registerCommand('qip.createContextService',
        async () => {
            const result = await fileApiImpl.createEmptyContextService();
            qipProvider.refresh();
            if (result) {
                const ext = getExtensionsForUri();
                const serviceFileUri = vscode.Uri.joinPath(result.folderUri, `${result.serviceId}${ext.contextService}`);
                openWebviewForElement(context, serviceFileUri, 'service');
            }
        }
    ));

    // Register refresh command
    context.subscriptions.push(
        vscode.commands.registerCommand('qip.refreshExplorer', () => {
            qipProvider.refresh();
        })
    );

    context.subscriptions.push(
        vscode.workspace.onDidChangeConfiguration(e => {
            if (e.affectsConfiguration("editor.fontSize") ||
                e.affectsConfiguration("editor.fontFamily") ||
                e.affectsConfiguration("editor.fontWeight") ||
                e.affectsConfiguration("editor.lineHeight") ||
                e.affectsConfiguration("editor.minimap.enabled") ||
                e.affectsConfiguration("editor.minimap.maxColumn") ||
                e.affectsConfiguration("editor.scrollbar.vertical") ||
                e.affectsConfiguration("editor.scrollbar.horizontal") ||
                e.affectsConfiguration("editor.scrollbar.verticalScrollbarSize") ||
                e.affectsConfiguration("editor.scrollbar.horizontalScrollbarSize")) {
                broadcastThemeToAllWebviews();
            }
        })
    );

    context.subscriptions.push(
        vscode.window.onDidChangeActiveColorTheme(() => {
            broadcastThemeToAllWebviews();
        })
    );

    // Register reveal in explorer command
    context.subscriptions.push(
        vscode.commands.registerCommand('qip.revealInExplorer', async (item: any) => {
            if (item && item.fileUri) {
                try {
                    // Determine the correct editor based on file type
                    const fileName = item.fileUri.fsPath;
                    let editorType = 'qip.chainFile.editor'; // default

                    const fileExtensions = getExtensionsForUri({ path: fileName });
                    if (fileName.endsWith(fileExtensions.service)) {
                        editorType = 'qip.serviceFile.editor';
                    } else if (fileName.endsWith(fileExtensions.chain)) {
                        editorType = 'qip.chainFile.editor';
                    }

                    // Open the file with custom editor
                    await vscode.commands.executeCommand('vscode.openWith', item.fileUri, editorType);
                } catch (error) {
                    console.error('Failed to open file with custom editor:', error);
                    // Fallback to text editor if custom editor fails
                    try {
                        const document = await vscode.workspace.openTextDocument(item.fileUri);
                        await vscode.window.showTextDocument(document, {
                            viewColumn: vscode.ViewColumn.Active,
                            preview: false
                        });
                    } catch (fallbackError) {
                        console.error('Failed to open file in text editor:', fallbackError);
                        vscode.window.showErrorMessage(`Failed to open file: ${fallbackError}`);
                    }
                }
            }
        })
    );

    // Simplified approach - no complex UI refresh mechanism

    // Register delete commands
    context.subscriptions.push(
        vscode.commands.registerCommand('qip.deleteService', async (item: any) => {
            if (item && item.fileUri) {
                const result = await vscode.window.showWarningMessage(
                    `Are you sure you want to delete service "${item.label}"?`,
                    { modal: true },
                    'Delete'
                );
                if (result === 'Delete') {
                    try {
                        await deleteServiceWithRelatedFiles(item.fileUri, item.label);
                        qipProvider.refresh();
                    } catch (error) {
                        vscode.window.showErrorMessage(`Failed to delete service: ${error}`);
                    }
                }
            }
        })
    );

    context.subscriptions.push(
        vscode.commands.registerCommand('qip.deleteChain', async (item: any) => {
            if (item && item.fileUri) {
                const result = await vscode.window.showWarningMessage(
                    `Are you sure you want to delete chain "${item.label}"?`,
                    { modal: true },
                    'Delete'
                );
                if (result === 'Delete') {
                    try {
                        await vscode.workspace.fs.delete(item.fileUri);
                        qipProvider.refresh();
                        vscode.window.showInformationMessage(`Chain "${item.label}" deleted successfully`);
                    } catch (error) {
                        vscode.window.showErrorMessage(`Failed to delete chain: ${error}`);
                    }
                }
            }
        })
    );

    // Register open in text editor command
    context.subscriptions.push(
        vscode.commands.registerCommand('qip.openInTextEditor', async (item: any) => {
            if (item && item.fileUri) {
                try {
                    const document = await vscode.workspace.openTextDocument(item.fileUri);
                    await vscode.window.showTextDocument(document, {
                        viewColumn: vscode.ViewColumn.Active,
                        preview: false
                    });
                } catch (error) {
                    console.error(`Failed to open file in text editor:`, error);
                    vscode.window.showErrorMessage(`Failed to open file in text editor: ${error}`);
                }
            }
        })
    );

    return ConfigApiProvider.getInstance();
}

// This method is called when your extension is deactivated
export function deactivate() {}


function getWebviewContent(context: ExtensionContext, webview: Webview) {

  // Dynamically load the JS and CSS files
    const jsFileUri = vscode.Uri.joinPath(
        context.extensionUri,
        'node_modules',
        '@netcracker',
        'qip-ui',
        'dist-lib',
        'index.es.js'
    );
    const cssFileUri = vscode.Uri.joinPath(
        context.extensionUri,
        'node_modules',
        '@netcracker',
        'qip-ui',
        'dist-lib',
        'qip-ui.css'
    );
    const jsUri = webview.asWebviewUri(jsFileUri);
    const cssUri = webview.asWebviewUri(cssFileUri);

    // Return the HTML content for the webview
    return `
    <!DOCTYPE html>
    <html lang="en">
      <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>QIP Offline Chain Editor</title>
        <link href="${cssUri}" rel="stylesheet">
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
}








