import * as vscode from "vscode";
import { Uri } from "vscode";
import { getExtensionsForUri } from "./response/file/fileExtensions";

export type EditorViewTypes = {
  chain: string;
  service: string;
  externalService: string;
  internalService: string;
  implementedService: string;
  contextService: string;
  mcpService: string;
};

export const DEFAULT_EDITOR_VIEW_TYPES: EditorViewTypes = {
  chain: "qip.chainFile.editor",
  service: "qip.serviceFile.editor",
  externalService: "qip.externalServiceFile.editor",
  internalService: "qip.internalServiceFile.editor",
  implementedService: "qip.implementedServiceFile.editor",
  contextService: "qip.contextServiceFile.editor",
  mcpService: "qip.mcpServiceFile.editor",
};

let editorViewTypes: EditorViewTypes = { ...DEFAULT_EDITOR_VIEW_TYPES };

export function getEditorViewTypeForUri(uri: Uri): string {
  const fileExtensions = getExtensionsForUri(uri);
  const filePath = uri.path;

  if (filePath.endsWith(fileExtensions.chain)) {
    return editorViewTypes.chain;
  }
  // The per-type branches answer for files a #553 version wrote, which no write emits any more. A
  // project may also configure a plain extension the per-type ones end with, so they stay first.
  if (filePath.endsWith(fileExtensions.externalService)) {
    return editorViewTypes.externalService;
  }
  if (filePath.endsWith(fileExtensions.internalService)) {
    return editorViewTypes.internalService;
  }
  if (filePath.endsWith(fileExtensions.implementedService)) {
    return editorViewTypes.implementedService;
  }
  if (filePath.endsWith(fileExtensions.contextService)) {
    return editorViewTypes.contextService;
  }
  if (filePath.endsWith(fileExtensions.mcpService)) {
    return editorViewTypes.mcpService;
  }
  // Every plain service written from now on lands here, whatever its type.
  if (filePath.endsWith(fileExtensions.service)) {
    return editorViewTypes.service;
  }

  throw new Error(`Unable to find an editor for document: ${uri}`);
}

export async function openDocumentInEditor(uri: Uri): Promise<void> {
  const editor = getEditorViewTypeForUri(uri);
  await vscode.commands.executeCommand("vscode.openWith", uri, editor);
}
