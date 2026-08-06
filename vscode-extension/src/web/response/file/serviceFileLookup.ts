import { Uri } from "vscode";
import { fileApi } from "./fileApiProvider";
import { getExtensionsForFile } from "./fileExtensions";
import { plainServiceExtensions, ServiceExtensions } from "./serviceFileType";

function extensionsToScan(extensions?: ServiceExtensions): string[] {
  return plainServiceExtensions(extensions ?? getExtensionsForFile());
}

/**
 * Resolves a plain service file by id across every name it can carry. A typed name wins over
 * the legacy sibling, the same precedence `ApiGroupService.resolveGroupFile` applies to a group
 * stored under two extensions, so a converted service resolves to the file the next write lands on.
 */
export async function findServiceFileById(
  serviceId: string,
  extensions?: ServiceExtensions,
): Promise<Uri> {
  let lastError: unknown;
  for (const extension of extensionsToScan(extensions)) {
    try {
      return await fileApi.findFileById(serviceId, extension);
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError instanceof Error
    ? lastError
    : new Error(`Service file for id ${serviceId} not found`);
}

/** Every plain service file in the workspace, typed names ahead of legacy ones. */
export async function findServiceFiles(
  extensions?: ServiceExtensions,
): Promise<Uri[]> {
  const perExtension = await Promise.all(
    extensionsToScan(extensions).map((extension) =>
      fileApi.findFiles(extension),
    ),
  );
  return perExtension.flat();
}
