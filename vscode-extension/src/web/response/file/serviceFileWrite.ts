// The single write path for a plain service document, and the point where an old-format file
// becomes a new-format one. See "Conversion on first write" in `vscode-extension/CLAUDE.md`.

import * as vscode from "vscode";
import { Uri } from "vscode";
import { fileApi } from "./fileApiProvider";
import {
  extractFilename,
  getExtensionsForUri,
  getSchemaUrlsForApp,
} from "./fileExtensions";
import {
  isPlainServiceType,
  serviceTypeFromSchema,
  serviceFileNameForType,
  serviceSchemaUrlForType,
} from "./serviceFileType";

type ServiceFileMovedListener = (from: Uri, to: Uri) => void;

const serviceFileMovedListeners = new Set<ServiceFileMovedListener>();

/**
 * Fires when a conversion replaces a service file, so a caller still holding the old uri can
 * re-point instead of reading a deleted path. The open service editor is such a caller.
 */
export function onServiceFileMoved(listener: ServiceFileMovedListener): {
  dispose(): void;
} {
  serviceFileMovedListeners.add(listener);
  return {
    dispose: () => {
      serviceFileMovedListeners.delete(listener);
    },
  };
}

/**
 * Writes the service and returns the file it landed in, which is not the file it came from once a
 * conversion happens. Callers that re-read the service afterwards must use the returned uri.
 *
 * The current format states the type in `$schema` and nowhere else, so a document that resolves a
 * type is stamped with that schema and loses `content.integrationSystemType`
 * (`typed-service-content.schema.yaml` forbids the field). A document that resolves none is left
 * exactly as it is, rather than being stamped with a schema for a type nobody stated.
 *
 * Two things can move a file here: a per-type name left over from #553, which converts back to the
 * plain one, and a body-stated type on a document of a kind whose name is its own
 * (`isPlainServiceType` gates that — gate the resolved type instead and every context and MCP file
 * is renamed to `.service.` on its next edit). The service folder keeps its name, which is how the
 * backend still finds a converted dotted id.
 */
export async function writeServiceInCurrentFormat(
  serviceFileUri: Uri,
  service: any,
): Promise<Uri> {
  const extensions = getExtensionsForUri(serviceFileUri);
  const schemaUrls = getSchemaUrlsForApp(extensions.appName);
  // `$schema` wins whenever it states a kind, so a write never moves a file out of the family it is
  // in. Only a body-stated type may promote a document that states none, and `isPlainServiceType`
  // gates that to the three plain types — gate the resolved type instead and a plain file whose
  // stale body claims CONTEXT is renamed into that family on its next edit.
  const fromSchema = serviceTypeFromSchema(service?.$schema, schemaUrls);
  const fromBody = service?.content?.integrationSystemType;
  const type =
    fromSchema ?? (isPlainServiceType(fromBody) ? fromBody : undefined);

  // The three plain kinds share one name, so a resolved plain type names the file the same way an
  // unresolved one does; only the two typeless kinds keep a name of their own.
  const targetName = serviceFileNameForType(serviceFileUri, type, extensions);

  if (type !== undefined) {
    // Stamped only when `$schema` did not already state the type — a document carrying a schema url
    // this project does not configure still resolves through the schema file name, and rewriting it
    // would hand a file of one installation the url of another.
    if (fromSchema === undefined) {
      service.$schema = serviceSchemaUrlForType(type, schemaUrls);
    }
    if (service?.content && isPlainServiceType(type)) {
      delete service.content.integrationSystemType;
    }
  }

  if (targetName === extractFilename(serviceFileUri)) {
    await fileApi.writeMainService(serviceFileUri, service);
    return serviceFileUri;
  }

  const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
  const targetUri = vscode.Uri.joinPath(serviceFolderUri, targetName);
  // Write first, delete second: an interrupted conversion leaves both files, the typed one winning
  // every read, rather than leaving no service file at all.
  await fileApi.writeMainService(targetUri, service);
  await deleteLegacySibling(serviceFileUri, targetName);
  notifyServiceFileMoved(serviceFileUri, targetUri);
  return targetUri;
}

async function deleteLegacySibling(
  fileUri: Uri,
  targetName: string,
): Promise<void> {
  try {
    await fileApi.deleteFile(fileUri);
  } catch (error) {
    const staleName = extractFilename(fileUri);
    console.error(
      `Failed to delete the legacy service file ${fileUri.path} after converting it:`,
      error,
    );
    // Two files now carry one service id, which an import reads as a duplicate, so name the stale one.
    vscode.window.showWarningMessage(
      `The service was saved as "${targetName}", but "${staleName}" could not be deleted.` +
        " Delete it by hand — two files now describe the same service.",
    );
  }
}

function notifyServiceFileMoved(from: Uri, to: Uri): void {
  for (const listener of serviceFileMovedListeners) {
    try {
      listener(from, to);
    } catch (error) {
      console.error("A service file move listener failed:", error);
    }
  }
}
