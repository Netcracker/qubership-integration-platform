import { Uri } from "vscode";
import { fileApi } from "./fileApiProvider";
import { getExtensionsForFile } from "./fileExtensions";
import {
  allServiceExtensions,
  carriedServiceExtension,
  plainServiceExtensions,
  ServiceExtensions,
} from "./serviceFileType";
import type { CandidateOrder } from "./namePrecedence";
import {
  blockingSibling,
  refuseUnreadableSibling,
  resolveFirstCandidate,
  UnreadableSiblingError,
} from "./lookupOutcome";

function extensionsToScan(extensions?: ServiceExtensions): CandidateOrder {
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
 * Resolves a plain service file by id across every name it can carry. The current `.service.` name
 * wins over a per-type sibling, the same precedence `ApiGroupService.resolveGroupFile` applies to a
 * group stored under two extensions, so a converted service resolves to the file the next write
 * lands on.
 *
 * A miss and a broken scan both come back as one `ServiceFileNotFoundError` naming every failure,
 * because reporting the last failure alone hid the broken file that made every later name fail too.
 * A file the scan could not read is named among those causes: with nothing resolved there is no
 * sibling for a write to land beside, so it is a miss here — see the contract in `lookupOutcome.ts`.
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

/** Every plain service file in the workspace, the current name ahead of the per-type ones. */
export async function findServiceFiles(
  extensions?: ServiceExtensions,
): Promise<Uri[]> {
  const scanned = extensions ?? getExtensionsForFile();
  const order = extensionsToScan(scanned);
  const perExtension = await Promise.all(
    order.map((extension) => fileApi.findFiles(extension)),
  );
  // `findFiles` matches by bare `endsWith`, so under an overlapping config a per-type name lands
  // in the current-name batch too — ahead of the current file. A file counts only in the batch of
  // the extension it carries, the longest match.
  return perExtension
    .map((files, rank) =>
      files.filter(
        (fileUri) => carriedServiceExtension(fileUri, scanned) === order[rank],
      ),
    )
    .flat();
}

/** The services a listing can show, and the files it could not read. */
export type ListedServices = {
  readonly services: readonly { fileUri: Uri; service: any }[];
  readonly unreadable: readonly Uri[];
};

/**
 * Reads the files a listing handed back by name. `findFiles` lists by name, so the document behind
 * a listed name may still be unreadable, and reporting that as the parser's own failure loses both
 * which file it was and that the listing would otherwise show its sibling in its place.
 *
 * A file it cannot read takes itself and every entry it outranks off the list — the
 * `blockingSibling` rule every lookup refuses by — because listing such a sibling puts the
 * superseded document where the current one belongs. A converted service whose *legacy* file is the
 * broken one stays listed: the typed file is the one this list hands out and every write lands on,
 * and hiding it would take a healthy service off the only screen it is reachable from while the
 * warning tells the user to delete the file that is left. Everything else stays too: one broken
 * file is one service's problem, not the workspace's. The files come back so the caller can name
 * them.
 */
export async function readListedServices(
  fileUris: readonly Uri[],
  extensions?: ServiceExtensions,
): Promise<ListedServices> {
  const scanned = extensions ?? getExtensionsForFile();
  const services: { fileUri: Uri; service: any }[] = [];
  const unreadable: Uri[] = [];

  for (const fileUri of fileUris) {
    try {
      services.push({
        fileUri,
        service: await fileApi.getMainService(fileUri),
      });
    } catch (error) {
      console.error(`Unable to read the listed service file ${fileUri.path}`, {
        error,
      });
      unreadable.push(fileUri);
    }
  }

  if (unreadable.length === 0) {
    return { services, unreadable };
  }

  const names = allServiceExtensions(scanned);
  return {
    services: services.filter(({ fileUri }) => {
      const sibling = blockingSibling(fileUri, unreadable, names);
      if (sibling) {
        console.error(
          `Hiding the service in ${fileUri.path}: ${sibling.path} could not be read`,
        );
      }
      return !sibling;
    }),
    unreadable,
  };
}
