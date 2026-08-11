import { getAiServiceUrl } from "../../ai/appConfig.ts";

export type ChainPlanStatusDto = {
  hasActivePlan: boolean;
  approved: boolean;
  planId: string | null;
  chainName: string | null;
  updatedAt: string | null;
  openItemCount: number;
};

export type ChainPlanOpenItemDto = {
  itemId: string;
  kind: string;
  clientId: string;
  elementId: string | null;
  elementType: string | null;
  message: string;
  removedKeys: string[];
  dismissedByUser: boolean;
};

export type ChainPlanDetailDto = {
  planId: string;
  approved: boolean;
  chainName: string | null;
  apiHubRequired: boolean | null;
  apiHubReason: string | null;
  plan: unknown;
  openItems: ChainPlanOpenItemDto[];
};

function baseUrl(): string | null {
  const u = getAiServiceUrl();
  return u ? u.replace(/\/$/, "") : null;
}

export async function fetchChainPlanStatus(
  conversationId: string,
): Promise<ChainPlanStatusDto | null> {
  const base = baseUrl();
  if (!base) return null;
  const res = await fetch(
    `${base}/api/v1/chat/${conversationId}/chain-plan/status`,
  );
  if (!res.ok) return null;
  return (await res.json()) as ChainPlanStatusDto;
}

export async function fetchChainPlanDetail(
  conversationId: string,
): Promise<ChainPlanDetailDto | null> {
  const base = baseUrl();
  if (!base) return null;
  const res = await fetch(`${base}/api/v1/chat/${conversationId}/chain-plan`);
  if (!res.ok) return null;
  return (await res.json()) as ChainPlanDetailDto;
}

export async function dismissChainPlanOpenItems(
  conversationId: string,
): Promise<ChainPlanStatusDto | null> {
  const base = baseUrl();
  if (!base) return null;
  const res = await fetch(
    `${base}/api/v1/chat/${conversationId}/chain-plan/dismiss-open-items`,
    { method: "POST" },
  );
  if (!res.ok) return null;
  return (await res.json()) as ChainPlanStatusDto;
}

export async function approveChainPlanForBuild(
  conversationId: string,
): Promise<ChainPlanStatusDto | null> {
  const base = baseUrl();
  if (!base) return null;
  const res = await fetch(
    `${base}/api/v1/chat/${conversationId}/chain-plan/approve`,
    { method: "POST" },
  );
  if (!res.ok) return null;
  return (await res.json()) as ChainPlanStatusDto;
}
