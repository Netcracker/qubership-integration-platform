package org.qubership.integration.platform.ai.productpipeline.facade;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import java.util.Optional;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade;

/**
 * Resolves a conversation to its run and reports what that run waits for.
 *
 * <p>This is the one place that binds a conversation to a concrete pipeline facade. Chat code reads
 * the result through {@link PendingAction}, so adding a second pipeline profile changes this class
 * and nothing above it.
 */
@ApplicationScoped
public class PendingActionQuery {

  private final CreateChainApplicationFacade createChain;

  @Inject
  public PendingActionQuery(CreateChainApplicationFacade createChain) {
    this.createChain = Objects.requireNonNull(createChain, "createChain");
  }

  /** The open wait, or empty when the conversation has no run or the run waits for nothing. */
  public Optional<PendingAction> forConversation(String conversationId) {
    Objects.requireNonNull(conversationId, "conversationId");
    return createChain.snapshot(conversationId).map(ExecutionSnapshot::pendingAction);
  }
}
