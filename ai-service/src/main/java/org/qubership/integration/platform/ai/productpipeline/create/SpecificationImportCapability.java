package org.qubership.integration.platform.ai.productpipeline.create;

import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.integration.apihub.ApiHubRequirementRefs;
import org.qubership.integration.platform.ai.integration.catalog.cache.ConversationCatalogCache;
import org.qubership.integration.platform.ai.integration.catalog.pipeline.CatalogMutationGateway;
import org.qubership.integration.platform.ai.plan.RequirementDraft;
import org.qubership.integration.platform.ai.plan.RequirementDraftStore;
import org.qubership.integration.platform.ai.plan.ResolvedCatalogBinding;
import org.qubership.integration.platform.ai.productpipeline.capability.ArtifactCandidate;
import org.qubership.integration.platform.ai.productpipeline.capability.CapabilitySignal;
import org.qubership.integration.platform.ai.productpipeline.capability.SkillActivitySupport;
import org.qubership.integration.platform.ai.productpipeline.capability.StageCapability;
import org.qubership.integration.platform.ai.productpipeline.capability.StageExecutionContext;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcome;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;

/**
 * Product-pipeline APIHub → runtime-catalog import stage. Mutates the same requirement-draft in
 * place (ADR 0001). Skip when no candidate or binding already present is handled by profile {@code
 * skip} in the runtime; this capability still no-ops those cases defensively.
 */
@ApplicationScoped
public class SpecificationImportCapability implements StageCapability {

  public static final String CAPABILITY_ID = "specification-import";

  public static final String IMPORT_CONFIRM_MESSAGE =
      "Import the API Hub specification into the runtime catalog before planning?";

  /**
   * Soft-recovery prompt after import failure (ADR 0001 decisions 7+9): candidate cleared,
   * {@code importIntent} kept; user re-captures refs then confirms again.
   */
  public static final String IMPORT_FAIL_SOFT_RECOVERY_MESSAGE =
      "API Hub specification import failed. The pending candidate was cleared; import intent is"
          + " kept. Re-capture the API Hub match, then confirm the import again.";

  private static final Logger LOG = Logger.getLogger(SpecificationImportCapability.class);

  private final CatalogMutationGateway catalogMutationGateway;
  private final RequirementDraftStore draftStore;
  private final ConversationCatalogCache catalogCache;
  private final org.qubership.integration.platform.ai.integration.catalog.ApiHubExistingCatalogBinder
      existingCatalogBinder;

  @Inject
  public SpecificationImportCapability(
      CatalogMutationGateway catalogMutationGateway,
      RequirementDraftStore draftStore,
      ConversationCatalogCache catalogCache,
      org.qubership.integration.platform.ai.integration.catalog.ApiHubExistingCatalogBinder
          existingCatalogBinder) {
    this.catalogMutationGateway =
        Objects.requireNonNull(catalogMutationGateway, "catalogMutationGateway");
    this.draftStore = Objects.requireNonNull(draftStore, "draftStore");
    this.catalogCache = catalogCache;
    this.existingCatalogBinder = existingCatalogBinder;
  }

  /** Test helper without catalog-first binder. */
  public SpecificationImportCapability(
      CatalogMutationGateway catalogMutationGateway,
      RequirementDraftStore draftStore,
      ConversationCatalogCache catalogCache) {
    this(catalogMutationGateway, draftStore, catalogCache, null);
  }

  @Override
  public String capabilityId() {
    return CAPABILITY_ID;
  }

  @Override
  public Multi<CapabilitySignal> execute(StageExecutionContext context) {
    Objects.requireNonNull(context, "context");
    RequirementDraft draft = resolveDraft(context);
    if (draft == null) {
      return completed(
          StageOutcome.of(
              StageOutcomeClass.MISSING_MANDATORY_INPUT,
              "requirement-draft is required for specification-import"));
    }

    if (shouldPassthrough(draft)) {
      return succeeded(draft, context, "specification-import skipped");
    }

    // Fail / cold soft-path: durable importIntent without a candidate waits for re-gather.
    if (draft.apiHubCandidate() == null) {
      return completed(
          StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, IMPORT_FAIL_SOFT_RECOVERY_MESSAGE));
    }

    String conversationId = context.conversationId();
    String userText = context.attributeAsString("userText");
    if (!isImportConfirm(userText)) {
      // User may say the service is already in the catalog — bind it instead of replaying Import.
      if (existingCatalogBinder != null && draft.apiHubCandidate() != null) {
        var existing =
            existingCatalogBinder.resolve(conversationId, draft.apiHubCandidate());
        if (existing.isPresent()) {
          ResolvedCatalogBinding binding =
              ResolvedCatalogBinding.enrichFromCache(
                  catalogCache, conversationId, existing.get());
          draftStore.applyImportResult(conversationId, binding);
          RequirementDraft updated =
              draftStore.get(conversationId).orElseGet(() -> draft.withCatalogBinding(binding));
          LOG.infof(
              "specification-import bound existing catalog conversationId=%s systemId=%s",
              conversationId, binding.systemId());
          return completed(
              new StageOutcome(
                  StageOutcomeClass.SUCCEEDED,
                  List.of(
                      new ArtifactCandidate(
                          CompilationArtifacts.Kind.REQUIREMENT_DRAFT,
                          updated,
                          context.inputRefs())),
                  "Bound existing catalog specification",
                  null));
        }
      }
      return completed(
          StageOutcome.of(StageOutcomeClass.NEEDS_INPUT, IMPORT_CONFIRM_MESSAGE));
    }

    ApiHubRequirementRefs refs = draft.apiHubCandidate();
    if (!refs.hasImportableRefs()) {
      return completed(
          StageOutcome.of(
              StageOutcomeClass.MISSING_MANDATORY_INPUT,
              "API Hub candidate refs are incomplete for import"));
    }

    SkillActivitySupport.bindParents(CAPABILITY_ID);
    return Multi.createBy()
        .concatenating()
        .streams(
            Multi.createFrom().item(SkillActivitySupport.running(CAPABILITY_ID)),
            Multi.createFrom()
                .uni(
                    catalogMutationGateway
                        .importApiHubSpecification(conversationId, refs)
                        .map(
                            result -> {
                              ResolvedCatalogBinding binding =
                                  ResolvedCatalogBinding.enrichFromCache(
                                      catalogCache,
                                      conversationId,
                                      ResolvedCatalogBinding.fromImportResult(result));
                              draftStore.applyImportResult(conversationId, binding);
                              RequirementDraft updated =
                                  draftStore
                                      .get(conversationId)
                                      .orElseGet(() -> draft.withCatalogBinding(binding));
                              LOG.infof(
                                  "specification-import succeeded conversationId=%s systemId=%s",
                                  conversationId, binding.systemId());
                              return (CapabilitySignal)
                                  new CapabilitySignal.Completed(
                                      new StageOutcome(
                                          StageOutcomeClass.SUCCEEDED,
                                          List.of(
                                              new ArtifactCandidate(
                                                  CompilationArtifacts.Kind.REQUIREMENT_DRAFT,
                                                  updated,
                                                  context.inputRefs())),
                                          "API Hub specification imported",
                                          null));
                            }))
                .onItem()
                .transformToMultiAndConcatenate(
                    completed ->
                        Multi.createFrom()
                            .iterable(
                                SkillActivitySupport.wrapTerminal(
                                    CAPABILITY_ID, List.of(completed))))
                .onFailure()
                .recoverWithMulti(
                    error -> {
                      LOG.errorf(
                          error, "specification-import failed conversationId=%s", conversationId);
                      draftStore.recordImportFailure(conversationId);
                      RequirementDraft afterFail =
                          draftStore.get(conversationId).orElseGet(draft::clearApiHubCandidate);
                      String detail =
                          error.getMessage() == null || error.getMessage().isBlank()
                              ? IMPORT_FAIL_SOFT_RECOVERY_MESSAGE
                              : IMPORT_FAIL_SOFT_RECOVERY_MESSAGE
                                  + " Cause: "
                                  + error.getMessage();
                      return Multi.createFrom()
                          .items(
                              SkillActivitySupport.error(CAPABILITY_ID),
                              new CapabilitySignal.Completed(
                                  new StageOutcome(
                                      StageOutcomeClass.NEEDS_INPUT,
                                      List.of(
                                          new ArtifactCandidate(
                                              CompilationArtifacts.Kind.REQUIREMENT_DRAFT,
                                              afterFail,
                                              context.inputRefs())),
                                      detail,
                                      null)));
                    }))
        .onTermination()
        .invoke((failure, cancelled) -> SkillActivitySupport.clearParents());
  }

  private RequirementDraft resolveDraft(StageExecutionContext context) {
    // Prefer the draft store: import success/fail mutates it in place (ADR 0001 SoT).
    RequirementDraft fromStore = draftStore.get(context.conversationId()).orElse(null);
    if (fromStore != null) {
      return fromStore;
    }
    Object approved = context.attributes().get("approvedDraft");
    if (approved instanceof RequirementDraft draft) {
      return draft;
    }
    return null;
  }

  /**
   * Skip only when binding already exists, or there is no candidate <em>and</em> no durable
   * import intent (no-external-API). Intent without a candidate is soft-wait, not skip (ADR
   * decisions 3 and 7).
   */
  private static boolean shouldPassthrough(RequirementDraft draft) {
    if (draft.catalogBinding() != null) {
      return true;
    }
    return draft.apiHubCandidate() == null && !draft.importIntent();
  }

  /** The reader answered the import card; the marker is this service's own, not the reader's. */
  private static boolean isImportConfirm(String userText) {
    return userText != null && userText.strip().startsWith(ChatEvent.IMPORT_MARKER);
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
