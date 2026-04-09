import type { Api } from "../../api/api.ts";
import type { Chain } from "../../api/apiTypes.ts";
import type { ChainModificationProposal } from "./ChainModificationConfirmation.tsx";
import type { ChainContext } from "./useChainContext.ts";

/**
 * Applies a chain modification proposal via the API. Does not refresh UI; caller should refresh chain context after success.
 */
export async function applyChainModificationProposal(
  proposal: ChainModificationProposal,
  api: Api,
  chainContext: ChainContext,
): Promise<void> {
  const chainId = proposal.chainId || chainContext.chain.id;
  for (const change of proposal.changes) {
    switch (change.action) {
      case "updateChain":
        await api.updateChain(chainId, change.patch as Partial<Chain>);
        break;
      case "createElement": {
        const { type, properties } = change.request;
        if (properties && Object.keys(properties).length > 0) {
          const elements = await api.getElementsByType(chainId, type);
          if (elements.length > 0) {
            let target = elements[0];
            if (elements.length > 1 && type === "http-trigger") {
              const candidate = elements.find((el) => {
                const props = (el as { properties?: Record<string, unknown> })
                  .properties;
                return !props || props.contextPath === undefined;
              });
              if (candidate) target = candidate;
            }
            const elProps = target as { properties?: Record<string, unknown> };
            const currentProps = elProps.properties ?? {};
            await api.updateElement(
              {
                name: target.name,
                description:
                  (target as { description?: string }).description || "",
                type: target.type,
                parentElementId: (target as { parentElementId?: string })
                  .parentElementId,
                properties: { ...currentProps, ...properties },
              },
              chainId,
              target.id,
            );
          }
        }
        break;
      }
      case "updateElement":
        await api.updateElement(change.patch, chainId, change.elementId);
        break;
      case "deleteElements":
        await api.deleteElements(change.elementIds, chainId);
        break;
      case "createConnection":
        await api.createConnection(change.connection, chainId);
        break;
      case "deleteConnections":
        await api.deleteConnections(change.connectionIds, chainId);
        break;
      default:
        console.warn("[AI] Unknown chain modification action", change);
    }
  }
}
