/**
 * Centralized semantic colors for the application
 * These colors should remain consistent across themes
 */

// HTTP Method Colors (from ServicesTreeTable.tsx)
export const METHOD_COLORS: Record<string, string> = {
  GET: "#61affe",
  POST: "#49cc90",
  PUT: "#fca130",
  DELETE: "#f93e3e",
  PATCH: "#50e3c2",
  QUERY: "#1890ff",
  MUTATION: "#52c41a",
  SUBSCRIPTION: "#722ed1",
};

// Source/Protocol Colors (from SourceFlagTag.tsx)
export const SOURCE_COLORS: Record<string, string> = {
  manual: "blue",
  discovered: "green",
  updated: "blue",
  created: "green",
  empty: "gray",
  http: "blue",
  kafka: "green",
  amqp: "orange",
  graphql: "red",
  metamodel: "violet",
  grpc: "lightblue",
  soap: "red",
  New: "blue",
  use: "green",
  deprecated: "red",
  POSTGRESQL: "blue",
};

// Predefined color palette
export const COLOR_PALETTE: Record<string, string> = {
  blue: "#1677ff",
  green: "#52c41a",
  orange: "#fa8c16",
  red: "#ff4d4f",
  gray: "#bfbfbf",
  violet: "#9012fe",
  lightblue: "#4FC0F8",
};

// Helper to get actual color from semantic name
export function getSemanticColor(semanticName: string): string {
  const paletteKey = SOURCE_COLORS[semanticName];
  if (paletteKey && COLOR_PALETTE[paletteKey]) {
    return COLOR_PALETTE[paletteKey];
  }
  return COLOR_PALETTE.blue; // fallback
}

// Deployment status colors (from DeploymentRuntimeState.tsx)
export function getDeploymentStatusColor(status: string): string {
  switch (status) {
    case "DEPLOYED":
      return "green";
    case "PROCESSING":
      return "blue";
    case "FAILED":
      return "red";
    case "REMOVED":
      return "orange";
    default:
      return "#888888";
  }
}
