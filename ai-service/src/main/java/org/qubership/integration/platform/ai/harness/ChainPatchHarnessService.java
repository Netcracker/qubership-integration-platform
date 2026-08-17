package org.qubership.integration.platform.ai.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Context;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchCapture;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchOwnership;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchPipeline;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchRemovalClosure;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchSemanticValidator;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchStore;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriteResult;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriter;
import org.qubership.integration.platform.ai.chain.patch.PatchedChain;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chat.ToolSession;
import org.qubership.integration.platform.ai.llm.agent.ChainPatchAgent;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchShapeValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;
import org.qubership.integration.platform.ai.schema.ChainElementCatalog;

/**
 * Drives the COMPARE_AND_PATCH pipeline against an existing catalog chain for a regression run,
 * applying the patch as soon as it validates rather than waiting on a decision card.
 *
 * <p>Same import-agent-capture-apply-write path {@code ChainPatchScenario} uses in production
 * (via {@link ChainPatchPipeline}), minus the confirmation round trip: a regression run has no
 * reader to answer a card, and ADR 0001 places that gate on the interactive path, not on this one.
 */
@ApplicationScoped
public class ChainPatchHarnessService {

  private static final Logger LOG = Logger.getLogger(ChainPatchHarnessService.class);

  private final ChainCatalogFactsService factsService;
  private final ChainPlanGraphImporter importer;
  private final ChainPatchAgent agent;
  private final ChainPatchStore patchStore;
  private final ChainPatchOwnership ownership;
  private final ValidatedGraphPatchApplier patchApplier;
  private final ChainPatchSemanticValidator semanticValidator;
  private final ChainPatchWriter writer;
  private final ChainElementCatalog elementCatalog;
  private final ObjectMapper objectMapper;

  @Inject
  public ChainPatchHarnessService(
      ChainCatalogFactsService factsService,
      ChainPlanGraphImporter importer,
      ChainPatchAgent agent,
      ChainPatchStore patchStore,
      ChainPatchOwnership ownership,
      ValidatedGraphPatchApplier patchApplier,
      ChainPatchSemanticValidator semanticValidator,
      ChainPatchWriter writer,
      ChainElementCatalog elementCatalog,
      ObjectMapper objectMapper) {
    this.factsService = factsService;
    this.importer = importer;
    this.agent = agent;
    this.patchStore = patchStore;
    this.ownership = ownership;
    this.patchApplier = patchApplier;
    this.semanticValidator = semanticValidator;
    this.writer = writer;
    this.elementCatalog = elementCatalog;
    this.objectMapper = objectMapper;
  }

  public ChainPatchHarnessResponse run(ChainPatchHarnessRequest request) {
    String conversationId = resolveConversationId(request.conversationId());
    String chainId = request.chainId().trim();
    try {
      return runPipeline(conversationId, chainId, request.prompt().trim(), request.allowRemoval());
    } catch (RuntimeException e) {
      LOG.errorf(
          e, "Chain patch harness run failed conversationId=%s chainId=%s", conversationId, chainId);
      return failed(conversationId, failureMessage(e), ChainPatchRefusal.WRITE);
    }
  }

  private ChainPatchHarnessResponse runPipeline(
      String conversationId, String chainId, String prompt, boolean allowRemoval) {
    ImportedChainPlan imported = importer.importChain(factsService.load(chainId));

    // Cleared first so a run whose model proposes nothing cannot pick up a stale capture.
    patchStore.takeCapture(conversationId);

    // ChainPatchTool reads the conversation id from ambient ToolSession state, not a method
    // parameter -- the interactive path binds it in ChatExecutionService before the agent runs;
    // this one-shot path has to bind it itself, and propagate it across whatever thread the
    // reactive tool-call machinery resumes on.
    List<String> tokens;
    try (ToolSession.Handle handle = ToolSession.open(conversationId)) {
      Context toolSessionContext = ToolSession.attachedContext();
      tokens =
          ToolSession.propagateBinding(
                  toolSessionContext,
                  agent.chat(
                      conversationId,
                      ChainPatchPipeline.buildPatchRequest(
                          objectMapper, imported.graph(), prompt, elementCatalog)))
              .collect()
              .asList()
              .await()
              .indefinitely();
    }

    Optional<ChainPatchCapture> captured = patchStore.takeCapture(conversationId);
    if (captured.isEmpty()) {
      String said = String.join("", tokens);
      return failed(
          conversationId, said.isBlank() ? "No patch proposed." : said, ChainPatchRefusal.NONE);
    }

    GraphPatch proposed = ChainPatchPipeline.toGraphPatch(captured.get(), imported.graph());
    List<String> shapeErrors = GraphPatchShapeValidator.validate(proposed);
    if (!shapeErrors.isEmpty()) {
      return failed(
          conversationId,
          GraphPatchShapeValidator.summarize(shapeErrors),
          ChainPatchRefusal.STRUCTURAL);
    }

    ChainPatchRemovalClosure.Expansion expansion =
        ChainPatchRemovalClosure.expand(imported.graph(), proposed);
    if (!expansion.coherent()) {
      return failed(
          conversationId,
          "The change contradicts itself: " + String.join("; ", expansion.conflicts()),
          ChainPatchRefusal.STRUCTURAL);
    }
    GraphPatch patch = expansion.patch();

    GraphPatchApplyResult applied =
        patchApplier.apply(
            ChainPatchPipeline.executionContext(imported, chainId, patch, ownership, allowRemoval),
            patch);
    if (!applied.applied()) {
      String summary = applied.validationResult().summary();
      boolean ownershipViolation = ChainPatchPipeline.isOwnershipViolation(applied);
      String message =
          ownershipViolation
              ? "Outside what this skill may edit: " + summary
              : "The change could not be applied: " + summary;
      return failed(
          conversationId,
          message,
          ownershipViolation ? ChainPatchRefusal.OWNERSHIP : ChainPatchRefusal.STRUCTURAL);
    }

    List<String> introduced =
        semanticValidator.introducedProblems(imported.graph(), applied.graph(), patch);
    if (!introduced.isEmpty()) {
      return failed(
          conversationId,
          "The change would leave the chain broken: " + String.join("; ", introduced),
          ChainPatchRefusal.SEMANTIC);
    }

    PatchedChain patched =
        new PatchedChain(imported.graph(), applied.graph(), imported.materializationMap());
    ChainPatchWriteResult result = writer.write(patched, patch);
    List<String> changedElementIds = result.changedCatalogElementIds();
    List<String> failedElementIds = result.failedCatalogElementIds();

    List<String> removedElementIds = result.removedElementIds();

    if (result.succeeded()) {
      return new ChainPatchHarnessResponse(
          conversationId,
          SkillHarnessStatus.COMPLETED,
          "Changed "
              + changedElementIds.size()
              + " element(s), removed "
              + removedElementIds.size()
              + ".",
          ChainPatchRefusal.NONE,
          changedElementIds,
          failedElementIds,
          removedElementIds);
    }
    String message = result.error() != null ? result.error() : "Some elements could not be changed.";
    return new ChainPatchHarnessResponse(
        conversationId,
        SkillHarnessStatus.FAILED,
        message,
        ChainPatchRefusal.WRITE,
        changedElementIds,
        failedElementIds,
        removedElementIds,
        result.rollback());
  }

  private static ChainPatchHarnessResponse failed(
      String conversationId, String message, ChainPatchRefusal refusal) {
    return new ChainPatchHarnessResponse(
        conversationId, SkillHarnessStatus.FAILED, message, refusal, List.of(), List.of());
  }

  private static String resolveConversationId(String conversationId) {
    if (conversationId == null || conversationId.isBlank()) {
      return UUID.randomUUID().toString();
    }
    return conversationId.trim();
  }

  private static String failureMessage(Exception e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return message;
  }
}
