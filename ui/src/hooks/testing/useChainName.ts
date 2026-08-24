import { useEffect, useState } from "react";
import { api } from "../../api/api";

/**
 * Name of the chain an entity points at, for the scopes whose route carries no
 * chain of its own. A failed lookup answers nothing rather than notifying, so the
 * caller can fall back to the raw id.
 */
export function useChainName(
  chainId: string | null | undefined,
): string | undefined {
  const [name, setName] = useState<string>();

  useEffect(() => {
    if (!chainId) {
      setName(undefined);
      return;
    }
    let cancelled = false;
    void (async () => {
      try {
        const chain = await api.getChain(chainId);
        if (!cancelled) {
          setName(chain.name);
        }
      } catch {
        if (!cancelled) {
          setName(undefined);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [chainId]);

  return name;
}
