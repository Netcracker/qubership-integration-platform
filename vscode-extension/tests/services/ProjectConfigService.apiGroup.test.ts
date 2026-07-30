// A `.config.qip.yaml` written before the api-group rename carries no `apiGroup` key. The reader layers the loaded
// values over the defaults, so such a workspace still gets both group extensions and both schema URLs.

import { createVscodeMock } from "../helpers/mocks";

jest.mock("vscode", () => createVscodeMock(), { virtual: true });

import { ProjectConfigService } from "../../src/web/services/ProjectConfigService";

const service = ProjectConfigService.getInstance();

afterEach(() => {
  service.unregisterExternalConfig("legacy");
});

it("fills in the apiGroup extension and schema URL a pre-rename config omits", () => {
  service.registerExternalConfig("legacy", {
    extensions: {
      chain: ".chain.legacy.yaml",
      service: ".service.legacy.yaml",
      specificationGroup: ".specification-group.legacy.yaml",
      specification: ".specification.legacy.yaml",
    },
    schemaUrls: {
      specificationGroup:
        "http://qubership.org/schemas/product/qip/specification-group.schema.yaml",
    },
  });

  const config = service.getConfigByAppName("legacy");

  expect(config?.extensions.specificationGroup).toBe(
    ".specification-group.legacy.yaml",
  );
  expect(config?.extensions.apiGroup).toBe(".api-group.legacy.yaml");
  expect(config?.schemaUrls.apiGroup).toBe(
    "http://qubership.org/schemas/product/qip/api-group.schema.yaml",
  );
});
