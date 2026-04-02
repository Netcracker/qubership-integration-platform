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

export const SWIMLANE_COLORS: Record<string, string> = {
  Blue: "#bddcf2",
  Green: "#d0e7a1",
  Yellow: "#fdf39d",
  Purple: "#c6b0f2",
  Lagoon: "#a5e1d2",
  Brown: "#cabcb7",
};

// Helper to get actual color from semantic name
export function getSemanticColor(semanticName: string): string {
  const paletteKey = SOURCE_COLORS[semanticName];
  if (paletteKey && COLOR_PALETTE[paletteKey]) {
    return COLOR_PALETTE[paletteKey];
  }
  return COLOR_PALETTE.blue; // fallback
}

/** WCAG relative luminance for #RRGGBB — pick readable text on colored tags (see SourceFlagTag, HttpMethod). */
export function foregroundForBackground(hex: string): string {
  const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex.trim());
  if (!m) return "rgba(0, 0, 0, 0.88)";
  const r = parseInt(m[1], 16) / 255;
  const g = parseInt(m[2], 16) / 255;
  const b = parseInt(m[3], 16) / 255;
  const lin = (c: number) =>
    c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  const L = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b);
  return L > 0.45 ? "rgba(0, 0, 0, 0.88)" : "#ffffff";
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
