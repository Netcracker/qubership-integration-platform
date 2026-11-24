import path from "node:path";
import type { SASLOptions } from "kafkajs";
import type { ServiceRole } from "./index.ts";

export interface ExternalBrokers {
  kafka?: {
    host: string;
    engine: string;
    listeners?: Partial<Record<"saslPlaintext" | "tls" | "saslTls" | "tls13", string>>;
    hostSasl?: Extract<SASLOptions, { mechanism: "plain" | "scram-sha-256" | "scram-sha-512" }>;
    hostTls?: boolean | { ca?: string[]; cert?: string; key?: string };
  };
  rabbitmq?: { host: string; engine: string; username: string; password: string };
  pubsub?: { host: string; projectId?: string };
  sftp?: { host: { host: string; port: number }; engine: string; username: string; password: string };
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
  full?: { sourceRoot?: string; dedicatedStand?: true; standDir: string; runToken: string };
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


export function externalRunStateDir(standDir: string, token: string): string {
  return path.join(standDir, "runs", token);
}

export function runStateFile(reference: string): string {
  const settings = externalSettings();
  return settings?.full ? path.join(settings.stateDir, path.basename(reference)) : reference;
}
