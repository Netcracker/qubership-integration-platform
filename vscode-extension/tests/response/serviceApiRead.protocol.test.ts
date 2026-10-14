import {
  buildServiceRecord,
  createVscodeMock,
  stubFileApi,
  stubLabelUtils,
} from "../helpers/mocks";

jest.mock("vscode", () => createVscodeMock(), { virtual: true });
jest.mock("../../src/web/response/file/fileApiProvider", () =>
  stubFileApi({ getMainService: jest.fn(), findFileById: jest.fn() }),
);
jest.mock("../../src/web/response/file/fileExtensions", () => ({
  getExtensionsForFile: jest
    .fn()
    .mockReturnValue({ service: ".qip-service.yaml" }),
  getExtensionsForUri: jest
    .fn()
    .mockReturnValue({ service: ".qip-service.yaml" }),
}));
jest.mock("../../src/web/api-services/LabelUtils", () => stubLabelUtils());
jest.mock("../../src/web/api-services/parsers/ContentParser", () => ({
  ContentParser: { parseContentFromFile: jest.fn() },
}));
jest.mock("@netcracker/qip-ui", () => ({}), { virtual: true });

import { getService } from "../../src/web/response/serviceApiRead";
import { fileApi } from "../../src/web/response/file/fileApiProvider";

const serviceFileUri = {} as any;

function storing(protocol?: string) {
  (fileApi.getMainService as jest.Mock).mockResolvedValue(
    buildServiceRecord("svc-1", {
      integrationSystemType: "EXTERNAL",
      protocol,
    }),
  );
}

describe("getService – the protocol reported to the UI", () => {
  beforeEach(() => jest.clearAllMocks());

  // Measured against the running catalog: a service with a WSDL imported into it answers
  // `GET /v1/systems/{id}` with protocol `http`, because OperationProtocol.SOAP is ("http", "soap").
  test("reports a SOAP service as http", async () => {
    storing("SOAP");

    const system = await getService(serviceFileUri, "svc-1");

    expect(system.protocol).toBe("http");
  });

  test.each([["HTTP"], ["KAFKA"], ["AMQP"], ["GRAPHQL"], ["GRPC"]])(
    "reports %s lowercased, as before",
    async (stored) => {
      storing(stored);

      const system = await getService(serviceFileUri, "svc-1");

      expect(system.protocol).toBe(stored.toLowerCase());
    },
  );

  test.each([
    ["an empty protocol", ""],
    ["no protocol", undefined],
  ])("reports nothing for a service with %s", async (_case, stored) => {
    storing(stored);

    const system = await getService(serviceFileUri, "svc-1");

    expect(system.protocol).toBe("");
  });
});
