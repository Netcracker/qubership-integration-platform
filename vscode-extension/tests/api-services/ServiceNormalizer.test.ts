// The backend refuses a service file whose `content.migrations` is missing, and
// re-runs every migration when the field is an empty list — V101 then wraps
// `content` a second time and the import fails. Older versions of this
// extension wrote `migrations: []`, so reading such a file must repair it.

import { ServiceNormalizer } from "../../src/web/api-services/ServiceNormalizer";
import { SERVICE_MIGRATIONS } from "../../src/web/services/importMigrationVersions";

describe("ServiceNormalizer migrations", () => {
  it("replaces the empty array older versions wrote", () => {
    const service = ServiceNormalizer.normalizeService({
      id: "s1",
      content: { migrations: [] },
    });

    expect(service.content.migrations).toBe(SERVICE_MIGRATIONS);
  });

  it("fills in a missing claim", () => {
    const service = ServiceNormalizer.normalizeService({
      id: "s1",
      content: {},
    });

    expect(service.content.migrations).toBe(SERVICE_MIGRATIONS);
  });

  it("leaves an older claim alone so the backend still migrates the file", () => {
    const service = ServiceNormalizer.normalizeService({
      id: "s1",
      content: { migrations: "[100, 101]" },
    });

    expect(service.content.migrations).toBe("[100, 101]");
  });

  it("claims the current set for a file with no content at all", () => {
    const service = ServiceNormalizer.normalizeService({ id: "s1" });

    expect(service.content.migrations).toBe(SERVICE_MIGRATIONS);
  });
});

// Both fields are derived from the protocol in the service API response —
// SystemMapper maps OperationProtocol.type onto `specification` and the protocol
// itself onto `extendedProtocol` — so neither belongs in the file.
describe("ServiceNormalizer derived fields", () => {
  it.each(["specification", "extendedProtocol"])("does not add %s", (field) => {
    const withContent = ServiceNormalizer.normalizeService({
      id: "s1",
      content: {},
    });
    const withoutContent = ServiceNormalizer.normalizeService({ id: "s2" });

    expect(withContent.content).not.toHaveProperty(field);
    expect(withoutContent.content).not.toHaveProperty(field);
  });
});

// Blank strings and empty collections used to be filled in here and pruned
// again by writeServiceFile. Every reader defaults them itself, so the fill
// only hid what actually reaches the file.
describe("ServiceNormalizer blank fields", () => {
  it("adds no blank placeholders", () => {
    const service = ServiceNormalizer.normalizeService({
      id: "s1",
      content: {},
    });

    expect(Object.keys(service.content)).toEqual(["migrations"]);
  });

  it("adds the environment sourceType the schema requires", () => {
    const service = ServiceNormalizer.normalizeService({
      id: "s1",
      content: { environments: [{ id: "e1", name: "dev" }] },
    });

    expect(service.content.environments[0]).toEqual({
      id: "e1",
      name: "dev",
      sourceType: "MANUAL",
    });
  });

  it("leaves an existing sourceType alone", () => {
    const service = ServiceNormalizer.normalizeService({
      id: "s1",
      content: {
        environments: [
          { id: "e1", name: "dev", sourceType: "MAAS_BY_CLASSIFIER" },
        ],
      },
    });

    expect(service.content.environments[0].sourceType).toBe(
      "MAAS_BY_CLASSIFIER",
    );
  });
});
