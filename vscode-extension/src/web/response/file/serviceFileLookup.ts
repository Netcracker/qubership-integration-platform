import { Uri } from "vscode";
import { fileApi } from "./fileApiProvider";
import { getExtensionsForFile } from "./fileExtensions";
import { plainServiceExtensions, ServiceExtensions } from "./serviceFileType";

function extensionsToScan(extensions?: ServiceExtensions): string[] {
  return plainServiceExtensions(extensions ?? getExtensionsForFile());
}

/** No service name carried the id. `causes` is what each name reported, in scan order. */
export class ServiceFileNotFoundError extends Error {
  constructor(
    readonly serviceId: string,
    readonly causes: readonly unknown[],
  ) {
    super(
      `No service file carries id ${serviceId}: ${causes.map(describeCause).join("; ")}`,
    );
    this.name = "ServiceFileNotFoundError";
  }
}

function describeCause(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}

/**
 * Resolves a plain service file by id across every name it can carry. A typed name wins over
 * the legacy sibling, the same precedence `ApiGroupService.resolveGroupFile` applies to a group
 * stored under two extensions, so a converted service resolves to the file the next write lands on.
 *
 * A miss and a broken scan both come back as one `ServiceFileNotFoundError` naming every failure.
 * `FileApi` reports both as a plain `Error`, so the two cannot be told apart here, and reporting
 * the last failure alone hid the broken file that made every later name fail too.
 */
export async function findServiceFileById(
  serviceId: string,
  extensions?: ServiceExtensions,
): Promise<Uri> {
  const causes: unknown[] = [];
  for (const extension of extensionsToScan(extensions)) {
    try {
      return await fileApi.findFileById(serviceId, extension);
    } catch (error) {
      causes.push(error);
    }
  }
  throw new ServiceFileNotFoundError(serviceId, causes);
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
