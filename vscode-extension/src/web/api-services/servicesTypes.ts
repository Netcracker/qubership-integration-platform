import type { EntityLabel } from "@netcracker/qip-ui";

// @netcracker/qip-ui exports its types with `export type *`, so the enum used as a value is declared here.
// TypeScript treats it as compatible with the qip-ui enum because the name and the members match.
export enum IntegrationSystemType {
  EXTERNAL = "EXTERNAL",
  INTERNAL = "INTERNAL",
  IMPLEMENTED = "IMPLEMENTED",
  CONTEXT = "CONTEXT",
  MCP = "MCP",
}

// `SpecificationImportService` builds this request for `EnvironmentService`. The webview sends `EnvironmentRequest`.
export type EnvironmentServiceRequest = {
  name: string;
  address: string;
  description?: string;
  sourceType?: string;
  properties?: Record<string, string>;
  labels?: EntityLabel[];
  systemId?: string;
  isActive?: boolean;
};
