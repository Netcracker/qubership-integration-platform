/**
 * Centralized semantic colors for the application
 * These colors should remain consistent across themes
 */
import type { GlobalToken } from "antd";
import { DeploymentStatus } from "../api/apiTypes";

// HTTP method colors, matching the Swagger/OpenAPI method palette.
export const METHOD_COLORS: Record<string, string> = {
  GET: "#61affe",
  POST: "#49cc90",
  PUT: "#fca130",
  DELETE: "#f93e3e",
  PATCH: "#50e3c2",
  HEAD: "#9012fe",
  OPTIONS: "#0d5aa7",
  TRACE: "#1f1f1f",
  QUERY: "#1890ff",
  MUTATION: "#52c41a",
  SUBSCRIPTION: "#722ed1",
  // AsyncAPI operations (asyncapi-react palette, white label). From the
  // application's perspective: receive/publish are incoming (green),
  // send/subscribe are outgoing (blue) — so AsyncAPI 2.x publish maps to
  // green and subscribe to blue, matching the v2→v3 action flip.
  SEND: "#3182ce",
  RECEIVE: "#38a169",
  PUBLISH: "#38a169",
  SUBSCRIBE: "#3182ce",
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

// Brand accent tones for solid semantic badges (protocol / source / status tags
// rendered via qip-solid-tag). Single source of truth so the maps below and
// STATUS_COLORS (services/utils.tsx) never drift apart.
export const SOLID_TAG_TONES = {
  blue: "#0068ff",
  green: "#20cb64",
  red: "#ff5260",
  orange: "#fca130",
  lightblue: "#4FC0F8",
  violet: "#9012fe",
} as const;

// Protocol tag colors (SourceFlagTag with kind="protocol"; rendered lowercase,
// white label). Distinct from SOURCE_COLORS so protocol and source flags can be
// tuned independently. Unlisted protocols fall back to getSemanticColor.
export const PROTOCOL_COLORS: Record<string, string> = {
  http: SOLID_TAG_TONES.blue,
  kafka: SOLID_TAG_TONES.green,
  amqp: SOLID_TAG_TONES.orange,
  graphql: SOLID_TAG_TONES.red,
  grpc: SOLID_TAG_TONES.lightblue,
  metamodel: SOLID_TAG_TONES.violet,
};

// Source flag tag colors (SourceFlagTag source path; white label). Unlisted
// sources fall back to getSemanticColor.
export const SOURCE_FLAG_COLORS: Record<string, string> = {
  manual: SOLID_TAG_TONES.blue,
  discovered: SOLID_TAG_TONES.green,
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
