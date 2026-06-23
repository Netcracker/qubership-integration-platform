import * as vscode from "vscode";
import { Uri } from "vscode";
import { getExtensionsForUri } from "./response/file/fileExtensions";

export type EditorViewTypes = {
  chain: string;
  service: string;
  contextService: string;
  mcpService: string;
};

const DEFAULT_EDITOR_VIEW_TYPES: EditorViewTypes = {
  chain: "qip.chainFile.editor",
  service: "qip.serviceFile.editor",
  contextService: "qip.contextServiceFile.editor",
  mcpService: "qip.mcpServiceFile.editor",
};

let editorViewTypes: EditorViewTypes = { ...DEFAULT_EDITOR_VIEW_TYPES };

export function configureEditorViewTypes(types: Partial<EditorViewTypes>): void {
  editorViewTypes = { ...editorViewTypes, ...types };
}

export function getEditorViewTypes(): Readonly<EditorViewTypes> {
  return editorViewTypes;
}

export function resetEditorViewTypesForTests(): void {
  editorViewTypes = { ...DEFAULT_EDITOR_VIEW_TYPES };
}

export function getEditorViewTypeForUri(uri: Uri): string {
  const fileExtensions = getExtensionsForUri(uri);
  const filePath = uri.path;

  if (filePath.endsWith(fileExtensions.chain)) {
    return editorViewTypes.chain;
  }
  if (filePath.endsWith(fileExtensions.service)) {
    return editorViewTypes.service;
  }
  if (filePath.endsWith(fileExtensions.contextService)) {
    return editorViewTypes.contextService;
  }
  if (filePath.endsWith(fileExtensions.mcpService)) {
    return editorViewTypes.mcpService;
  }

  throw new Error(`Unable to find an editor for document: ${uri}`);
}

export async function openDocumentInEditor(uri: Uri): Promise<void> {
  const editor = getEditorViewTypeForUri(uri);
  await vscode.commands.executeCommand("vscode.openWith", uri, editor);
}
