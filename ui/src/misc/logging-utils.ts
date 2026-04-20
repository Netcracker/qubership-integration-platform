import {
  ChainLoggingProperties,
  ChainLoggingSettings,
} from "../api/apiTypes.ts";

export function pickLoggingProperties(
  settings: ChainLoggingSettings | null | undefined,
): ChainLoggingProperties | undefined {
  if (!settings) {
    return undefined;
  }
  return settings.custom ?? settings.consulDefault ?? settings.fallbackDefault;
}
