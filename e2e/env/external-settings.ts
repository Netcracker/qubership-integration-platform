import type { ServiceRole } from "./index.ts";

export interface ExternalBrokers {
  kafka?: { host: string; engine: string };
  rabbitmq?: { host: string; engine: string; username: string; password: string };
}

export interface ExternalSettings {
  services: Partial<Record<ServiceRole, string>>;
  ui?: string;
  proxy?: string;
  auth?: string;
  infrastructure?: string;
  brokers?: ExternalBrokers;
  deployment?: { domain: string; domainType?: "CLASSIC" | "MICRO" };
  routePrefix: string;
  run: string;
  stateDir: string;
}

const SETTINGS = "QIP_E2E_EXTERNAL";

export function setExternalSettings(settings: ExternalSettings): void {
  process.env[SETTINGS] = JSON.stringify(settings);
}

export function externalSettings(): ExternalSettings | undefined {
  const value = process.env[SETTINGS];
  return value === undefined ? undefined : JSON.parse(value) as ExternalSettings;
}

export function externalService(settings: ExternalSettings, role: ServiceRole): string {
  const base = settings.services[role];
  if (!base) throw new Error(`Set services["${role}"] in the external suite configuration.`);
  return base;
}
