// The contract itself: three outcomes, and the one rule allowed to turn the third into a match.

import { QIP_FILE_EXTENSIONS as ext } from "../../helpers/mocks";

jest.mock("vscode", () => ({ __esModule: true }), { virtual: true });

jest.mock("../../../src/web/response/file/fileExtensions", () => ({
  extractFilename: (fileRef: string | { path: string }) =>
    (typeof fileRef === "string" ? fileRef : fileRef.path).split("/").pop() ??
    "",
}));

import { UnreadableFileError } from "../../../src/web/response/fileFilteringUtils";
import {
  mayBeSameEntity,
  noMatchError,
  refuseUnreadableSibling,
  resolveFirstCandidate,
  UnreadableSiblingError,
} from "../../../src/web/response/file/lookupOutcome";

const uri = (path: string): any => ({ path });

const SERVICE_ID = "svc-1";
const typed = uri(`/root/${SERVICE_ID}/${SERVICE_ID}${ext.externalService}`);
const legacy = uri(`/root/${SERVICE_ID}/${SERVICE_ID}${ext.service}`);
const elsewhere = uri(`/root/other/${SERVICE_ID}${ext.service}`);
const names = [ext.externalService, ext.service];

const never = () => {
  throw new Error("onUnreadable must not run");
};

describe("resolveFirstCandidate", () => {
  it("answers the first match and stops there", async () => {
    const attempt = jest.fn(async (candidate: string) =>
      candidate === ext.externalService ? typed : legacy,
    );

    const resolved = await resolveFirstCandidate(names, attempt, {
      onUnreadable: never,
      onNoMatch: () => new Error("unreachable"),
    });

    expect(resolved).toBe(typed);
    expect(attempt).toHaveBeenCalledTimes(1);
  });

  it("hands the outstanding files to onUnreadable before answering", async () => {
    const onUnreadable = jest.fn();

    const resolved = await resolveFirstCandidate(
      names,
      async (candidate: string) => {
        if (candidate === ext.externalService) {
          throw new UnreadableFileError(candidate, [typed]);
        }
        return legacy;
      },
      { onUnreadable, onNoMatch: () => new Error("unreachable") },
    );

    expect(resolved).toBe(legacy);
    expect(onUnreadable).toHaveBeenCalledWith([typed], legacy);
  });

  it("lets onUnreadable refuse the answer", async () => {
    await expect(
      resolveFirstCandidate(
        names,
        async (candidate: string) => {
          if (candidate === ext.externalService) {
            throw new UnreadableFileError(candidate, [typed]);
          }
          return legacy;
        },
        {
          onUnreadable: () => {
            throw new Error("refused");
          },
          onNoMatch: () => new Error("unreachable"),
        },
      ),
    ).rejects.toThrow("refused");
  });

  it("reports every candidate's failure, and which files it could not read", async () => {
    const onNoMatch = jest.fn((_failures: any) => new Error("nothing matched"));

    await expect(
      resolveFirstCandidate(
        names,
        async (candidate: string) => {
          throw candidate === ext.externalService
            ? new UnreadableFileError(candidate, [typed])
            : new Error("no legacy file");
        },
        { onUnreadable: never, onNoMatch },
      ),
    ).rejects.toThrow("nothing matched");

    const failures = onNoMatch.mock.calls[0][0] as any;
    expect(failures.causes).toHaveLength(2);
    expect(failures.unreadable).toEqual([typed]);
  });
});

describe("noMatchError", () => {
  it("reports the file it could not read over a plain miss", () => {
    const unreadable = new UnreadableFileError(ext.externalService, [typed]);

    const error = noMatchError(
      { causes: [unreadable, new Error("not found")], unreadable: [typed] },
      () => new Error("not found"),
    );

    expect(error).toBe(unreadable);
  });

  it("reports the miss when every candidate was readable", () => {
    const absent = new Error("not found");

    expect(noMatchError({ causes: [], unreadable: [] }, () => absent)).toBe(
      absent,
    );
  });
});

describe("mayBeSameEntity", () => {
  it("pairs two names of one entity in one folder", () => {
    expect(mayBeSameEntity(typed, legacy, names)).toBe(true);
  });

  it("does not pair across folders", () => {
    expect(mayBeSameEntity(typed, elsewhere, names)).toBe(false);
  });

  it("does not pair two entities in one folder", () => {
    expect(
      mayBeSameEntity(
        typed,
        uri(`/root/${SERVICE_ID}/other${ext.service}`),
        names,
      ),
    ).toBe(false);
  });

  it("pairs nothing with a name carrying none of the extensions", () => {
    expect(
      mayBeSameEntity(uri(`/root/${SERVICE_ID}/notes.md`), legacy, names),
    ).toBe(false);
  });
});

describe("refuseUnreadableSibling", () => {
  it("refuses the answer that may be the unreadable file's sibling", () => {
    expect(() =>
      refuseUnreadableSibling(SERVICE_ID, legacy, [typed], names),
    ).toThrow(UnreadableSiblingError);
  });

  it("names the file to fix", () => {
    expect(() =>
      refuseUnreadableSibling(SERVICE_ID, legacy, [typed], names),
    ).toThrow(typed.path);
  });

  it("lets an unreadable file elsewhere through", () => {
    expect(() =>
      refuseUnreadableSibling(SERVICE_ID, elsewhere, [typed], names),
    ).not.toThrow();
  });
});
