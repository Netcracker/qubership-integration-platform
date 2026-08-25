// Key order has to match runtime-catalog's export, otherwise a file written
// here and the same service exported from the backend produce a diff made
// entirely of moved lines. Order follows the export DTO field declarations,
// which is what Jackson emits.

import { shapeServiceFile } from "../../../src/web/response/file/serviceFileShape";
import { ServiceNormalizer } from "../../../src/web/api-services/ServiceNormalizer";

describe("shapeServiceFile", () => {
  it("writes service content in the IntegrationSystemContentDto order", () => {
    const shaped = shapeServiceFile(
      {
        $schema: "http://qubership.org/schemas/product/qip/service.schema.yaml",
        id: "s1",
        name: "test2",
        content: {
          integrationSystemType: "EXTERNAL",
          migrations: "[100, 101]",
          protocol: "HTTP",
          activeEnvironmentId: "env-1",
          environments: [{ id: "env-1", name: "Production" }],
        },
      },
      "service",
    );

    expect(Object.keys(shaped)).toEqual(["id", "$schema", "name", "content"]);
    expect(Object.keys(shaped.content)).toEqual([
      "activeEnvironmentId",
      "integrationSystemType",
      "protocol",
      "environments",
      "migrations",
    ]);
  });

  it("orders environment entries like the Environment entity", () => {
    const shaped = shapeServiceFile(
      {
        id: "s1",
        content: {
          environments: [
            {
              properties: { soTimeout: "120000" },
              sourceType: "MANUAL",
              systemId: "s1",
              address: "https://example.test",
              description: "Created for the group",
              name: "Production",
              id: "env-1",
            },
          ],
        },
      },
      "service",
    );

    expect(Object.keys(shaped.content.environments[0])).toEqual([
      "id",
      "name",
      "description",
      "address",
      "sourceType",
      "properties",
      // Not a field the backend exports, so it lands after the known ones.
      "systemId",
    ]);
  });

  it("uses the MCP order, where migrations precedes labels", () => {
    const shaped = shapeServiceFile(
      {
        id: "m1",
        content: {
          labels: ["a"],
          migrations: "[100]",
          instructions: "do the thing",
          identifier: "mcp-1",
        },
      },
      "mcpService",
    );

    expect(Object.keys(shaped.content)).toEqual([
      "identifier",
      "instructions",
      "migrations",
      "labels",
    ]);
  });

  // Both are derived from the protocol in the API response. A stored copy goes
  // stale the moment the protocol changes, so it must not survive a save.
  it("drops the derived keys older files stored", () => {
    const shaped = shapeServiceFile(
      {
        id: "s1",
        content: {
          protocol: "KAFKA",
          extendedProtocol: "http",
          specification: "swagger",
          migrations: "[100]",
        },
      },
      "service",
    );

    expect(Object.keys(shaped.content)).toEqual(["protocol", "migrations"]);
  });

  it("still prunes empty values before ordering", () => {
    const shaped = shapeServiceFile(
      {
        id: "s1",
        content: {
          description: "",
          labels: [],
          protocol: "HTTP",
          migrations: "[100]",
        },
      },
      "service",
    );

    expect(Object.keys(shaped.content)).toEqual(["protocol", "migrations"]);
  });
});

// Reading supplies only what the file cannot go without, saving prunes what is
// empty. Together they have to leave a file with its own values, no new blanks,
// and nothing the schemas require missing.
describe("read and save round trip", () => {
  it("keeps the real values and adds no blanks", () => {
    const onDisk = {
      $schema: "http://qubership.org/schemas/product/qip/service.schema.yaml",
      id: "s1",
      name: "test",
      content: {
        integrationSystemType: "EXTERNAL",
        protocol: "HTTP",
        migrations: "[100, 101]",
        environments: [
          { id: "env-1", name: "Production", address: "https://example.test" },
        ],
      },
    };

    const shaped = shapeServiceFile(
      ServiceNormalizer.normalizeService(onDisk),
      "service",
    );

    expect(shaped.content).toEqual({
      integrationSystemType: "EXTERNAL",
      protocol: "HTTP",
      migrations: "[100, 101]",
      environments: [
        {
          id: "env-1",
          name: "Production",
          address: "https://example.test",
          // Required by the schema and absent from the file, so reading adds it.
          sourceType: "MANUAL",
        },
      ],
    });
  });

  it("drops the blanks an older extension version wrote", () => {
    const onDisk = {
      id: "s1",
      name: "test",
      content: {
        description: "",
        activeEnvironmentId: "",
        integrationSystemType: "EXTERNAL",
        protocol: "",
        environments: [],
        labels: [],
        migrations: "[100, 101]",
      },
    };

    const shaped = shapeServiceFile(
      ServiceNormalizer.normalizeService(onDisk),
      "service",
    );

    expect(shaped.content).toEqual({
      integrationSystemType: "EXTERNAL",
      migrations: "[100, 101]",
    });
  });
});
