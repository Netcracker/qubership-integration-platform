// The two scans of a service folder that resolve an entity stored under two names: a group, held as
// `.specification-group.` or `.api-group.`, and an API, held as `.specification.` or `.api.`. Both
// pairs are what a conversion leaves behind and what a re-save overwrites, so both obey the contract
// in `lookupOutcome.ts` — see `resolveScannedEntities` there for why the refusal is not optional.
//
// Every read, write, list and delete of a group or an API resolves through these two functions, and
// neither states a precedence of its own: `namePrecedence.ts` declares which name is current, and
// both the candidate set and the winner are derived from that declaration.

import { Uri } from "vscode";
import * as vscode from "vscode";
import { fileApi } from "./fileApiProvider";
import { getExtensionsForUri } from "./fileExtensions";
import {
  refuseUnreadableSibling,
  ScannedEntities,
  resolveScannedEntities,
} from "./lookupOutcome";
import {
  API_GROUP_NAMES,
  API_NAMES,
  candidateExtensions,
  currentExtension,
} from "./namePrecedence";

/** The group files of a service folder, one per group id. */
export async function resolveGroupFiles(
  serviceFileUri: Uri,
): Promise<ScannedEntities<any>> {
  const ext = getExtensionsForUri(serviceFileUri);
  return await resolveFolderEntities(
    serviceFileUri,
    await fileApi.getSpecificationGroupFiles(serviceFileUri),
    candidateExtensions(API_GROUP_NAMES, ext),
    currentExtension(API_GROUP_NAMES, ext),
  );
}

/** The API files of a service folder, one per API id. */
export async function resolveApiFiles(
  serviceFileUri: Uri,
): Promise<ScannedEntities<any>> {
  const ext = getExtensionsForUri(serviceFileUri);
  return await resolveFolderEntities(
    serviceFileUri,
    await fileApi.getSpecificationFiles(serviceFileUri),
    candidateExtensions(API_NAMES, ext),
    currentExtension(API_NAMES, ext),
  );
}

async function resolveFolderEntities(
  serviceFileUri: Uri,
  fileNames: readonly string[],
  extensions: readonly string[],
  currentExtension: string,
): Promise<ScannedEntities<any>> {
  const serviceFolderUri = vscode.Uri.joinPath(serviceFileUri, "..");
  return await resolveScannedEntities(
    fileNames.map((fileName) =>
      vscode.Uri.joinPath(serviceFolderUri, fileName),
    ),
    (fileUri) => fileApi.parseFile(fileUri),
    {
      idOf: (parsed: any) => parsed?.id,
      prefers: (fileUri) => fileUri.path.endsWith(currentExtension),
      onUnreadable: (entityId, resolved, unreadable) =>
        refuseUnreadableSibling(entityId, resolved, unreadable, extensions),
    },
  );
}
