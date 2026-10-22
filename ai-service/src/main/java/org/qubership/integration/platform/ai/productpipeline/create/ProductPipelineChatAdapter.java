package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Objects;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;

/**
 * Thin browser chat translator for product-owned CREATE conversations.
 *
 * <p>Lifecycle commands go through {@link
 * org.qubership.integration.platform.ai.productpipeline.create.facade.CreateChainApplicationFacade}.
 * This adapter keeps browser {@code ChatRequest}/{@code ChatEvent} convenience behavior, including
 * free-form approval intent.
 */
@ApplicationScoped
public class ProductPipelineChatAdapter {

  private final CreateRunSelectionService selectionService;
  private final CreateProductPipelineCoordinator coordinator;

  @Inject
  public ProductPipelineChatAdapter(
      CreateRunSelectionService selectionService, CreateProductPipelineCoordinator coordinator) {
    this.selectionService = Objects.requireNonNull(selectionService, "selectionService");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
  }

  /** True when a supported product CREATE binding already exists for the conversation. */
  public boolean owns(String conversationId) {
    return selectionService.existing(conversationId).isPresent();
  }

  public Multi<ChatEvent> handle(ChatRequest request, String conversationId) {
    return coordinator.handle(request, conversationId);
  }
}
