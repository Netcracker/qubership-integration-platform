// The two router cases the webview drives beside `importSpecificationGroup`, over the Jest fake of
// `vscode` and on the harness `tests/web/import/soap.test.ts` uses.
//
// `getImportSpecificationResult` is not optional for the product:
// `ui/src/components/services/modals/ImportSpecificationsModal.tsx` polls it after every import, so
// `ImportProgressTracker.getImportSession` and the `Import session … not found` answer are on the
// path a person walks. The golden cases drive the tracker's three write methods implicitly and read
// no session back, which left the poll answering for every import with nothing to catch it.
//
// `importSpecification` adds a specification to a group that already exists, where
// `importSpecificationGroup` creates the group first. It mints no environment: `runImport` creates
// one from its `afterImport` hook, and only `importSpecificationGroup` passes one.
import type { ExtensionContext, Uri } from "vscode";
import * as yaml from "yaml";
import { workspaceTree } from "../../fake-vscode";
import type { FrozenIds } from "../../helpers/freezeSourceIds";
import {
  errorMessagesShown,
  fixtureFile,
  postImportGroup,
  resetImportWorkspace,
  serializedFile,
} from "../../helpers/importHarness";
import { seedServiceDocument } from "../../helpers/seedServiceDocument";
import { getApiResponse } from "../../../src/web/response/apiRouter";
import type { SerializedFile } from "../../../src/web/api-services/importApiTypes";

// Its own service id and frozen prefix, as every other import case has: nothing here reaches the
// catalog, but a shared id would make a failure here read as a failure there.
const SERVICE_ID = "6d4e0000-0000-4000-8000-000000000000";
const ID_PREFIX = "6d4e5e55";
const frozenId = (value: number) =>
  `${ID_PREFIX}-0000-4000-8000-${String(value).padStart(12, "0")}`;

const GROUP_NAME = "orders";
const GROUP_ID = `${SERVICE_ID}-${GROUP_NAME}`;
const SPECIFICATION_ID = `${GROUP_ID}-1.0.0`;
const SERVICE_DIR = `services/${SERVICE_ID}`;
const SOURCE_DIR = `${SERVICE_DIR}/resources/source-${SPECIFICATION_ID}`;

const WSDL = "orders.wsdl";

// A second SOAP document, kept inline because the group the first import created accepts only the
// protocol it was created for.
const CANCEL_WSDL = `<?xml version="1.0" encoding="UTF-8"?>
<wsdl:definitions xmlns:wsdl="http://schemas.xmlsoap.org/wsdl/"
                  xmlns:xsd="http://www.w3.org/2001/XMLSchema"
                  xmlns:tns="http://orders.qip.test/cancel"
                  targetNamespace="http://orders.qip.test/cancel">
  <wsdl:types>
    <xsd:schema targetNamespace="http://orders.qip.test/cancel">
      <xsd:element name="cancelOrderRequest" type="xsd:string"/>
    </xsd:schema>
  </wsdl:types>
  <wsdl:message name="cancelOrderRequest">
    <wsdl:part name="parameters" element="tns:cancelOrderRequest"/>
  </wsdl:message>
  <wsdl:portType name="CancelPort">
    <wsdl:operation name="cancelOrder">
      <wsdl:input message="tns:cancelOrderRequest"/>
    </wsdl:operation>
  </wsdl:portType>
  <wsdl:binding name="CancelBinding" type="tns:CancelPort">
    <wsdl:operation name="cancelOrder"/>
  </wsdl:binding>
</wsdl:definitions>
`;

function importGroup(serviceFileUri: Uri | undefined, files: SerializedFile[]) {
  return postImportGroup(serviceFileUri, {
    systemId: SERVICE_ID,
    name: GROUP_NAME,
    files,
  });
}

// Both describes need the same reset, so its hooks sit at file scope.
let frozen: FrozenIds;

beforeEach(() => {
  frozen = resetImportWorkspace(ID_PREFIX);
});

afterEach(() => {
  frozen.restore();
});

describe("the import session the webview polls", () => {
  test("answers the finished import for the id the import returned", async () => {
    const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });
    const imported = await importGroup(serviceFileUri, [
      fixtureFile(WSDL, "text/xml"),
    ]);

    const polled = await getApiResponse(
      {
        type: "getImportSpecificationResult",
        payload: { importId: frozenId(1) },
      } as any,
      serviceFileUri,
      {} as ExtensionContext,
    );

    // The tracker hands back the session the import completed, which is the result itself.
    expect(polled).toEqual({
      id: frozenId(1),
      specificationGroupId: GROUP_ID,
      done: true,
    });
    expect(polled).toEqual(imported);
  });

  test("answers that an unknown id has expired", async () => {
    const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });

    const polled = await getApiResponse(
      {
        type: "getImportSpecificationResult",
        payload: { importId: "no-such-import" },
      } as any,
      serviceFileUri,
      {} as ExtensionContext,
    );

    expect(polled).toEqual({
      id: "no-such-import",
      specificationGroupId: "",
      done: true,
      warningMessage:
        "Import session no-such-import not found. It may have expired or been cleaned up.",
    });
  });
});

describe("a specification imported into a group that already exists", () => {
  test("replaces the specification document and keeps the first source beside it", async () => {
    const serviceFileUri = seedServiceDocument({ id: SERVICE_ID });
    await importGroup(serviceFileUri, [fixtureFile(WSDL, "text/xml")]);

    const result = await getApiResponse(
      {
        type: "importSpecification",
        payload: {
          specificationGroupId: GROUP_ID,
          systemId: SERVICE_ID,
          files: [serializedFile("cancel.wsdl", CANCEL_WSDL, "text/xml")],
        },
      } as any,
      serviceFileUri,
      {} as ExtensionContext,
    );

    expect(result).toEqual({
      id: frozenId(4),
      specificationGroupId: GROUP_ID,
      done: true,
    });
    expect(errorMessagesShown()).toEqual([]);

    const tree = workspaceTree();
    const specification = yaml.parse(
      tree[`${SERVICE_DIR}/${SPECIFICATION_ID}.specification.cip.yaml`],
    );
    // Both imports derive the same specification id from the system, the group name and the
    // version, so the second document replaces the first rather than joining it.
    expect(
      specification.content.operations.map((operation: any) => operation.name),
    ).toEqual(["cancelOrder"]);
    expect(
      specification.content.specificationSources.map((source: any) => source.name),
    ).toEqual(["cancel.wsdl"]);
    // The first import's source copy stays where it was written: the second import copies its own
    // file into the same directory and removes nothing.
    expect(Object.keys(tree).filter((each) => each.startsWith(`${SOURCE_DIR}/`)).sort()).toEqual([
      `${SOURCE_DIR}/cancel.wsdl`,
      `${SOURCE_DIR}/${WSDL}`,
    ]);
    // Five ids: the first import minted three, its `importId`, one source id and the environment
    // id, and this one mints two. `importSpecification` passes no `afterImport`, so no environment
    // id is minted here.
    expect(frozen.issued).toEqual([
      frozenId(1),
      frozenId(2),
      frozenId(3),
      frozenId(4),
      frozenId(5),
    ]);
  });
});
