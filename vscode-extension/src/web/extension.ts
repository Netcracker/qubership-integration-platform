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
import * as path from "path";
import { setFileApi } from "./response/file";
import { VSCodeFileApi } from "./response/file/fileApiImpl";
import { getExtensionsForUri, setCurrentFileContext, extractFilename } from "./response/file/fileExtensions";
import { QipExplorerProvider } from "./qipExplorer";
import {VSCodeMessage, VSCodeResponse} from "@netcracker/qip-ui";

let globalQipProvider: QipExplorerProvider | null = null;

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

        enrichWebview(panel, this.context, document.uri);
    }
}

function openWebviewForElement(context: ExtensionContext, fileUri: Uri, elementType: 'chain' | 'service') {
    const panel = vscode.window.createWebviewPanel(
        'qipWebView', // Identifies the type of the webview
        `QIP ${elementType === 'chain' ? 'Chain' : 'Service'} Editor`, // Title of the panel
        vscode.ViewColumn.One, // Show in the first column
        {
            enableScripts: true, // Allow JavaScript execution
            retainContextWhenHidden: true, // Keep state when hidden
            enableCommandUris: true
        }
    );

    enrichWebview(panel, context, fileUri);
}

function enrichWebview(panel: WebviewPanel, context: ExtensionContext, fileUri: Uri | undefined = undefined) {
    type VSCodeMessageWrapper = {
        command: string;
        data: VSCodeMessage<any>;
    };

    panel.webview.html = getWebviewContent(context, panel.webview);

    panel.webview.onDidReceiveMessage(async (message: VSCodeMessageWrapper) => {
        console.log('QIP Extension API Request:', message);

        if (fileUri) {
            setCurrentFileContext(extractFilename(fileUri));
        }

        const response: VSCodeResponse<any> = {
            requestId: message.data.requestId,
            type: message.data.type,
        };

        try {
            response.payload = await getApiResponse(message.data, fileUri, context);
            console.log('QIP Extension API Response:', response);

            if (message.data.type === "openChainInNewTab") {
                vscode.commands.executeCommand('vscode.openWith', response.payload, 'qip.chainFile.editor');
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

// Your extension is activated the very first time the command is executed
export function activate(context: ExtensionContext) {
    const fileApiImpl = new VSCodeFileApi(context);
    setFileApi(fileApiImpl);

    // Register QIP Explorer provider
    const qipProvider = new QipExplorerProvider(context);
    globalQipProvider = qipProvider;

    context.subscriptions.push(
        vscode.window.registerTreeDataProvider('qip-main', qipProvider)
    );

    context.subscriptions.push(
        vscode.window.registerCustomEditorProvider(
            'qip.chainFile.editor',
            new ChainFileEditorProvider(context),
            {
                webviewOptions: {
                    retainContextWhenHidden: true
                },
                supportsMultipleEditorsPerDocument: false
            }
        )
    );

    context.subscriptions.push(
        vscode.window.registerCustomEditorProvider(
            'qip.serviceFile.editor',
            new ChainFileEditorProvider(context),
            {
                webviewOptions: {
                    retainContextWhenHidden: true
                },
                supportsMultipleEditorsPerDocument: false
            }
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

    // Register refresh command
    context.subscriptions.push(
        vscode.commands.registerCommand('qip.refreshExplorer', () => {
            qipProvider.refresh();
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
                        await vscode.workspace.fs.delete(item.fileUri);
                        qipProvider.refresh();
                        vscode.window.showInformationMessage(`Service "${item.label}" deleted successfully`);
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
                    // Open file in text editor instead of custom editor
                    const document = await vscode.workspace.openTextDocument(item.fileUri);
                    await vscode.window.showTextDocument(document, {
                        viewColumn: vscode.ViewColumn.Active,
                        preview: false
                    });
                    console.log(`QIP Explorer: Opened ${item.label} in text editor`);
                } catch (error) {
                    console.error(`Failed to open file in text editor:`, error);
                    vscode.window.showErrorMessage(`Failed to open file in text editor: ${error}`);
                }
            }
        })
    );

    console.log('QIP Extension: QIP Explorer providers registered successfully');
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
      </head>
      <body>
        <div id="app-root"></div>
      </body>
    </html>
  `;
}








