// The single write path for a plain service document, and the point where an old-format file
// becomes a new-format one. Reads accept `<id>.service.<app>.yaml`; writes emit only the name that
// states the type, so a project migrates as its services are edited and git records a rename.

import * as vscode from "vscode";
import { Uri } from "vscode";
import { fileApi } from "./fileApiProvider";
import { extractFilename, getExtensionsForUri } from "./fileExtensions";
import { ProjectConfigService } from "../../services/ProjectConfigService";
import {
  resolveServiceType,
  serviceExtensionForType,
  serviceFileNameForType,
  serviceSchemaUrlForType,
} from "./serviceFileType";

/**
 * Writes the service and returns the file it landed in, which is not the file it came from once a
 * conversion happens. Callers that re-read the service afterwards must use the returned uri.
 *
 * A document whose name states the type must not restate it in `content.integrationSystemType` —
 * the typed schemas refuse that (`typed-service-content.schema.yaml`), and the backend refuses a
 * name and a field that disagree. The field is dropped for every typed name, so a write cannot
 * reintroduce it into a file that already carries one.
 *
 * The service folder keeps its name. The backend still finds a converted service whose id contains
 * a dot only because the folder states that id.
 */
export async function writeServiceInCurrentFormat(
  serviceFileUri: Uri,
  service: any,
): Promise<Uri> {
  const extensions = getExtensionsForUri(serviceFileUri);
  const type = resolveServiceType(serviceFileUri, service, extensions);

  if (
    service?.content &&
    serviceExtensionForType(type, extensions) !== extensions.service
  ) {
    delete service.content.integrationSystemType;
  }

  const targetName = serviceFileNameForType(serviceFileUri, type, extensions);
  if (targetName === extractFilename(serviceFileUri)) {
    await fileApi.writeMainService(serviceFileUri, service);
    return serviceFileUri;
  }

  // The document now claims the schema its new name implies. Same source as both create paths: the
  // project config, whose `schemaUrls` a project is free to override.
  service.$schema = serviceSchemaUrlForType(
    type,
    ProjectConfigService.getConfig().schemaUrls,
  );

  const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
  const targetUri = vscode.Uri.joinPath(serviceFolderUri, targetName);
  await fileApi.writeMainService(targetUri, service);
  // Write first, delete second: an interrupted conversion leaves both files, and the typed one
  // wins every read, rather than leaving no service file at all.
  await deleteLegacySibling(serviceFileUri);
  return targetUri;
}

async function deleteLegacySibling(fileUri: Uri): Promise<void> {
  try {
    await fileApi.deleteFile(fileUri);
  } catch (error) {
    console.error(
      `Failed to delete the legacy service file ${fileUri.path} after converting it:`,
      error,
    );
  }
}
