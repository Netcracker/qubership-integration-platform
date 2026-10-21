package org.qubership.integration.platform.ai.harness;

/**
 * Request body for {@code POST /api/v1/harness/chain-patch-run}.
 *
 * <p>{@code allowRemoval} is off unless the caller asks for it. This path applies a patch with no
 * card and nobody watching, and a removal it makes cannot be undone -- so a suite that means to
 * exercise deletion has to say so, and one that does not cannot stumble into it.
 */
public record ChainPatchHarnessRequest(
    String conversationId, String chainId, String prompt, boolean allowRemoval) {

  public ChainPatchHarnessRequest(String conversationId, String chainId, String prompt) {
    this(conversationId, chainId, prompt, false);
  }
}
