// The single write path for a plain service document, and the point where an old-format file
// becomes a new-format one. See "Conversion on first write" in `vscode-extension/CLAUDE.md`.

import * as vscode from "vscode";
import { Uri } from "vscode";
import { fileApi } from "./fileApiProvider";
import { extractFilename, getExtensionsForUri } from "./fileExtensions";
import { ProjectConfigService } from "../../services/ProjectConfigService";
import {
  fileNameStatesType,
  isPlainServiceType,
  serviceTypeFromUri,
  serviceFileNameForType,
  serviceSchemaUrlForType,
  ServiceSchemaUrls,
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
 * A name the backend reads a type from must not restate it in `content.integrationSystemType`
 * (`typed-service-content.schema.yaml` forbids the field); a name it reads none from keeps it.
 * The service folder keeps its name, which is how the backend still finds a converted dotted id.
 */
export async function writeServiceInCurrentFormat(
  serviceFileUri: Uri,
  service: any,
): Promise<Uri> {
  const extensions = getExtensionsForUri(serviceFileUri);
  // The name wins whenever it states a kind, so a write never moves a file out of the family it is
  // in. Only a body-stated type may promote a legacy name, and `isPlainServiceType` gates that to
  // the three plain types — gate the resolved type instead and every context and MCP file is
  // renamed to `.service.` on its next edit.
  const fromName = serviceTypeFromUri(serviceFileUri, extensions);
  const fromBody = service?.content?.integrationSystemType;
  const type =
    fromName ?? (isPlainServiceType(fromBody) ? fromBody : undefined);

  const targetName = serviceFileNameForType(serviceFileUri, type, extensions);

  if (service?.content && fileNameStatesType(targetName, extensions)) {
    delete service.content.integrationSystemType;
  }

  if (targetName === extractFilename(serviceFileUri)) {
    await fileApi.writeMainService(serviceFileUri, service);
    return serviceFileUri;
  }

  // The schema the new name implies, read for the app the *file* belongs to rather than for
  // whichever app the last opened document made current.
  service.$schema = serviceSchemaUrlForType(
    type,
    schemaUrlsForApp(extensions.appName),
  );

  const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
  const targetUri = vscode.Uri.joinPath(serviceFolderUri, targetName);
  // Write first, delete second: an interrupted conversion leaves both files, the typed one winning
  // every read, rather than leaving no service file at all.
  await fileApi.writeMainService(targetUri, service);
  await deleteLegacySibling(serviceFileUri, targetName);
  notifyServiceFileMoved(serviceFileUri, targetUri);
  return targetUri;
}

function schemaUrlsForApp(appName: string): ServiceSchemaUrls {
  try {
    const configService = ProjectConfigService.getInstance?.();
    if (configService?.isConfigLoaded()) {
      const config = configService
        .getAllConfigs()
        .find((candidate) => candidate.appName === appName);
      if (config) {
        return config.schemaUrls;
      }
    }
  } catch (error) {
    console.error(
      `Failed to read the schema URLs configured for app ${appName}:`,
      error,
    );
  }
  return ProjectConfigService.getConfig().schemaUrls;
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
