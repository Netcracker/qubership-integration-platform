// A `.config.qip.yaml` written before the plain service types existed carries none of their keys.
// The reader layers the loaded values over the defaults, so such a workspace still gets an
// extension and a schema URL per type instead of `undefined`.

import { createVscodeMock } from "../helpers/mocks";

jest.mock("vscode", () => createVscodeMock(), { virtual: true });

import { ProjectConfigService } from "../../src/web/services/ProjectConfigService";

const service = ProjectConfigService.getInstance();

afterEach(() => {
  service.unregisterExternalConfig("legacy");
});

it("fills in the plain service extensions and schema URLs a pre-#553 config omits", () => {
  service.registerExternalConfig("legacy", {
    extensions: {
      chain: ".chain.legacy.yaml",
      service: ".service.legacy.yaml",
      contextService: ".context-service.legacy.yaml",
      mcpService: ".mcp-service.legacy.yaml",
    },
    schemaUrls: {
      service: "http://qubership.org/schemas/product/qip/service.schema.yaml",
    },
  });

  const config = service.getConfigByAppName("legacy");

  expect(config?.extensions.service).toBe(".service.legacy.yaml");
  expect(config?.extensions.externalService).toBe(
    ".external-service.legacy.yaml",
  );
  expect(config?.extensions.internalService).toBe(
    ".internal-service.legacy.yaml",
  );
  expect(config?.extensions.implementedService).toBe(
    ".implemented-service.legacy.yaml",
  );
  expect(config?.schemaUrls.externalService).toBe(
    "http://qubership.org/schemas/product/qip/external-service.schema.yaml",
  );
  expect(config?.schemaUrls.internalService).toBe(
    "http://qubership.org/schemas/product/qip/internal-service.schema.yaml",
  );
  expect(config?.schemaUrls.implementedService).toBe(
    "http://qubership.org/schemas/product/qip/implemented-service.schema.yaml",
  );
});

it("keeps a project override of a plain service extension", () => {
  service.registerExternalConfig("legacy", {
    extensions: {
      externalService: ".ext-svc.legacy.yaml",
    },
  });

  const config = service.getConfigByAppName("legacy");

  expect(config?.extensions.externalService).toBe(".ext-svc.legacy.yaml");
  expect(config?.extensions.internalService).toBe(
    ".internal-service.legacy.yaml",
  );
});
