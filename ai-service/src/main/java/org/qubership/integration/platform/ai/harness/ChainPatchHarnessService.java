package org.qubership.integration.platform.ai.harness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.UUID;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.edit.ChainEditCompiler;
import org.qubership.integration.platform.ai.chain.edit.ChainEditOutcome;
import org.qubership.integration.platform.ai.chain.edit.ChainEditRequest;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.chain.patch.ChainEditProposalAssembler;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriteResult;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriter;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;

/**
 * Drives an existing-chain edit for a regression run, writing as soon as it compiles rather than
 * waiting on a decision card.
 *
 * <p>The same {@link ChainEditCompiler} and {@link ChainEditProposalAssembler} the interactive
 * scenario uses, minus the confirmation round trip: a regression run has no reader to answer a
 * card, and ADR 0001 places that gate on the interactive path, not on this one. Sharing both seams
 * is what keeps a change the harness passes from failing in front of a reader.
 */
@ApplicationScoped
public class ChainPatchHarnessService {

  private static final Logger LOG = Logger.getLogger(ChainPatchHarnessService.class);

  private final ChainCatalogFactsService factsService;
  private final ChainPlanGraphImporter importer;
  private final ChainEditCompiler editCompiler;
  private final ChainEditProposalAssembler assembler;
  private final ChainPatchWriter writer;

  @Inject
  public ChainPatchHarnessService(
      ChainCatalogFactsService factsService,
      ChainPlanGraphImporter importer,
      ChainEditCompiler editCompiler,
      ChainEditProposalAssembler assembler,
      ChainPatchWriter writer) {
    this.factsService = factsService;
    this.importer = importer;
    this.editCompiler = editCompiler;
    this.assembler = assembler;
    this.writer = writer;
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

    ChainEditOutcome outcome =
        editCompiler.compile(
            new ChainEditRequest(
                conversationId,
                chainId,
                conversationId + "-edit-" + UUID.randomUUID(),
                imported,
                prompt,
                null));
    if (!(outcome instanceof ChainEditOutcome.Proposal proposal)) {
      return failed(conversationId, describe(outcome), refusalOf(outcome));
    }

    ChainEditProposalAssembler.Assembled assembled =
        assembler.assemble(imported, chainId, proposal.netPatch(), allowRemoval);
    if (assembled instanceof ChainEditProposalAssembler.Assembled.Refused(String reason, var kind)) {
      return failed(conversationId, reason, refusalOf(kind));
    }
    ChainEditProposalAssembler.Assembled.Ready ready =
        (ChainEditProposalAssembler.Assembled.Ready) assembled;

    ChainPatchWriteResult result = writer.write(ready.patched(), ready.patch());
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

  private static String describe(ChainEditOutcome outcome) {
    return switch (outcome) {
      case ChainEditOutcome.Clarification(
          String question, List<String> choices, var ignoredHeldIntent, var ignoredContinuation) ->
          choices.isEmpty() ? question : question + " " + String.join("; ", choices);
      case ChainEditOutcome.ResolutionFailure(String message) -> message;
      case ChainEditOutcome.NoChange ignored -> "No chain change was requested.";
      case ChainEditOutcome.CompilationFailure(String message) -> message;
      case ChainEditOutcome.Escalation escalation -> escalation.message();
      case ChainEditOutcome.Unsupported(var action) ->
          "No compiler skill owns a " + action + " edit.";
      case ChainEditOutcome.Proposal ignored -> "";
    };
  }

  private static ChainPatchRefusal refusalOf(ChainEditOutcome outcome) {
    return switch (outcome) {
      case ChainEditOutcome.CompilationFailure ignored -> ChainPatchRefusal.STRUCTURAL;
      default -> ChainPatchRefusal.NONE;
    };
  }

  private static ChainPatchRefusal refusalOf(ChainEditProposalAssembler.ChainPatchRefusalKind kind) {
    return switch (kind) {
      case OWNERSHIP -> ChainPatchRefusal.OWNERSHIP;
      case SEMANTIC -> ChainPatchRefusal.SEMANTIC;
      case STRUCTURAL -> ChainPatchRefusal.STRUCTURAL;
    };
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
