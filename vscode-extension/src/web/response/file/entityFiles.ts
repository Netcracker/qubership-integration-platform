// The two scans of a service folder that resolve an entity stored under two names: a group, held as
// `.specification-group.` or `.api-group.`, and an API, held as `.specification.` or `.api.`. Both
// pairs are what a conversion leaves behind and what a re-save overwrites, so both obey the contract
// in `lookupOutcome.ts` — see `resolveScannedEntities` there for why the refusal is not optional.
//
// Every read, write, list and delete of a group or an API resolves through these two functions, so
// the precedence rule is stated once: the current name wins, the same rule the service lookup
// applies to a typed name over the legacy one.

import { Uri } from "vscode";
import * as vscode from "vscode";
import { fileApi } from "./fileApiProvider";
import { getExtensionsForUri } from "./fileExtensions";
import {
  refuseUnreadableSibling,
  ResolvedEntity,
  resolveScannedEntities,
} from "./lookupOutcome";

/** The group files of a service folder, one per group id. */
export async function resolveGroupFiles(
  serviceFileUri: Uri,
): Promise<Map<string, ResolvedEntity<any>>> {
  const ext = getExtensionsForUri(serviceFileUri);
  return await resolveFolderEntities(
    serviceFileUri,
    await fileApi.getSpecificationGroupFiles(serviceFileUri),
    [ext.specificationGroup, ext.apiGroup],
    ext.apiGroup,
  );
}

/** The API files of a service folder, one per API id. */
export async function resolveApiFiles(
  serviceFileUri: Uri,
): Promise<Map<string, ResolvedEntity<any>>> {
  const ext = getExtensionsForUri(serviceFileUri);
  return await resolveFolderEntities(
    serviceFileUri,
    await fileApi.getSpecificationFiles(serviceFileUri),
    [ext.specification, ext.api],
    ext.api,
  );
}

async function resolveFolderEntities(
  serviceFileUri: Uri,
  fileNames: readonly string[],
  extensions: readonly string[],
  currentExtension: string,
): Promise<Map<string, ResolvedEntity<any>>> {
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
