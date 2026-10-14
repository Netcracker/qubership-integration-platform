import { useEffect, useMemo, useState } from "react";
import { api } from "../../api/api";
import { Element } from "../../api/apiTypes";
import { flattenElements } from "../../components/testing/testingElements";
import { useNotificationService } from "../useNotificationService";

export type ChainElementOption = { value: string; label: string };

export type ChainElements = {
  elements: Element[];
  isLoading: boolean;
  options: ChainElementOption[];
};

/**
 * Elements of one chain, narrowed to the ones a picker offers. The tree is
 * flattened first: a trigger or a sender can sit inside a container. Pass a
 * predicate defined outside the component, since the fetch keys on it.
 */
export function useChainElements(
  chainId: string | null | undefined,
  predicate: (element: Element) => boolean,
): ChainElements {
  const notificationService = useNotificationService();
  const [elements, setElements] = useState<Element[]>([]);
  const [isLoading, setIsLoading] = useState(!!chainId);

  useEffect(() => {
    if (!chainId) {
      setElements([]);
      setIsLoading(false);
      return;
    }
    let cancelled = false;
    setIsLoading(true);
    void (async () => {
      try {
        const loaded = await api.getElements(chainId);
        if (!cancelled) {
          setElements(flattenElements(loaded).filter(predicate));
        }
      } catch (error) {
        if (!cancelled) {
          setElements([]);
          notificationService.requestFailed(
            "Failed to load chain elements",
            error,
          );
        }
      } finally {
        if (!cancelled) {
          setIsLoading(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [chainId, predicate, notificationService]);

  const options = useMemo<ChainElementOption[]>(
    () =>
      elements.map((element) => ({ value: element.id, label: element.name })),
    [elements],
  );

  return { elements, isLoading, options };
}
