// The single write path for a plain service document, and the point where an old-format file
// becomes a new-format one. Reads accept `<id>.service.<app>.yaml`; writes emit only the name that
// states the type, so a project migrates as its services are edited and git records a rename.

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
 * re-point instead of reading a deleted path. The open service editor is such a caller: its webview
 * was handed the uri once, and every later message on that tab would otherwise fail.
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
 * A document whose name states the type must not restate it in `content.integrationSystemType` —
 * the typed schemas refuse that (`typed-service-content.schema.yaml`), and the backend refuses a
 * name and a field that disagree. A name the backend reads no type from keeps the field, which is
 * then the only thing that states it.
 *
 * The service folder keeps its name. The backend still finds a converted service whose id contains
 * a dot only because the folder states that id.
 */
export async function writeServiceInCurrentFormat(
  serviceFileUri: Uri,
  service: any,
): Promise<Uri> {
  const extensions = getExtensionsForUri(serviceFileUri);
  // The name wins whenever it states a kind, context and MCP included, so a write never moves a
  // file out of the family it is in. Only a body-stated type may promote a legacy name, and only to
  // one of the three plain types: name and `$schema` together are what tell the backend a context or
  // an MCP document apart, so a body claiming one of those leaves the file legacy.
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

  // The document now claims the schema its new name implies. Same source as both create paths: the
  // project config, read for the app the file itself belongs to rather than for whichever app the
  // last opened document made current.
  service.$schema = serviceSchemaUrlForType(
    type,
    schemaUrlsForApp(extensions.appName),
  );

  const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
  const targetUri = vscode.Uri.joinPath(serviceFolderUri, targetName);
  await fileApi.writeMainService(targetUri, service);
  // Write first, delete second: an interrupted conversion leaves both files, and the typed one
  // wins every read, rather than leaving no service file at all.
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
    // The save itself succeeded and has already said so. Two files now carry one service id, which
    // the extension survives but an import reads as a duplicate, so name the one to remove.
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
