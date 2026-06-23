import * as vscode from "vscode";
import { ExtensionContext, TextDocument, Uri, WebviewPanel } from "vscode";
import { Chain, VSCodeMessage, VSCodeResponse } from "@netcracker/qip-ui";
import { ContentParser } from "./api-services";
import { CHAIN_DIFF_PATH, getApiResponse } from "./response/apiRouter";
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

export function getChainDiffUri(): Uri {
  return Uri.parse(CHAIN_DIFF_PATH);
}

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
      if (!message?.data) {
        return;
      }

      if (message.data.type === "comparedDocumentsRequest") {
        const getDocument = async (document: TextDocument) => {
          const text = document.getText().trim();
          return schemaToChain(
            document.uri,
            ContentParser.parseContent(text),
          );
        };

        const response: VSCodeResponse<{ original: Chain; modified: Chain }> = {
          requestId: message.data.requestId,
          type: "comparedDocumentsResponse",
          payload: {
            original: await getDocument(documents.original),
            modified: await getDocument(documents.modified),
          },
        };
        panel.webview.postMessage(response);
        return;
      }

      if (message.data.type === "navigateComparedDocumentInNewTab") {
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
          await openEditor(documentUri);
        } catch (e) {
          console.error("Failed to open compared document in new tab", e);
        }
      }
    },
  );
}


