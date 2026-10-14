// Seeds the service document an import writes beside, and returns that file's uri — the second
// argument `getApiResponse` needs, since `getBaseFolder` throws `Service file must be selected`
// unless `getFileType` answers `SERVICE` for it.
//
// Modelled on `src/web/test/workspace/services/3b2e9742-…`, with three deliberate differences:
//
//   - `protocol` is a parameter and absent by default, because
//     `SpecificationValidator.validateSpecificationProtocol` runs before `ensureSystemProtocol` and
//     throws `Protocol mismatch` unless the system's protocol is absent or equal to the imported
//     one. The fixture's `HTTP` would refuse every SOAP, gRPC and GraphQL import.
//   - `environments` is omitted rather than empty. `ServiceNormalizer` supplies `[]` for an absent
//     key, so `ensureInternalEnvironment` takes its create arm and the import mints an environment
//     id and rewrites the service file a second time. A seed carrying the fixture's one environment
//     with a real address makes that a no-op.
//   - `migrations` stays the quoted string the fixture writes. `MigrationFieldStrategy.getVersions`
//     reads it with `.asText()`, so a YAML list yields an empty version set and the catalog refuses
//     the document at level 3.
import type { Uri } from "vscode";
import { seedWorkspace, workspaceUri } from "../fake-vscode";

const SERVICE_SCHEMA =
  "http://netcracker.com/schemas/product/cloud-integration-platform/conf-model/service";

export type ServiceSeed = {
  id: string;
  name?: string;
  // Absent by default. Set it only to reach a protocol-dependent branch, such as the gRPC
  // environment fallback, which fires once `system.protocol` is `grpc`.
  protocol?: string;
  // `IMPLEMENTED` is the only value `ALLOWED_PROTOCOL_MAP` restricts, so it is what reaches
  // `Specification type is not allowed for … system`.
  integrationSystemType?: "INTERNAL" | "EXTERNAL" | "IMPLEMENTED";
};

export function serviceFilePath(id: string): string {
  return `services/${id}/${id}.service.cip.yaml`;
}

export function seedServiceDocument(seed: ServiceSeed): Uri {
  const name = seed.name ?? `import-${seed.id.slice(0, 8)}`;
  const lines = [
    "---",
    `id: "${seed.id}"`,
    `$schema: "${SERVICE_SCHEMA}"`,
    `name: "${name}"`,
    "content:",
    `  integrationSystemType: "${seed.integrationSystemType ?? "INTERNAL"}"`,
  ];
  if (seed.protocol !== undefined) {
    lines.push(`  protocol: "${seed.protocol}"`);
  }
  lines.push('  migrations: "[100, 101, 102]"');

  const filePath = serviceFilePath(seed.id);
  seedWorkspace({ [filePath]: `${lines.join("\n")}\n` });
  return workspaceUri(filePath);
}
