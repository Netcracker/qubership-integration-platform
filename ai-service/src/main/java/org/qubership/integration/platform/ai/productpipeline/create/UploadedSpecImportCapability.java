package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.catalog.materialize.UploadedSpecImportResult;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.UploadedSpecCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

@ApplicationScoped
public class UploadedSpecImportCapability implements StageCapability {

  public static final String CAPABILITY_ID = "uploaded-spec-import";

  private static final Logger LOG = Logger.getLogger(UploadedSpecImportCapability.class);

  private final CatalogMutationGateway catalogMutationGateway;
  private final RequirementDraftStore draftStore;

  @Inject
  public UploadedSpecImportCapability(
      CatalogMutationGateway catalogMutationGateway, RequirementDraftStore draftStore) {
    this.catalogMutationGateway = Objects.requireNonNull(catalogMutationGateway);
    this.draftStore = Objects.requireNonNull(draftStore);
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    RequirementDraft draft = resolveDraft(context);
    if (draft == null) {
      return completed(
          StageOutcome.of(
              StageOutcomeClass.MISSING_MANDATORY_INPUT,
              "requirement-draft is required for uploaded-spec-import"));
    }

    List<UploadedSpecCandidate> candidates = draft.uploadedSpecCandidates();
    if (candidates == null || candidates.isEmpty()) {
      return succeeded(draft, context, "uploaded-spec-import skipped");
    }

    String conversationId = context.conversationId();
    SkillActivitySupport.bindParents(CAPABILITY_ID);
    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(SkillActivitySupport.running(CAPABILITY_ID)),
            Multi.createFrom()
                .uni(
                    catalogMutationGateway
                        .importUploadedSpecifications(conversationId, candidates)
                        .map(
                            results -> {
                              RequirementDraft updated =
                                  draft
                                      .withUploadedSpecCandidates(List.of())
                                      .withUploadedSpecImportResults(results);
                              draftStore.put(conversationId, updated);
                              LOG.infof(
                                  "uploaded-spec-import succeeded conversationId=%s results=%d",
                                  conversationId, results.size());
                              return (CapabilitySignal)
                                  new CapabilitySignal.Completed(
                                      new StageOutcome(
                                          StageOutcomeClass.SUCCEEDED,
                                          List.of(
                                              new ArtifactCandidate(
                                                  CompilationArtifacts.Kind.REQUIREMENT_DRAFT,
                                                  updated,
                                                  context.inputRefs())),
                                          "Uploaded specifications imported",
                                          null));
                            }))
                .onItem()
                .transformToMultiAndConcatenate(
                    completed ->
                        Multi.createFrom()
                            .iterable(
                                SkillActivitySupport.wrapTerminal(CAPABILITY_ID, List.of(completed))))
                .onFailure()
                .recoverWithMulti(
                    error -> {
                      LOG.errorf(
                          error, "uploaded-spec-import failed conversationId=%s", conversationId);
                      return Multi.createFrom()
                          .items(
                              SkillActivitySupport.error(CAPABILITY_ID),
                              new CapabilitySignal.Completed(
                                  StageOutcome.of(
                                      StageOutcomeClass.NEEDS_INPUT,
                                      "Uploaded specification import failed: " + error.getMessage())));
                    }))
        .onTermination()
        .invoke((failure, cancelled) -> SkillActivitySupport.clearParents());
  }

  private RequirementDraft resolveDraft(StageExecutionContext context) {
    return draftStore.get(context.conversationId()).orElse(null);
  }

  private static Multi<CapabilitySignal> succeeded(
      RequirementDraft draft, StageExecutionContext context, String message) {
    return completed(
        new StageOutcome(
            StageOutcomeClass.SUCCEEDED,
            List.of(
                new ArtifactCandidate(
                    CompilationArtifacts.Kind.REQUIREMENT_DRAFT, draft, context.inputRefs())),
            message,
            null));
  }

  private static Multi<CapabilitySignal> completed(StageOutcome outcome) {
    return Multi.createFrom().item(new CapabilitySignal.Completed(outcome));
  }
}
