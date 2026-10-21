import type { Api } from "../../api/api.ts";
import type { Chain } from "../../api/apiTypes.ts";
import type { ChainModificationProposal } from "./ChainModificationConfirmation.tsx";
import type { ChainContext } from "./useChainContext.ts";
import { publishCatalogChange } from "../../misc/catalog-change-events.ts";

/**
 * Applies a proposal and reports completed catalog writes, including partial failures.
 */
export async function applyChainModificationProposal(
  proposal: ChainModificationProposal,
  api: Api,
  chainContext: ChainContext,
): Promise<void> {
  const chainId = proposal.chainId || chainContext.chain.id;
  let wrote = false;
  try {
    for (const change of proposal.changes) {
      switch (change.action) {
        case "updateChain":
          await api.updateChain(chainId, change.patch as Partial<Chain>);
          wrote = true;
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
              const elProps = target as {
                properties?: Record<string, unknown>;
              };
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
              wrote = true;
            }
          }
          break;
        }
        case "updateElement":
          await api.updateElement(change.patch, chainId, change.elementId);
          wrote = true;
          break;
        case "deleteElements":
          if (change.elementIds.length) {
            await api.deleteElements(change.elementIds, chainId);
            wrote = true;
          }
          break;
        case "createConnection":
          await api.createConnection(change.connection, chainId);
          wrote = true;
          break;
        case "deleteConnections":
          if (change.connectionIds.length) {
            await api.deleteConnections(change.connectionIds, chainId);
            wrote = true;
          }
          break;
        default:
          console.warn("[AI] Unknown chain modification action", change);
      }
    }
  } finally {
    if (wrote)
      publishCatalogChange({ chainIds: [chainId], chainsListChanged: true });
  }
}
