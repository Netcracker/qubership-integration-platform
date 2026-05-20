import { createMinimalVscodeMock } from "../helpers/mocks";

jest.mock("vscode", () => createMinimalVscodeMock(), { virtual: true });
jest.mock("../../src/web/response/file/fileApiProvider", () => ({
  fileApi: {},
}));
jest.mock("../../src/web/response/serviceApiRead", () => ({
  getCurrentServiceId: jest.fn(),
}));
jest.mock("../../src/web/response/serviceApiModify", () => ({
  createService: jest.fn(),
}));
jest.mock("../../src/web/api-services/SpecificationImportApiHandler", () => ({
  SpecificationImportApiHandler: jest.fn(),
}));

import { ApiSpecificationType } from "../../src/web/api-services/importApiTypes";
import { IntegrationSystemType } from "../../src/web/api-services/servicesTypes";
import {
  ALLOWED_PROTOCOL_MAP,
  validateAllowedSystemProtocol,
} from "../../src/web/response/serviceApiUtils";

describe("ALLOWED_PROTOCOL_MAP", () => {
  const allProtocols = Object.values(ApiSpecificationType);

  test.each([
    ["EXTERNAL", IntegrationSystemType.EXTERNAL],
    ["INTERNAL", IntegrationSystemType.INTERNAL],
  ])("%s allows all protocol types", (_label, systemType) => {
    const allowed = ALLOWED_PROTOCOL_MAP.get(systemType);
    expect(allowed).toBeDefined();
    for (const protocol of allProtocols) {
      expect(allowed!.has(protocol)).toBe(true);
    }
  });

  test("IMPLEMENTED allows only HTTP, SOAP, GRAPHQL", () => {
    const allowed = ALLOWED_PROTOCOL_MAP.get(
      IntegrationSystemType.IMPLEMENTED,
    )!;
    expect(allowed).toBeDefined();

    const expectedAllowed = [
      ApiSpecificationType.HTTP,
      ApiSpecificationType.SOAP,
      ApiSpecificationType.GRAPHQL,
    ];
    const expectedBlocked = [
      ApiSpecificationType.GRPC,
      ApiSpecificationType.KAFKA,
      ApiSpecificationType.AMQP,
    ];

    expectedAllowed.forEach((p) => expect(allowed.has(p)).toBe(true));
    expectedBlocked.forEach((p) => expect(allowed.has(p)).toBe(false));
  });

  test("does not contain CONTEXT system type", () => {
    expect(ALLOWED_PROTOCOL_MAP.has(IntegrationSystemType.CONTEXT)).toBe(false);
  });
});

describe("validateAllowedSystemProtocol", () => {
  describe("returns silently when systemType or protocol is missing", () => {
    test.each([
      ["systemType is undefined", undefined, ApiSpecificationType.HTTP],
      ["protocol is undefined", IntegrationSystemType.EXTERNAL, undefined],
      ["both are undefined", undefined, undefined],
    ])("%s", (_label, systemType, protocol) => {
      expect(() =>
        validateAllowedSystemProtocol(systemType, protocol),
      ).not.toThrow();
    });
  });

  describe("does not throw for allowed combinations", () => {
    test.each([
      [
        "EXTERNAL + HTTP",
        IntegrationSystemType.EXTERNAL,
        ApiSpecificationType.HTTP,
      ],
      [
        "INTERNAL + KAFKA",
        IntegrationSystemType.INTERNAL,
        ApiSpecificationType.KAFKA,
      ],
      [
        "IMPLEMENTED + SOAP",
        IntegrationSystemType.IMPLEMENTED,
        ApiSpecificationType.SOAP,
      ],
      [
        "CONTEXT (not in map) + HTTP",
        IntegrationSystemType.CONTEXT,
        ApiSpecificationType.HTTP,
      ],
    ])("%s", (_label, systemType, protocol) => {
      expect(() =>
        validateAllowedSystemProtocol(systemType, protocol),
      ).not.toThrow();
    });
  });

  describe("throws for disallowed combinations", () => {
    test.each([
      [ApiSpecificationType.GRPC],
      [ApiSpecificationType.KAFKA],
      [ApiSpecificationType.AMQP],
    ])("IMPLEMENTED + %s", (protocol) => {
      expect(() =>
        validateAllowedSystemProtocol(
          IntegrationSystemType.IMPLEMENTED,
          protocol,
        ),
      ).toThrow(
        `Specification type is not allowed for implemented system: ${protocol}`,
      );
    });
  });
});
