/** @jest-environment jsdom */
import { applyChainModificationProposal } from "../../../src/components/ai/applyChainModificationProposal.ts";
import { subscribeCatalogChanges } from "../../../src/misc/catalog-change-events.ts";
import type { Api } from "../../../src/api/api.ts";
import type { ChainContext } from "../../../src/components/ai/useChainContext.ts";
import type { ChainModificationProposal } from "../../../src/components/ai/ChainModificationConfirmation.tsx";

const context = { chain: { id: "current" } } as ChainContext;
const proposal = (changes: ChainModificationProposal["changes"]) =>
  ({ chainId: "target", changes }) as ChainModificationProposal;

it("should emit one signal for successful writes even if a later write fails", async () => {
  const error = new Error("second write failed");
  const api = {
    updateChain: jest.fn().mockResolvedValue({}),
    deleteElements: jest.fn().mockRejectedValue(error),
  } as unknown as Api;
  const receive = jest.fn();
  const unsubscribe = subscribeCatalogChanges(receive);
  try {
    await expect(
      applyChainModificationProposal(
        proposal([
          { action: "updateChain", patch: { name: "new" } },
          { action: "deleteElements", elementIds: ["x"] },
        ]),
        api,
        context,
      ),
    ).rejects.toBe(error);
    expect(receive).toHaveBeenCalledTimes(1);
    expect(receive).toHaveBeenCalledWith({
      chainIds: ["target"],
      chainsListChanged: true,
    });
  } finally {
    unsubscribe();
  }
});

it("should not signal empty proposals, read-only branches, or failure before any write", async () => {
  const api = {
    getElementsByType: jest.fn().mockResolvedValue([]),
    updateChain: jest.fn().mockRejectedValue(new Error("failed")),
  } as unknown as Api;
  const receive = jest.fn();
  const unsubscribe = subscribeCatalogChanges(receive);
  try {
    await applyChainModificationProposal(proposal([]), api, context);
    await applyChainModificationProposal(
      proposal([
        {
          action: "createElement",
          request: { type: "script", properties: { script: "return 1" } },
        },
      ]),
      api,
      context,
    );
    await expect(
      applyChainModificationProposal(
        proposal([{ action: "updateChain", patch: {} }]),
        api,
        context,
      ),
    ).rejects.toThrow("failed");
    expect(receive).not.toHaveBeenCalled();
  } finally {
    unsubscribe();
  }
});
