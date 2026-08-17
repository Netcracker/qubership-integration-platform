package org.qubership.integration.platform.ai.chain.edit;

import java.util.Objects;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;

/**
 * One edit to prepare: the chain as it stands in the catalog, and what the reader asked for.
 *
 * <p>{@code editRunId} keys the run's own compiler workspace. It must differ per compilation so
 * two edits in one conversation cannot read each other's artifacts.
 */
public record ChainEditRequest(
    String conversationId,
    String chainId,
    String editRunId,
    ImportedChainPlan imported,
    String userRequest,
    String languageVersion) {

  public ChainEditRequest {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(chainId, "chainId");
    Objects.requireNonNull(editRunId, "editRunId");
    Objects.requireNonNull(imported, "imported");
    userRequest = userRequest == null ? "" : userRequest;
    languageVersion =
        languageVersion == null || languageVersion.isBlank() ? "2026.1" : languageVersion.trim();
  }
}
