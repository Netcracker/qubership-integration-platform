import { Uri } from "vscode";
import { fileApi } from "./fileApiProvider";
import { getExtensionsForFile } from "./fileExtensions";
import {
  plainServiceExtensions,
  ServiceExtensions,
  serviceIdFromFileName,
} from "./serviceFileType";
import { UnreadableFileError } from "../fileFilteringUtils";

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
export class UnreadableServiceFileError extends Error {
  constructor(
    readonly serviceId: string,
    readonly fileUri: Uri,
  ) {
    super(
      `Cannot resolve service ${serviceId}: ${fileUri.path} could not be read.` +
        " Fix or delete that file — until then a save would overwrite it with another file's content.",
    );
    this.name = "UnreadableServiceFileError";
  }
}

function directoryOf(filePath: string): string {
  return filePath.slice(0, filePath.lastIndexOf("/"));
}

/**
 * Whether two names can be the two files of one service. A conversion changes the extension alone,
 * so a pair shares a folder and the id its names state — and that is also the only pair a write
 * can destroy, because it writes the recomputed name into the folder of the file it resolved.
 */
function mayBeSameService(
  candidate: Uri,
  resolved: Uri,
  extensions: ServiceExtensions,
): boolean {
  return (
    directoryOf(candidate.path) === directoryOf(resolved.path) &&
    serviceIdFromFileName(candidate, extensions) ===
      serviceIdFromFileName(resolved, extensions)
  );
}

/**
 * Resolves a plain service file by id across every name it can carry. A typed name wins over
 * the legacy sibling, the same precedence `ApiGroupService.resolveGroupFile` applies to a group
 * stored under two extensions, so a converted service resolves to the file the next write lands on.
 *
 * A miss and a broken scan both come back as one `ServiceFileNotFoundError` naming every failure.
 * `FileApi` reports both as a plain `Error`, so the two cannot be told apart here, and reporting
 * the last failure alone hid the broken file that made every later name fail too.
 *
 * The one failure that is told apart is a file the scan could not read, which `FileApi` does
 * report as its own type: a lookup that would answer with the sibling of such a file refuses
 * instead. Falling through to the sibling is how a legacy body gets written over a typed file.
 */
export async function findServiceFileById(
  serviceId: string,
  extensions?: ServiceExtensions,
): Promise<Uri> {
  const scanned = extensions ?? getExtensionsForFile();
  const causes: unknown[] = [];
  const unreadable: Uri[] = [];
  for (const extension of extensionsToScan(scanned)) {
    let fileUri: Uri;
    try {
      fileUri = await fileApi.findFileById(serviceId, extension);
    } catch (error) {
      if (error instanceof UnreadableFileError) {
        unreadable.push(...error.files);
      }
      causes.push(error);
      continue;
    }
    const sibling = unreadable.find((candidate) =>
      mayBeSameService(candidate, fileUri, scanned),
    );
    if (sibling) {
      throw new UnreadableServiceFileError(serviceId, sibling);
    }
    return fileUri;
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
