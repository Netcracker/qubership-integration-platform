package org.qubership.integration.platform.ai.chain.edit;

import java.util.Objects;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;

/**
 * One edit to prepare: the chain as it stands in the catalog, and what the reader asked for.
 *
 * <p>{@code editRunId} keys the run's own compiler workspace. It must differ per compilation so
 * two edits in one conversation cannot read each other's artifacts.
 *
 * <p>{@code transcriptWindow} and {@code pinnedFailureSafeText} come from the open-chain turn.
 * Both are empty when that context is missing or the pin is absent.
 */
public record ChainEditRequest(
    String conversationId,
    String chainId,
    String editRunId,
    ImportedChainPlan imported,
    String userRequest,
    String languageVersion,
    String transcriptWindow,
    String pinnedFailureSafeText) {

  public ChainEditRequest {
    Objects.requireNonNull(conversationId, "conversationId");
    Objects.requireNonNull(chainId, "chainId");
    Objects.requireNonNull(editRunId, "editRunId");
    Objects.requireNonNull(imported, "imported");
    userRequest = userRequest == null ? "" : userRequest;
    languageVersion =
        languageVersion == null || languageVersion.isBlank() ? "2026.1" : languageVersion.trim();
    transcriptWindow = transcriptWindow == null ? "" : transcriptWindow;
    pinnedFailureSafeText = pinnedFailureSafeText == null ? "" : pinnedFailureSafeText;
  }

  public ChainEditRequest(
      String conversationId,
      String chainId,
      String editRunId,
      ImportedChainPlan imported,
      String userRequest,
      String languageVersion) {
    this(
        conversationId,
        chainId,
        editRunId,
        imported,
        userRequest,
        languageVersion,
        "",
        "");
  }
}
