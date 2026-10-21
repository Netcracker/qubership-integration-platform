export const FILE_EXTENSIONS = {
  chain: ".chain.cip.yaml",
  service: ".service.cip.yaml",
  contextService: ".context-service.cip.yaml",
  mcpService: ".mcp-service.cip.yaml",
  specificationGroup: ".specification-group.cip.yaml",
  specification: ".specification.cip.yaml",
} as const;

export function extractFilename(fileUri: { path: string } | string): string {
  if (typeof fileUri === "string") {
    return fileUri.split("/").pop() || "";
  }
  return fileUri.path.split("/").pop() || "";
}
