type ActiveCatalogAuthRefresh = {
  conversationId: string;
  serviceUrl: string;
  getAuthorization: () => Record<string, string>;
};

let activeRefresh: ActiveCatalogAuthRefresh | null = null;

/**
 * Remember the in-flight AI conversation so catalog auth can be hot-swapped on token refresh.
 *
 * Hot-swap state is per browser tab. In multi-replica deployments, route AI chat and this PUT to
 * the same pod (session affinity) or refresh may return 404 while the SSE runs elsewhere.
 */
export function registerCatalogAuthRefresh(
  conversationId: string,
  serviceUrl: string,
  getAuthorization: () => Record<string, string>,
): void {
  activeRefresh = { conversationId, serviceUrl, getAuthorization };
}

export function clearCatalogAuthRefresh(): void {
  activeRefresh = null;
}

/** Push the current Bearer token to ai-service for an active SSE conversation. */
export async function pushCatalogAuthRefresh(): Promise<void> {
  if (!activeRefresh) {
    return;
  }
  const headers = activeRefresh.getAuthorization();
  const authorization = headers.Authorization;
  if (typeof authorization !== "string" || !authorization) {
    return;
  }
  const base = activeRefresh.serviceUrl.replace(/\/$/, "");
  const url = `${base}/api/v1/chat/${encodeURIComponent(activeRefresh.conversationId)}/catalog-auth`;
  try {
    const response = await fetch(url, {
      method: "PUT",
      headers: { Authorization: authorization },
    });
    if (!response.ok && response.status !== 404) {
      console.warn(
        `Catalog auth refresh failed: HTTP ${response.status} for conversation ${activeRefresh.conversationId}`,
      );
    }
  } catch (error) {
    console.warn("Catalog auth refresh request failed", error);
  }
}
