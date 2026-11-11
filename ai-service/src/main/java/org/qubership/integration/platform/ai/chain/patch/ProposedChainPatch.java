package org.qubership.integration.platform.ai.chain.patch;

import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;

/**
 * A patch shown to the reader and waiting to be answered.
 *
 * <p>Two bindings guard the write. {@code patchHash} is what the decision card carries: an answer
 * naming a different hash belongs to a card the conversation has moved past. {@code baseGraphDigest}
 * is the chain this patch was built against: if the chain no longer digests to it when the answer
 * arrives, someone changed it in the meantime and applying would overwrite their work.
 */
public record ProposedChainPatch(
    String chainId,
    GraphPatch patch,
    PatchedChain patched,
    String patchHash,
    String baseGraphDigest,
    String summary) {}
