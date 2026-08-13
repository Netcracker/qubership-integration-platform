package org.qubership.integration.platform.ai.harness;

/** Request body for {@code POST /api/v1/harness/chain-patch-run}. */
public record ChainPatchHarnessRequest(String conversationId, String chainId, String prompt) {}
