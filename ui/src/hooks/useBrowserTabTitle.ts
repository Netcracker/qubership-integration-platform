import { useEffect, useState } from "react";
import { useLocation } from "react-router";
import { api } from "../api/api.ts";
import {
  BROWSER_TAB_DEFAULT_TITLE,
  extractChainId,
  getStaticBrowserTabTitle,
  parseServiceRoute,
  resolveOperationTabTitle,
} from "../misc/browserTabTitle.ts";

async function resolveServiceTabTitle(
  pathname: string,
): Promise<string | null> {
  const route = parseServiceRoute(pathname);
  if (!route || route.variant === "list" || !route.systemId) {
    return null;
  }

  if (route.operationId && route.specId) {
    try {
      const [operations, operationInfo] = await Promise.all([
        api.getOperations(route.specId, {}),
        api.getOperationInfo(route.operationId),
      ]);
      const operation = operations.find((op) => op.id === route.operationId);
      return resolveOperationTabTitle(
        operation?.name,
        operationInfo.specification,
      );
    } catch {
      return resolveOperationTabTitle(undefined, undefined);
    }
  }

  if (route.specId && route.groupId) {
    try {
      const models = await api.getSpecificationModel(
        route.systemId,
        route.groupId,
      );
      const model = models.find((m) => m.id === route.specId);
      if (model?.name) {
        return model.name;
      }
    } catch (error) {
      console.error(
        `Unable to get specification: systemId=${route.systemId}, groupId=${route.groupId}`,
        error,
      );
    }
  }

  if (route.groupId) {
    try {
      const groups = await api.getApiSpecifications(route.systemId);
      const group = groups.find((g) => g.id === route.groupId);
      if (group?.name) {
        return group.name;
      }
    } catch (error) {
      console.error(
        `Unable to get specification: systemId=${route.systemId}, groupId=${route.groupId}`,
        error,
      );
    }
  }

  try {
    const service = await api.getService(route.systemId);
    return service.name;
  } catch {
    return null;
  }
}

async function resolveChainTabTitle(chainId: string): Promise<string | null> {
  try {
    const chain = await api.getChain(chainId);
    return chain.name;
  } catch {
    return null;
  }
}

export function useBrowserTabTitle(): void {
  const { pathname, hash } = useLocation();
  const chainId = extractChainId(pathname);
  const [hashTick, setHashTick] = useState(0);

  useEffect(() => {
    const onHashChange = () => {
      setHashTick((tick) => tick + 1);
    };
    window.addEventListener("hashchange", onHashChange);
    return () => window.removeEventListener("hashchange", onHashChange);
  }, []);

  useEffect(() => {
    let cancelled = false;

    if (!chainId) {
      return;
    }

    void resolveChainTabTitle(chainId).then((chainTitle) => {
      if (!cancelled) {
        document.title = chainTitle ?? BROWSER_TAB_DEFAULT_TITLE;
      }
    });

    return () => {
      cancelled = true;
    };
  }, [chainId]);

  useEffect(() => {
    if (chainId) {
      return;
    }

    let cancelled = false;

    const applyTitle = (title: string) => {
      if (!cancelled) {
        document.title = title;
      }
    };

    const resolve = async () => {
      const effectiveHash =
        window.location.hash.slice(1) || hash.replace(/^#/, "");
      const staticTitle = getStaticBrowserTabTitle(pathname, effectiveHash);
      if (staticTitle) {
        applyTitle(staticTitle);
        return;
      }

      const serviceTitle = await resolveServiceTabTitle(pathname);
      if (serviceTitle) {
        applyTitle(serviceTitle);
        return;
      }

      applyTitle(BROWSER_TAB_DEFAULT_TITLE);
    };

    void resolve();

    return () => {
      cancelled = true;
    };
  }, [pathname, hash, hashTick, chainId]);
}
