// The findFileById counterpart for service files. Its scan order is the typed-wins precedence every
// read depends on, and its failure is what the read sites decide the fallback on — a report naming
// only the last name tried hid the broken file that made every name after it fail.

import { QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

jest.mock("vscode", () => ({ __esModule: true }), { virtual: true });

const findFileById = jest.fn();
const findFiles = jest.fn();

jest.mock("../../../src/web/response/file/fileApiProvider", () => ({
  fileApi: { findFileById, findFiles },
}));

jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: jest.fn(() => ext),
  extractFilename: (fileRef: string | { path: string }) =>
    (typeof fileRef === "string" ? fileRef : fileRef.path).split("/").pop() ??
    "",
}));

import {
  findServiceFileById,
  findServiceFiles,
  ServiceFileNotFoundError,
} from "../../../src/web/response/file/serviceFileLookup";

const SERVICE_ID = "svc-1";

beforeEach(() => {
  jest.clearAllMocks();
});

describe("findServiceFileById", () => {
  it("prefers the typed name over the legacy sibling", async () => {
    findFileById.mockImplementation((_id: string, extension: string) =>
      extension === ext.internalService || extension === ext.service
        ? Promise.resolve({ path: `/root/${SERVICE_ID}${extension}` })
        : Promise.reject(new Error("not found")),
    );

    const fileUri = await findServiceFileById(SERVICE_ID, ext);

    expect(fileUri.path).toBe(`/root/${SERVICE_ID}${ext.internalService}`);
    expect(findFileById).not.toHaveBeenCalledWith(SERVICE_ID, ext.service);
  });

  it("reports every name it tried when none answers", async () => {
    findFileById.mockImplementation((_id: string, extension: string) =>
      Promise.reject(new Error(`no ${extension} file`)),
    );

    const error = await findServiceFileById(SERVICE_ID, ext).catch(
      (thrown) => thrown,
    );

    expect(error).toBeInstanceOf(ServiceFileNotFoundError);
    expect(error.serviceId).toBe(SERVICE_ID);
    expect(error.causes).toHaveLength(4);
    for (const extension of [
      ext.externalService,
      ext.internalService,
      ext.implementedService,
      ext.service,
    ]) {
      expect(error.message).toContain(`no ${extension} file`);
    }
  });

  // A malformed file anywhere in the workspace makes the scan throw rather than come back empty.
  // That reason has to survive to the caller, not be replaced by the last name's plain miss.
  it("keeps a broken scan visible behind a later miss", async () => {
    findFileById.mockImplementation((_id: string, extension: string) =>
      Promise.reject(
        new Error(
          extension === ext.externalService
            ? "Unable to parse file: broken.yaml"
            : "not found",
        ),
      ),
    );

    await expect(findServiceFileById(SERVICE_ID, ext)).rejects.toThrow(
      "Unable to parse file: broken.yaml",
    );
  });
});

describe("findServiceFiles", () => {
  it("lists every plain name, typed ones first", async () => {
    findFiles.mockImplementation((extension: string) =>
      Promise.resolve([{ path: `/root/a${extension}` }]),
    );

    const files = await findServiceFiles(ext);

    expect(files.map((file) => file.path)).toEqual([
      `/root/a${ext.externalService}`,
      `/root/a${ext.internalService}`,
      `/root/a${ext.implementedService}`,
      `/root/a${ext.service}`,
    ]);
  });
});
