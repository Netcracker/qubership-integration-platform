package org.qubership.integration.platform.ai.llm.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chain.imports.ChainPlanGraphImporter;
import org.qubership.integration.platform.ai.chain.imports.ImportedChainPlan;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchCapture;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchOwnership;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchPipeline;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchStore;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchSummary;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriteResult;
import org.qubership.integration.platform.ai.chain.patch.ChainPatchWriter;
import org.qubership.integration.platform.ai.chain.patch.PatchedChain;
import org.qubership.integration.platform.ai.chain.patch.ProposedChainPatch;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFactsService;
import org.qubership.integration.platform.ai.chain.presentation.ChainContextExtractor;
import org.qubership.integration.platform.ai.chat.ChatEvent;
import org.qubership.integration.platform.ai.chat.model.ChatDecisionCommand;
import org.qubership.integration.platform.ai.chat.model.ChatRequest;
import org.qubership.integration.platform.ai.llm.agent.ChainPatchAgent;
import org.qubership.integration.platform.ai.model.ScenarioType;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchApplyResult;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchShapeValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.ValidatedGraphPatchApplier;

/**
 * Changes part of a chain the user already has in the catalog.
 *
 * <p>Reads the chain, asks the model for a patch against it, and offers that patch as a decision
 * card. Nothing reaches the catalog until the reader answers the card, and what is written is
 * bounded twice: by the ownership policy, which holds whatever the reader answered, and by the
 * writer, which sends only the elements the patch names.
 */
@ApplicationScoped
@ForScenario(ScenarioType.COMPARE_AND_PATCH)
public class ChainPatchScenario implements ScenarioHandler {

  private static final Logger LOG = Logger.getLogger(ChainPatchScenario.class);

  private final ChainContextExtractor chainContextExtractor;
  private final ChainCatalogFactsService factsService;
  private final ChainPlanGraphImporter importer;
  private final ChainPatchAgent agent;
  private final ChainPatchStore patchStore;
  private final ChainPatchOwnership ownership;
  private final ValidatedGraphPatchApplier patchApplier;
  private final ChainPatchWriter writer;
  private final CanonicalGraphDigest canonicalGraphDigest;
  private final ObjectMapper objectMapper;

  @Inject
  public ChainPatchScenario(
      ChainContextExtractor chainContextExtractor,
      ChainCatalogFactsService factsService,
      ChainPlanGraphImporter importer,
      ChainPatchAgent agent,
      ChainPatchStore patchStore,
      ChainPatchOwnership ownership,
      ValidatedGraphPatchApplier patchApplier,
      ChainPatchWriter writer,
      CanonicalGraphDigest canonicalGraphDigest,
      ObjectMapper objectMapper) {
    this.chainContextExtractor = Objects.requireNonNull(chainContextExtractor);
    this.factsService = Objects.requireNonNull(factsService);
    this.importer = Objects.requireNonNull(importer);
    this.agent = Objects.requireNonNull(agent);
    this.patchStore = Objects.requireNonNull(patchStore);
    this.ownership = Objects.requireNonNull(ownership);
    this.patchApplier = Objects.requireNonNull(patchApplier);
    this.writer = Objects.requireNonNull(writer);
    this.canonicalGraphDigest = Objects.requireNonNull(canonicalGraphDigest);
    this.objectMapper = Objects.requireNonNull(objectMapper);
  }

  @Override
  public Multi<ChatEvent> handle(
      ChatRequest request, String conversationId, ScenarioType scenarioType) {
    ChatDecisionCommand decision = request == null ? null : request.getDecision();
    if (decision != null && ChatEvent.APPLY_CHAIN_PATCH_ACTION.equals(decision.getAction())) {
      return applyAnsweredPatch(conversationId, decision);
    }
    return proposePatch(request, conversationId);
  }

  private Multi<ChatEvent> proposePatch(ChatRequest request, String conversationId) {
    String chainId = chainContextExtractor.resolveChainId(request, conversationId).orElse(null);
    if (chainId == null) {
      return message(
          "No chain context found. Open the chain you want to change, then say what to change.");
    }

    ImportedChainPlan imported;
    try {
      ChainCatalogFacts facts = factsService.load(chainId);
      imported = importer.importChain(facts);
    } catch (RuntimeException e) {
      LOG.errorf(e, "Chain read failed conversationId=%s chainId=%s", conversationId, chainId);
      return Multi.createFrom()
          .item(ChatEvent.error("Failed to read chain from catalog: " + e.getMessage()));
    }

    // The capture is cleared first so a turn that proposes nothing cannot answer with the last one.
    patchStore.takeCapture(conversationId);
    patchStore.clearProposal(conversationId);

    String userMessage = request == null ? "" : request.getEffectiveUserText();
    return agent
        .chat(
            conversationId,
            ChainPatchPipeline.buildPatchRequest(objectMapper, imported.graph(), userMessage))
        .collect()
        .asList()
        .onItem()
        .transformToMulti(
            said -> proposalFrom(conversationId, chainId, imported, String.join("", said)))
        .onFailure()
        .recoverWithMulti(
            e -> {
              LOG.errorf(e, "Chain patch agent failed conversationId=%s", conversationId);
              return Multi.createFrom().item(ChatEvent.error("Failed to plan the change: " + e.getMessage()));
            });
  }

  private Multi<ChatEvent> proposalFrom(
      String conversationId, String chainId, ImportedChainPlan imported, String said) {
    Optional<ChainPatchCapture> captured = patchStore.takeCapture(conversationId);
    if (captured.isEmpty()) {
      // The model resolves the target element itself. When a description fits several elements or
      // none, it names the candidates or asks for an exact name instead of calling the tool, and
      // that question is what the reader has to see to answer it.
      return message(
          said.isBlank()
              ? "I read the chain but proposed no change. Say which element to change and how."
              : said);
    }

    GraphPatch patch = ChainPatchPipeline.toGraphPatch(captured.get());
    List<String> shapeErrors = GraphPatchShapeValidator.validate(patch);
    if (!shapeErrors.isEmpty()) {
      return message("The change could not be read: " + GraphPatchShapeValidator.summarize(shapeErrors));
    }

    GraphPatchApplyResult applied =
        patchApplier.apply(
            ChainPatchPipeline.executionContext(imported, chainId, patch, ownership), patch);
    if (!applied.applied()) {
      return message("That change is outside what I may edit here: " + applied.validationResult().summary());
    }

    PatchedChain patched = new PatchedChain(applied.graph(), imported.materializationMap());
    String patchHash = canonicalGraphDigest.sha256(applied.graph());
    String summary = ChainPatchSummary.describe(imported.graph(), patch);
    patchStore.putProposal(
        conversationId,
        new ProposedChainPatch(
            chainId, patch, patched, patchHash, imported.baseGraphDigest(), summary));

    return Multi.createFrom().item(ChatEvent.chainPatchDecision(patchHash, summary));
  }

  private Multi<ChatEvent> applyAnsweredPatch(String conversationId, ChatDecisionCommand decision) {
    Optional<ProposedChainPatch> proposal = patchStore.findProposal(conversationId);
    if (proposal.isEmpty() || !proposal.get().patchHash().equals(decision.getArtifactHash())) {
      return message("That change is no longer the one on offer. Ask for the change again.");
    }

    ProposedChainPatch proposed = proposal.get();
    // The chain is read again rather than trusted: between the card and the answer someone may have
    // edited the very element this patch rewrites, and a merge would drop their work silently.
    String currentDigest;
    try {
      currentDigest = importer.importChain(factsService.load(proposed.chainId())).baseGraphDigest();
    } catch (RuntimeException e) {
      LOG.errorf(e, "Chain re-read failed conversationId=%s chainId=%s", conversationId, proposed.chainId());
      return Multi.createFrom()
          .item(ChatEvent.error("Failed to read chain from catalog: " + e.getMessage()));
    }
    if (!currentDigest.equals(proposed.baseGraphDigest())) {
      patchStore.clearProposal(conversationId);
      return message(
          "The chain changed since I proposed this, so I did not write anything."
              + " Ask for the change again against the chain as it stands now.");
    }

    patchStore.clearProposal(conversationId);
    ChainPatchWriteResult result = writer.write(proposed.patched(), proposed.patch());
    return message(describe(result, proposed));
  }

  private String describe(ChainPatchWriteResult result, ProposedChainPatch proposed) {
    List<String> changed =
        result.changedElementIds().stream()
            .map(elementId -> elementName(proposed.patched().graph(), elementId))
            .toList();
    if (result.succeeded()) {
      return changed.isEmpty()
          ? "Nothing needed changing."
          : "Changed " + String.join(", ", changed) + " in the chain.";
    }
    List<String> failed =
        result.failedElementIds().stream()
            .map(elementId -> elementName(proposed.patched().graph(), elementId))
            .toList();
    StringBuilder text = new StringBuilder();
    if (!changed.isEmpty()) {
      text.append("Changed ").append(String.join(", ", changed)).append(". ");
    }
    text.append("Could not change ").append(String.join(", ", failed)).append(".");
    if (result.error() != null) {
      text.append(" ").append(result.error());
    }
    return text.toString();
  }

  private static String elementName(ChainPlanGraph graph, String nodeId) {
    return graph.nodes().stream()
        .filter(node -> nodeId.equals(node.nodeId()))
        .map(node -> node.label() == null || node.label().isBlank() ? node.nodeId() : node.label())
        .findFirst()
        .orElse(nodeId);
  }

  private static Multi<ChatEvent> message(String text) {
    return Multi.createFrom().item(ChatEvent.token(text));
  }
}
