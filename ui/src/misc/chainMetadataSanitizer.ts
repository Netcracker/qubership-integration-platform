import {
  Chain,
  ChainCreationRequest,
  EntityLabel,
  IntegrationSystem,
  SystemRequest,
} from "../api/apiTypes.ts";
import { escapeHtml, unescapeHtml } from "./html-utils.ts";

export function decodeStoredText(value?: string | null): string {
  if (!value) {
    return "";
  }
  return unescapeHtml(value);
}

/** Idempotent: safe to call on already-escaped values before persisting. */
export function normalizeStoredText(value?: string | null): string | undefined {
  if (value === undefined || value === null) {
    return undefined;
  }
  return escapeHtml(decodeStoredText(value));
}

function sanitizeLabels(labels?: EntityLabel[]): EntityLabel[] {
  return (labels ?? []).map((label) => ({
    ...label,
    name: normalizeStoredText(label.name) ?? "",
  }));
}

export function sanitizeChainCreationRequest(
  request: ChainCreationRequest,
): ChainCreationRequest {
  return {
    ...request,
    description: normalizeStoredText(request.description),
    labels: sanitizeLabels(request.labels),
  };
}

export function sanitizeChainUpdate(chain: Partial<Chain>): Partial<Chain> {
  const result: Partial<Chain> = { ...chain };
  if ("description" in chain) {
    result.description = normalizeStoredText(chain.description);
  }
  if ("labels" in chain) {
    result.labels = sanitizeLabels(chain.labels);
  }
  return result;
}

export function sanitizeServiceRequest(system: SystemRequest): SystemRequest {
  return {
    ...system,
    description: normalizeStoredText(system.description),
    labels: sanitizeLabels(system.labels),
  };
}

export function sanitizeServiceUpdate(
  data: Partial<IntegrationSystem>,
): Partial<IntegrationSystem> {
  const result: Partial<IntegrationSystem> = { ...data };
  if ("description" in data) {
    result.description = normalizeStoredText(data.description);
  }
  if ("labels" in data) {
    result.labels = sanitizeLabels(data.labels);
  }
  return result;
}
