import * as vscode from "vscode";
import { ExtensionContext, TextDocument, Uri, WebviewPanel } from "vscode";
import { Chain, VSCodeMessage, VSCodeResponse } from "@netcracker/qip-ui";
import { ContentParser } from "./api-services";
import { getApiResponse } from "./response/apiRouter";
import { schemaToChain } from "./response/chainApiRead";
import { updateNavigationStateValue } from "./response/navigationUtils";
import { openDocumentInEditor } from "./editorViewTypes";

export type VSCodeMessageWrapper = {
  command: string;
  data: VSCodeMessage<unknown>;
};

export type ChainDiffEditorOptions = {
  context: ExtensionContext;
  panel: WebviewPanel;
  documents: vscode.CustomEditorDiffDocuments<TextDocument>;
  openDocumentInEditor?: (uri: Uri) => Promise<void>;
};

export function registerChainDiffMessageHandlers(
  options: ChainDiffEditorOptions,
): vscode.Disposable {
  const {
    context,
    panel,
    documents,
    openDocumentInEditor: openEditor = openDocumentInEditor,
  } = options;

  return panel.webview.onDidReceiveMessage(
    async (message: VSCodeMessageWrapper) => {
      if (message.data.type === "comparedDocumentsRequest") {
        const getDocument = async (d: vscode.TextDocument) => {
          return schemaToChain(
            d.uri,
            ContentParser.parseContent(d.getText()),
          );
        };
        const response: VSCodeResponse<{
          original: Chain;
          modified: Chain;
        }> = {
          requestId: message.data.requestId,
          type: "comparedDocumentsResponse",
          payload: {
            original: await getDocument(documents.original),
            modified: await getDocument(documents.modified),
          },
        };
        panel.webview.postMessage(response);
      } else if (message.data.type === "navigateComparedDocumentInNewTab") {
        const { type, path } = message.data.payload as {
          type: string;
          path: string;
        };
        const uri =
          type === "left" ? documents.original.uri : documents.modified.uri;

        try {
          const documentUri =
            uri.scheme === "git"
              ? uri
              : await getApiResponse(
                {
                  type: "navigateInNewTab",
                  requestId: crypto.randomUUID(),
                  payload: path,
                },
                uri,
                context,
              );
          await updateNavigationStateValue(context, documentUri, path);
          await openDocumentInEditor(documentUri);
        } catch (e) {
          console.error("Failed to fetch data for QIP Extension API", e);
        }
      }
    },
  );
}


