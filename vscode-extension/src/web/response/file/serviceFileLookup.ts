import { Uri } from "vscode";
import { fileApi } from "./fileApiProvider";
import { getExtensionsForFile } from "./fileExtensions";
import {
  allServiceExtensions,
  plainServiceExtensions,
  ServiceExtensions,
  serviceIdFromFileName,
} from "./serviceFileType";
import {
  refuseUnreadableSibling,
  resolveFirstCandidate,
  UnreadableSiblingError,
} from "./lookupOutcome";

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
 * A name of higher precedence holds a file the scan could not read, and it may be this service's
 * own. Answering with the sibling instead is what has to be refused: every read would show the
 * sibling's superseded body, and the next write would put that body over the file nobody could
 * read — the conversion recomputes the target name from the type, so it lands on exactly it.
 */
export class UnreadableServiceFileError extends UnreadableSiblingError {
  constructor(serviceId: string, fileUri: Uri) {
    super(serviceId, fileUri, "service ");
    this.name = "UnreadableServiceFileError";
  }

  get serviceId(): string {
    return this.entityId;
  }
}

/**
 * Resolves a plain service file by id across every name it can carry. A typed name wins over
 * the legacy sibling, the same precedence `ApiGroupService.resolveGroupFile` applies to a group
 * stored under two extensions, so a converted service resolves to the file the next write lands on.
 *
 * A miss and a broken scan both come back as one `ServiceFileNotFoundError` naming every failure.
 * `FileApi` reports both as a plain `Error`, so the two cannot be told apart here, and reporting
 * the last failure alone hid the broken file that made every later name fail too. A file the scan
 * could not read is named among those causes: with nothing resolved there is no sibling for a write
 * to land beside, so it is a miss here — see the contract in `lookupOutcome.ts`.
 *
 * The one failure that is told apart is a file the scan could not read while another name *did*
 * answer: `refuseUnreadableSibling` decides, and falling through to the sibling is how a legacy body
 * gets written over a typed file.
 */
export async function findServiceFileById(
  serviceId: string,
  extensions?: ServiceExtensions,
): Promise<Uri> {
  const scanned = extensions ?? getExtensionsForFile();
  return await resolveFirstCandidate(
    extensionsToScan(scanned),
    (extension) => fileApi.findFileById(serviceId, extension),
    {
      onUnreadable: (unreadable, resolved) =>
        refuseUnreadableSibling(
          serviceId,
          resolved,
          unreadable,
          allServiceExtensions(scanned),
          (id, fileUri) => new UnreadableServiceFileError(id, fileUri),
        ),
      onNoMatch: (failures) =>
        new ServiceFileNotFoundError(serviceId, failures.causes),
    },
  );
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

/**
 * A listed service file, read. `findFiles` lists by name, so the document behind a listed name may
 * still be unreadable; reporting that as the parser's own failure loses both which file it was and
 * that the listing would otherwise show its sibling in its place.
 */
export async function readListedServiceFile(
  fileUri: Uri,
  extensions?: ServiceExtensions,
): Promise<any> {
  try {
    return await fileApi.getMainService(fileUri);
  } catch (error) {
    console.error(`Unable to read the listed service file ${fileUri.path}`, {
      error,
    });
    throw new UnreadableServiceFileError(
      serviceIdFromFileName(fileUri, extensions) ?? fileUri.path,
      fileUri,
    );
  }
}
