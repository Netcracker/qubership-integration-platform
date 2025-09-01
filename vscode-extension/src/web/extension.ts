import {
    CancellationToken,
    CustomTextEditorProvider,
    ExtensionContext,
    TextDocument, Uri,
    Webview,
    WebviewPanel
} from "vscode";
import {getApiResponse} from "./response/chainApi";
import {VSCodeMessage, VSCodeResponse} from "./response/apiTypes";
import * as path from "path";
import { setFileApi } from "./response/file/fileApiProvider";
import { VSCodeFileApi } from "./response/file/fileApiImpl";

const vscode = require('vscode');


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

        enrichWebview(panel, this.context, getDocumentDir(document));
    }
}

function enrichWebview(panel: WebviewPanel, context: ExtensionContext, mainFolderUri: Uri | undefined = undefined) {
    type VSCodeMessageWrapper = {
        command: string;
        data: VSCodeMessage;
    };

    panel.webview.html = getWebviewContent(context, panel.webview);

    panel.webview.onDidReceiveMessage(async (message: VSCodeMessageWrapper) => {
        // Handle the mock response
        console.log('QIP Extension API Request:', message.data);

        const response: VSCodeResponse = {
            requestId: message.data.requestId,
            type: message.data.type,
        };
        try {
            response.payload = await getApiResponse(message.data, mainFolderUri);
            console.log('QIP Extension API Response:', response);
        } catch (e) {
            console.error("Failed to fetch data for QIP Extension API", e);
            response.error = e;
        }
        panel.webview.postMessage(response);
    });
}

// Your extension is activated the very first time the command is executed
export function activate(context: ExtensionContext) {
    const fileApi = new VSCodeFileApi(context);
    setFileApi(fileApi);

    context.subscriptions.push(
        vscode.window.registerCustomEditorProvider(
            'qip.chainFile.editor',
            new ChainFileEditorProvider(context),
            {
                webviewOptions: {
                    retainContextWhenHidden: true,
                    enableScripts: true,
                    enableCommandUris: true
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

        enrichWebview(panel, context);
    }));

    context.subscriptions.push(vscode.commands.registerCommand('qip.createChain',
        async () => {
            await fileApi.createEmptyChain();
        }
    ));

    context.subscriptions.push(vscode.commands.registerCommand('qip.createChainParent',
        async () => {
            await fileApi.createEmptyChain(true);
        }
    ));
}

function getDocumentDir(document: TextDocument): Uri {
    console.log('QIP Extension API Document:', document);
    const filePath = path.normalize(document.uri.fsPath);
    console.log('QIP Extension API filePath:', filePath);
    const sanitizedFilePath = filePath.replace(/\\/g, '/');
    console.log('QIP Extension API sanitizedFilePath:', sanitizedFilePath);
    const dirPath = path.dirname(sanitizedFilePath);
    console.log('QIP Extension API dirPath:', dirPath);
    const res = vscode.Uri.file(dirPath);
    console.log('QIP Extension API res:', res);
    return res;
}

// This method is called when your extension is deactivated
export function deactivate() {}


function getWebviewContent(context: ExtensionContext, webview: Webview) {

  // Dynamically load the JS and CSS files with the unique hashes
  const jsFileUri = vscode.Uri.joinPath(context.extensionUri, 'media', 'index.js');
  const cssFileUri = vscode.Uri.joinPath(context.extensionUri, 'media', 'index.css');

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
        <div id="root"></div>
      </body>
    </html>
  `;
}



