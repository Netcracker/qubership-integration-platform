package org.qubership.integration.platform.ai.llm.routing;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.productpipeline.create.CreateProductPipelineCoordinator;
import org.qubership.integration.platform.ai.productpipeline.store.RunStatus;

/**
 * Derives conversation phase from product CREATE run state when present; otherwise from the
 * requirement draft only. Legacy design/plan/bundle/workflow stores are not CREATE phase
 * authority after hard cutover.
 */
@ApplicationScoped
public class ConversationPhaseResolver {

  private final RequirementDraftStore requirementDraftStore;
  private final CreateProductPipelineCoordinator productPipelineCoordinator;

  public ConversationPhaseResolver(RequirementDraftStore requirementDraftStore) {
    this(requirementDraftStore, null);
  }

  @Inject
  public ConversationPhaseResolver(
      RequirementDraftStore requirementDraftStore,
      CreateProductPipelineCoordinator productPipelineCoordinator) {
    this.requirementDraftStore = requirementDraftStore;
    this.productPipelineCoordinator = productPipelineCoordinator;
  }

  public ConversationPhase resolve(String conversationId) {
    if (productPipelineCoordinator != null) {
      var document = productPipelineCoordinator.loadRun(conversationId);
      if (document.isPresent()) {
        RunStatus status = document.get().run().status();
        String stageId = document.get().run().currentStageId();
        return switch (status) {
          case PLAN_APPROVED, CHAIN_MATERIALIZED, WAITING_FOR_IMPLEMENT ->
              ConversationPhase.PLAN_APPROVED;
          case WAITING_FOR_APPROVAL ->
              "planning".equals(stageId)
                  ? ConversationPhase.PLAN_CANDIDATE
                  : ConversationPhase.DESIGN_REVIEW;
          case WAITING_FOR_INPUT, RUNNING -> phaseForProductStage(stageId);
          case FAILED -> ConversationPhase.DISCOVERY;
        };
      }
    }

    RequirementDraft draft = requirementDraftStore.get(conversationId).orElse(null);
    if (draft == null) {
      return ConversationPhase.COLD;
    }
    if (draft.hasPendingImport()) {
      return ConversationPhase.IMPORT_PENDING;
    }
    if (!draft.readyForPlan()) {
      return ConversationPhase.DISCOVERY;
    }
    return ConversationPhase.PLAN_DRAFT;
  }

  /** Maps create-chain product stage ids onto conversation phases. */
  static ConversationPhase phaseForProductStage(String stageId) {
    if ("requirement-discovery".equals(stageId)) {
      return ConversationPhase.DISCOVERY;
    }
    if ("import-stage".equals(stageId)) {
      return ConversationPhase.IMPORT_PENDING;
    }
    if ("planning".equals(stageId)) {
      return ConversationPhase.PLAN_CANDIDATE;
    }
    return ConversationPhase.DESIGN_REVIEW;
  }
}
