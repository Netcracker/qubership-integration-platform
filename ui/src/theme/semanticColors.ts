/**
 * Centralized semantic colors for the application
 * These colors should remain consistent across themes
 */
import type { GlobalToken } from "antd";
import { DeploymentStatus } from "../api/apiTypes";

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

export type RgbChannels = { r: number; g: number; b: number };

/** Parse `#RRGGBB` (with or without leading `#`) into 0–255 channels. */
export function parseHex(hex: string): RgbChannels | null {
  const m = /^#?([a-f\d]{2})([a-f\d]{2})([a-f\d]{2})$/i.exec(hex.trim());
  if (!m) return null;
  return {
    r: parseInt(m[1], 16),
    g: parseInt(m[2], 16),
    b: parseInt(m[3], 16),
  };
}

/** WCAG relative luminance for #RRGGBB — pick readable text on colored tags (see SourceFlagTag, HttpMethod). */
export function foregroundForBackground(hex: string): string {
  const channels = parseHex(hex);
  if (!channels) return "rgba(0, 0, 0, 0.88)";
  const lin = (c: number) =>
    c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
  const L =
    0.2126 * lin(channels.r / 255) +
    0.7152 * lin(channels.g / 255) +
    0.0722 * lin(channels.b / 255);
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

export type DeploymentStatusTone = {
  accent: string;
  bg: string;
  border: string;
  borderHover: string;
  text: string;
};

type TokenToneKeys = {
  accent: keyof GlobalToken;
  bg: keyof GlobalToken;
  border: keyof GlobalToken;
  borderHover: keyof GlobalToken;
  text: keyof GlobalToken;
};

const STATUS_TONE_KEYS: Record<DeploymentStatus, TokenToneKeys> = {
  [DeploymentStatus.DEPLOYED]: {
    accent: "colorSuccess",
    bg: "colorSuccessBg",
    border: "colorSuccessBorder",
    borderHover: "colorSuccessBorderHover",
    text: "colorSuccessText",
  },
  [DeploymentStatus.PROCESSING]: {
    accent: "colorInfo",
    bg: "colorInfoBg",
    border: "colorInfoBorder",
    borderHover: "colorInfoBorderHover",
    text: "colorInfoText",
  },
  [DeploymentStatus.FAILED]: {
    accent: "colorError",
    bg: "colorErrorBg",
    border: "colorErrorBorder",
    borderHover: "colorErrorBorderHover",
    text: "colorErrorText",
  },
  [DeploymentStatus.REMOVED]: {
    accent: "colorWarning",
    bg: "colorWarningBg",
    border: "colorWarningBorder",
    borderHover: "colorWarningBorderHover",
    text: "colorWarningText",
  },
};

const NEUTRAL_TONE_KEYS: TokenToneKeys = {
  accent: "colorTextTertiary",
  bg: "colorFillQuaternary",
  border: "colorBorderSecondary",
  borderHover: "colorBorder",
  text: "colorTextSecondary",
};

export function getDeploymentStatusTone(
  status: DeploymentStatus | string,
  token: GlobalToken,
): DeploymentStatusTone {
  const keys =
    STATUS_TONE_KEYS[status as DeploymentStatus] ?? NEUTRAL_TONE_KEYS;
  return {
    accent: token[keys.accent] as string,
    bg: token[keys.bg] as string,
    border: token[keys.border] as string,
    borderHover: token[keys.borderHover] as string,
    text: token[keys.text] as string,
  };
}

/** True if the active theme's base background is dark — used to branch terminal-style UI. */
export function isTokenDark(token: GlobalToken): boolean {
  const channels = parseHex(token.colorBgBase);
  if (!channels) return false;
  return (channels.r + channels.g + channels.b) / 3 < 128;
}
