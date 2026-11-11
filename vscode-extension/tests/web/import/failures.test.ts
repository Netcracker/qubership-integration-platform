// What the user is told when a specification import is refused, driven end to end through the
// router over the Jest fake of `vscode`, as `tests/web/import/soap.test.ts` drives a successful
// SOAP import.
//
// The split this file pins: `runImport` catches everything and resolves with
// `{ id, specificationGroupId, done: true, warningMessage }`, so a refusal reaches the caller as an
// exception only where it is raised ahead of `runImport`. Three messages reject, and each has a
// case here: `Invalid import request: …` and `System with id … not found` in
// `importSpecificationGroup`, and `Specification group with id … not found` in
// `importSpecification`. A case that expected a rejection for any other refusal would assert a
// behavior the code does not have.
// `window.showErrorMessage` is the other half of the answer, and for a resolving refusal it is the
// only thing the user sees, so each case asserts the text it received.
//
// Each resolving case also asserts which side of `ensureSystemProtocol` its refusal falls on,
// because that call rewrites the service file before the import can fail any later. Four of them
// are raised before it and leave the seeded tree untouched; `Failed to save specification files`
// is raised inside `saveSpecificationFiles` and therefore after it, so that case asserts the
// rewrite rather than an untouched disk.
//
// No case is written for the four branches with no product path into them, two of which carry a
// refusal: `resolveSpecificationSourceFile` answers `undefined` only for an empty file list, which
// `validateImportRequest` has already refused, so the warn-and-`continue` above it is dead too;
// `Failed to convert file …` guards a `File` constructor that throws only for a detached
// `ArrayBuffer`, which `postMessage` cannot deliver; `No base folder available` sits behind a
// `getBaseFolder` typed `Promise<Uri>` that throws rather than answering `undefined`; and
// `ensureInternalEnvironment`'s update arm needs a seeded environment with an empty address.
import type { ExtensionContext, Uri } from "vscode";
import * as yaml from "yaml";
import { seedWorkspace, workspaceTree, workspaceUri } from "../../fake-vscode";
import type { FrozenIds } from "../../helpers/freezeSourceIds";
import {
  errorMessagesShown,
  fixtureFile,
  postImportGroup,
  resetImportWorkspace,
  serializedFile,
} from "../../helpers/importHarness";
import { seedServiceDocument, serviceFilePath } from "../../helpers/seedServiceDocument";
import { getApiResponse } from "../../../src/web/response/apiRouter";
import { ApiSpecificationType } from "../../../src/web/api-services/importApiTypes";
import type { SerializedFile } from "../../../src/web/api-services/importApiTypes";
import { IntegrationSystemType } from "../../../src/web/api-services/servicesTypes";

// Its own service id and frozen prefix, as each golden case has: nothing here reaches the catalog,
// but a shared id would make a failure here read as a failure there.
const SERVICE_ID = "7e110000-0000-4000-8000-000000000000";
const ID_PREFIX = "7e11fa11";
const IMPORT_ID = `${ID_PREFIX}-0000-4000-8000-000000000001`;

const GROUP_NAME = "orders";
const GROUP_ID = `${SERVICE_ID}-${GROUP_NAME}`;
const SPECIFICATION_ID = `${GROUP_ID}-1.0.0`;
const SERVICE_DIR = `services/${SERVICE_ID}`;
const SERVICE_FILE = serviceFilePath(SERVICE_ID);

function importGroup(
  serviceFileUri: Uri | undefined,
  files: SerializedFile[],
  systemId = SERVICE_ID,
) {
  return postImportGroup(serviceFileUri, { systemId, name: GROUP_NAME, files });
}

describe("a refused specification import", () => {
  let frozen: FrozenIds;
  // Every case here triggers a logged error on purpose, each with a stack trace.
  let consoleError: jest.SpyInstance;

  beforeEach(() => {
    frozen = resetImportWorkspace(ID_PREFIX);
    consoleError = jest.spyOn(console, "error").mockImplementation(() => {});
  });

  afterEach(() => {
    frozen.restore();
    consoleError.mockRestore();
  });

  describe("rejects before the import starts", () => {
    test("when the request carries no files", async () => {
      const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });
      const seeded = workspaceTree();

      await expect(importGroup(serviceFileUri, [])).rejects.toThrow(
        "Invalid import request: At least one file is required",
      );

      // `validateImportRequest` runs ahead of the `importId`, so not even a uuid is minted.
      expect(frozen.issued).toEqual([]);
      expect(workspaceTree()).toEqual(seeded);
      expect(errorMessagesShown()).toEqual([]);
    });

    test("when no workspace file carries the system id", async () => {
      const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });
      const seeded = workspaceTree();

      await expect(
        importGroup(serviceFileUri, [fixtureFile("orders.wsdl")], "no-such-system"),
      ).rejects.toThrow("System with id no-such-system not found");

      expect(frozen.issued).toEqual([]);
      expect(workspaceTree()).toEqual(seeded);
      expect(errorMessagesShown()).toEqual([]);
    });

    test("when the group a specification is imported into does not exist", async () => {
      // The third rejecting message, raised in `importSpecification` rather than in
      // `importSpecificationGroup`: `getSpecificationGroupById` resolves the id through
      // `fileApi.findFileById`, finds no document, logs and returns `null`. Nothing here creates
      // the group file, so this is the refusal a second import into a deleted group reaches.
      const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });
      const seeded = workspaceTree();

      await expect(
        getApiResponse(
          {
            type: "importSpecification",
            payload: {
              specificationGroupId: GROUP_ID,
              systemId: SERVICE_ID,
              files: [fixtureFile("orders.wsdl")],
            },
          } as any,
          serviceFileUri,
          {} as ExtensionContext,
        ),
      ).rejects.toThrow(`Specification group with id ${GROUP_ID} not found`);

      // The lookup runs ahead of `runImport`, so no uuid is minted and the user is shown nothing.
      expect(frozen.issued).toEqual([]);
      expect(workspaceTree()).toEqual(seeded);
      expect(errorMessagesShown()).toEqual([]);
    });
  });

  describe("resolves before the service file is rewritten", () => {
    test("when no protocol can be detected from the posted file", async () => {
      const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });
      const seeded = workspaceTree();

      const result = await importGroup(serviceFileUri, [
        serializedFile("notes.txt", "just a note\n"),
      ]);

      expect(result).toEqual({
        id: IMPORT_ID,
        // Empty, not the group id: `runImport` raised this before the group was created.
        specificationGroupId: "",
        done: true,
        warningMessage: "Unsupported specification format: unable to detect protocol",
      });
      expect(workspaceTree()).toEqual(seeded);
      // Twice: `runImport` shows the message itself and then throws, and its `catch` shows the
      // message it built from that exception.
      expect(errorMessagesShown()).toEqual([
        "Unsupported specification format: unable to detect protocol",
        "Unsupported specification format: unable to detect protocol",
      ]);
    });

    test("when a .zip is posted, which no picker in this context offers", async () => {
      // The extension unpacks no archive: `ProtocolDetectorService.extractArchives` passes every
      // file through, where the backend's `MultipartFileUtils.extractArchives` reads a zip's
      // entries. So the import refuses one, and the picker's `SUPPORTED_EXTENSIONS` in
      // `ui/src/components/services/modals/ImportSpecificationsModal.tsx` drops `".zip"` where the
      // api is the extension's, which is what keeps the product from inviting this file. The case
      // stays because the webview is not the only sender: `getApiResponse` takes the message from
      // anything that can post one, including an extension that loads this package as a library.
      const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });
      const seeded = workspaceTree();

      const result = await importGroup(serviceFileUri, [
        serializedFile("orders.zip", "PK\u0003\u0004 not really an archive"),
      ]);

      expect(result).toEqual({
        id: IMPORT_ID,
        specificationGroupId: "",
        done: true,
        warningMessage: "Unsupported specification format: unable to detect protocol",
      });
      expect(workspaceTree()).toEqual(seeded);
      // Twice, as for any undetected protocol: `runImport` shows the message and then throws, and
      // its `catch` shows the message it built from that exception.
      expect(errorMessagesShown()).toEqual([
        "Unsupported specification format: unable to detect protocol",
        "Unsupported specification format: unable to detect protocol",
      ]);
    });

    test("when the service protocol differs from the imported one", async () => {
      const serviceFileUri = seedServiceDocument({
        id: SERVICE_ID,
        protocol: ApiSpecificationType.HTTP,
      });
      const seeded = workspaceTree();

      const result = await importGroup(serviceFileUri, [fixtureFile("orders.wsdl")]);

      const message =
        "Protocol mismatch: Cannot import SOAP specification into HTTP service. " +
        "The specification protocol (SOAP) must match the service protocol (HTTP).";
      expect(result).toEqual({
        id: IMPORT_ID,
        specificationGroupId: "",
        done: true,
        warningMessage: message,
      });
      expect(workspaceTree()).toEqual(seeded);
      expect(errorMessagesShown()).toEqual([message]);
    });

    test("when an IMPLEMENTED service does not allow the imported protocol", async () => {
      // `ALLOWED_PROTOCOL_MAP` restricts `IMPLEMENTED` to `HTTP`, `SOAP` and `GRAPHQL`, and leaves
      // the other three system types unrestricted. `EXTERNAL` and `INTERNAL` carry every protocol,
      // and `CONTEXT` is absent from the map, which `validateAllowedSystemProtocol` reads as
      // unrestricted through its `allowedProtocols &&` guard rather than through an entry of its
      // own. So gRPC on an `IMPLEMENTED` service is the cheapest way into this refusal.
      const serviceFileUri = seedServiceDocument({
        id: SERVICE_ID,
        integrationSystemType: IntegrationSystemType.IMPLEMENTED,
      });
      const seeded = workspaceTree();

      const result = await importGroup(serviceFileUri, [fixtureFile("orders.proto")]);

      const message = "Specification type is not allowed for implemented system: GRPC";
      expect(result).toEqual({
        id: IMPORT_ID,
        specificationGroupId: "",
        done: true,
        warningMessage: message,
      });
      expect(workspaceTree()).toEqual(seeded);
      expect(errorMessagesShown()).toEqual([message]);
    });
  });

  describe("resolves after the service file has been rewritten", () => {
    test("when the selected file is not a service file", async () => {
      // `getBaseFolder` throws `Service file must be selected` unless `getFileType` answers
      // `SERVICE`, and `saveSpecificationFiles` wraps that text in its own message. By then
      // `ensureSystemProtocol` has written the service file once, so the disk is not untouched.
      seedServiceDocument({ id: SERVICE_ID });
      seedWorkspace({ "notes.txt": "not a service document\n" });
      const seededService = workspaceTree()[SERVICE_FILE];

      const result = await importGroup(workspaceUri("notes.txt"), [fixtureFile("orders.wsdl")]);

      const message = "Failed to save specification files: Service file must be selected";
      expect(result).toEqual({
        id: IMPORT_ID,
        // The group id, not an empty string: the group was created before the write failed.
        specificationGroupId: GROUP_ID,
        done: true,
        warningMessage: message,
      });
      expect(errorMessagesShown()).toEqual([message]);

      const tree = workspaceTree();
      expect(Object.keys(tree).sort()).toEqual(
        ["notes.txt", "services", SERVICE_DIR, SERVICE_FILE].sort(),
      );
      expect(tree[SERVICE_FILE]).not.toBe(seededService);
      const service = yaml.parse(tree[SERVICE_FILE]);
      expect(service.content.protocol).toBe("SOAP");
      expect(service.content.extendedProtocol).toBe("SOAP");
      // Only the `importId`: the source ids are minted inside `saveSpecificationFiles`, after the
      // call that threw.
      expect(frozen.issued).toEqual([IMPORT_ID]);
    });
  });

  describe("a malformed specification", () => {
    test("a WSDL whose root is in no WSDL namespace reaches the user as a refusal", async () => {
      // The file is detected as SOAP by its extension alone, so it reaches `WsdlParser`, which
      // refuses a root element in neither WSDL namespace. Until that guard the document parsed as
      // a WSDL with nothing in it, and the import wrote the whole tree — a specification with an
      // empty operation list, its group file and the source copy — and reported neither an error
      // nor a warning. The route out is the one the proto case below takes: `runImport` turns the
      // parser's text into the `warningMessage` and the dialog, and the only write that survives is
      // the service file `ensureSystemProtocol` had already rewritten to `SOAP`.
      const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });

      const result = await importGroup(serviceFileUri, [
        serializedFile("broken.wsdl", "<notDefinitions><oops/></notDefinitions>\n"),
      ]);

      const message =
        "Unsupported WSDL: root element <notDefinitions> is in no WSDL namespace";
      expect(result).toEqual({
        id: IMPORT_ID,
        specificationGroupId: GROUP_ID,
        done: true,
        warningMessage: message,
      });
      expect(errorMessagesShown()).toEqual([message]);

      const tree = workspaceTree();
      expect(Object.keys(tree).sort()).toEqual(
        ["services", SERVICE_DIR, SERVICE_FILE].sort(),
      );
      expect(yaml.parse(tree[SERVICE_FILE]).content.protocol).toBe("SOAP");
    });

    test("a proto with a syntax error reaches the user as the parser's own message", async () => {
      // `createOperationsFromFile` rethrows, `processSpecificationFiles` does not catch, and
      // `runImport` turns the parser's text into the `warningMessage`. The group id is already on
      // the result, the service file has been rewritten to `GRPC`, and nothing else was written.
      const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });

      const result = await importGroup(serviceFileUri, [
        serializedFile("broken.proto", 'syntax = "proto3"\nservice {{{ Oops\n'),
      ]);

      const message = "illegal token 'service', ';' expected (line 2)";
      expect(result).toEqual({
        id: IMPORT_ID,
        specificationGroupId: GROUP_ID,
        done: true,
        warningMessage: message,
      });
      expect(errorMessagesShown()).toEqual([message]);

      const tree = workspaceTree();
      expect(Object.keys(tree).sort()).toEqual(["services", SERVICE_DIR, SERVICE_FILE].sort());
      expect(yaml.parse(tree[SERVICE_FILE]).content.protocol).toBe("GRPC");
    });
  });
});
