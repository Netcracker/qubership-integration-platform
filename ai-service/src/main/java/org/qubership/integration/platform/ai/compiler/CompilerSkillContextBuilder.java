package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.chat.evidence.EvidenceEmitter;
import org.qubership.integration.platform.ai.compiler.addon.AddonPromptMaterialStripper;
import org.qubership.integration.platform.ai.compiler.addon.CaptureTool;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonContext;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonDocument;
import org.qubership.integration.platform.ai.compiler.addon.CompilerSkillAddonRepository;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlan;
import org.qubership.integration.platform.ai.compiler.plan.GeneratorPlanManifest;
import org.qubership.integration.platform.ai.compiler.policy.CompilerGeneratorSpec;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageArtifact;
import org.qubership.integration.platform.ai.compiler.runtimepkg.CompilerRuntimePackageIndex;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.SelectedPattern;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBriefText;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeClient;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextPackage;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextProvider;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgeContextRequest;
import org.qubership.integration.platform.ai.productpipeline.knowledge.KnowledgePackageRef;
import org.qubership.integration.platform.ai.productpipeline.knowledge.CanonicalKnowledgeObject;
import org.qubership.integration.platform.ai.qipknowledge.pack.QipKnowledgePackRepository;
import org.qubership.integration.platform.ai.qipknowledge.skill.QipKnowledgeCapabilityPhase;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactPayload;
import org.qubership.integration.platform.ai.skill.workspace.SkillArtifactType;
import org.qubership.integration.platform.ai.skill.workspace.SkillWorkspace;

/** Builds user messages and input snapshots for compiler skill agents. */
@ApplicationScoped
public class CompilerSkillContextBuilder {

  private final ObjectMapper objectMapper;
  private final QipKnowledgePackRepository repository;
  private final CompilerSkillAddonRepository addonRepository;
  private final CompilerSkillRuntimeEligibility runtimeEligibility;
  private final KnowledgeClient knowledgeClient;
  private final KnowledgeContextProvider knowledgeContextProvider;
  private final EvidenceEmitter evidenceEmitter;

  @Inject
  public CompilerSkillContextBuilder(
      ObjectMapper objectMapper,
      QipKnowledgePackRepository repository,
      CompilerSkillAddonRepository addonRepository,
      CompilerSkillRuntimeEligibility runtimeEligibility,
      KnowledgeClient knowledgeClient,
      KnowledgeContextProvider knowledgeContextProvider,
      EvidenceEmitter evidenceEmitter) {
    this.objectMapper = objectMapper;
    this.repository = repository;
    this.addonRepository = addonRepository;
    this.runtimeEligibility = runtimeEligibility;
    this.knowledgeClient = Objects.requireNonNull(knowledgeClient, "knowledgeClient");
    this.knowledgeContextProvider =
        Objects.requireNonNull(knowledgeContextProvider, "knowledgeContextProvider");
    this.evidenceEmitter = evidenceEmitter;
  }

  /** Test constructor with an explicit knowledge client and context provider. */
  public CompilerSkillContextBuilder(
      ObjectMapper objectMapper,
      QipKnowledgePackRepository repository,
      CompilerSkillAddonRepository addonRepository,
      CompilerSkillRuntimeEligibility runtimeEligibility,
      KnowledgeClient knowledgeClient,
      KnowledgeContextProvider knowledgeContextProvider) {
    this(
        objectMapper,
        repository,
        addonRepository,
        runtimeEligibility,
        knowledgeClient,
        knowledgeContextProvider,
        null);
  }

  public CompilerSkillInputSnapshot snapshotFromWorkspace(SkillWorkspace workspace) {
    return new CompilerSkillInputSnapshot(
        readRawUserRequest(workspace),
        readRequirementBrief(workspace),
        readSelectedPatternText(workspace),
        readGraph(workspace),
        readGeneratorPlanManifestSummary(workspace),
        ChainEditSkillContext.render(workspace));
  }

  public String buildUserMessage(CompilerSkillDocument document, CompilerSkillInputSnapshot snapshot) {
    return buildUserMessage(null, document, snapshot, null);
  }

  public String buildUserMessage(
      String conversationId,
      CompilerSkillDocument document,
      CompilerSkillInputSnapshot snapshot,
      GeneratorPlan activePlan) {
    return buildUserMessage(conversationId, document, snapshot, activePlan, defaultCaptureTool(document));
  }

  public String buildUserMessage(
      String conversationId,
      CompilerSkillDocument document,
      CompilerSkillInputSnapshot snapshot,
      GeneratorPlan activePlan,
      CaptureTool captureTool) {
    runtimeEligibility.requirePromptContext(document.capabilityId());

    StringBuilder body = new StringBuilder();
    appendPipelineContract(body, document, captureTool);
    appendCatalogContext(body, document);

    body.append("User request:\n").append(nullToEmpty(snapshot.rawUserRequest())).append("\n\n");
    body.append("Requirement brief:\n").append(nullToEmpty(snapshot.requirementBrief())).append("\n\n");
    if (!nullToEmpty(snapshot.selectedPatternId()).isBlank()) {
      body.append("Selected golden pattern:\n").append(snapshot.selectedPatternId()).append("\n\n");
    }
    if (!nullToEmpty(snapshot.mappingGenerationContext()).isBlank()) {
      body.append(snapshot.mappingGenerationContext()).append("\n\n");
    }

    boolean editStructureCapture =
        captureTool == CaptureTool.CAPTURE_CHAIN_STRUCTURE
            && snapshot.editContext() != null
            && !snapshot.editContext().isBlank();
    if (document.phase() == QipKnowledgeCapabilityPhase.GENERATOR
        || document.phase() == QipKnowledgeCapabilityPhase.VALIDATOR
        || editStructureCapture) {
      body.append("Current ChainPlanGraph JSON:\n");
      body.append(
              formatGraph(
                  withTruncatedScriptBodies(snapshot.chainPlanGraph(), document.capabilityId())))
          .append("\n\n");

      if (document.phase() == QipKnowledgeCapabilityPhase.GENERATOR && activePlan != null) {
        body.append("Active generator plan slice:\n");
        body.append(formatActivePlan(activePlan)).append("\n\n");
      }
      if (document.phase() == QipKnowledgeCapabilityPhase.GENERATOR
          && snapshot.editContext() != null
          && !snapshot.editContext().isBlank()) {
        body.append(snapshot.editContext()).append("\n\n");
      }
      if (editStructureCapture) {
        body.append(snapshot.editContext()).append("\n\n");
      }
    }

    if (document.phase() == QipKnowledgeCapabilityPhase.VALIDATOR
        && snapshot.generatorPlanManifestSummary() != null
        && !snapshot.generatorPlanManifestSummary().isBlank()) {
      body.append("Generator plan manifest:\n");
      body.append(snapshot.generatorPlanManifestSummary()).append("\n\n");
    }

    appendAddonSections(body, document);

    body.append("Compiler skill document (").append(document.sourcePath()).append("):\n");
    body.append(document.markdown());
    appendRuntimeContextPackage(body, conversationId, document, snapshot);
    appendFinalEditConstraint(body, document, snapshot);

    return QuteUserMessageEscaping.escapeForAiServiceUserMessage(body.toString());
  }

  /**
   * Renders the mapping generator prompt section. Task 13 stores the result in
   * {@link CompilerSkillInputSnapshot#mappingGenerationContext()}.
   */
  public String renderMappingGenerationContext(
      MappingIntent intent,
      MappingEnvelope envelope,
      MappingSchemaSide sourceSide,
      MappingSchemaSide targetSide) {
    Objects.requireNonNull(intent, "intent");
    Objects.requireNonNull(envelope, "envelope");
    Objects.requireNonNull(sourceSide, "sourceSide");
    Objects.requireNonNull(targetSide, "targetSide");

    StringBuilder section = new StringBuilder();
    section.append("Mapping generation:\n\n");
    section.append("mappingIntentId: ").append(intent.mappingIntentId()).append('\n');
    section
        .append("source: ")
        .append(intent.sourceRef())
        .append(' ')
        .append(intent.sourcePort())
        .append('\n');
    section
        .append("target: ")
        .append(intent.targetRef())
        .append(' ')
        .append(intent.targetPort())
        .append("\n\n");

    section.append("Approved rules:\n");
    for (MappingIntentRule rule : intent.rules()) {
      section.append("- ").append(rule.sourcePath()).append(" -> ").append(rule.targetPath());
      if (rule.expression() != null && !rule.expression().isBlank()) {
        section.append(" (expression: ").append(rule.expression()).append(')');
      }
      section.append(" [").append(rule.status()).append("]\n");
    }
    section.append('\n');

    section.append("Schema artifact hashes:\n");
    section.append("- source: ").append(sourceSide.sha256()).append('\n');
    section.append("- target: ").append(targetSide.sha256()).append("\n\n");

    section.append("Envelope idToPath:\n");
    section.append(formatJson(envelope.idToPath())).append("\n\n");

    section.append("Frozen envelope (source and target only):\n");
    section.append(formatEnvelopeSourceTarget(envelope)).append("\n\n");

    section.append(
        "Encode only the approved rules above. Extra correspondences fail parity validation. "
            + "Copy source and target from the frozen envelope unchanged; they must be copied "
            + "unchanged in capture.");

    return section.toString();
  }

  public String buildScriptRepairMessage(
      String conversationId,
      CompilerSkillDocument document,
      CompilerSkillInputSnapshot snapshot,
      List<String> missingNodeIds) {
    runtimeEligibility.requirePromptContext(document.capabilityId());

    StringBuilder body = new StringBuilder();
    body.append("Pipeline instruction:\n");
    body.append(
        "You are executing compiler skill '"
            + document.capabilityId()
            + "' in an automated pipeline.\n");
    body.append("Call repairScriptBodies exactly once before you finish.\n");
    boolean mappingTurn = !nullToEmpty(snapshot.mappingGenerationContext()).isBlank();
    if (mappingTurn) {
      body.append("Submit script bodies and mappingCoverage for mapping nodes.\n");
    } else {
      body.append("Submit only script bodies for the listed targetNodeIds.\n");
    }
    body.append("Do not call captureGraphPatch.\n\n");
    body.append("User request:\n").append(nullToEmpty(snapshot.rawUserRequest())).append("\n\n");
    body.append("Requirement brief:\n").append(nullToEmpty(snapshot.requirementBrief())).append("\n\n");
    if (!nullToEmpty(snapshot.selectedPatternId()).isBlank()) {
      body.append("Selected golden pattern:\n").append(snapshot.selectedPatternId()).append("\n\n");
    }
    if (!nullToEmpty(snapshot.mappingGenerationContext()).isBlank()) {
      body.append(snapshot.mappingGenerationContext()).append("\n\n");
    }
    body.append("Missing script node ids:\n");
    for (String nodeId : missingNodeIds) {
      body.append("- ").append(nodeId).append('\n');
    }
    body.append("\nCall repairScriptBodies with this exact shape (fill each listed id):\n");
    body.append("{\n");
    body.append("  \"patchId\": \"script-body\",\n");
    body.append("  \"scripts\": [\n");
    for (int i = 0; i < missingNodeIds.size(); i++) {
      String nodeId = missingNodeIds.get(i);
      body.append("    { \"targetNodeId\": \"")
          .append(nodeId)
          .append("\", \"script\": \"exchange.in.body = 'Hello'\\nreturn exchange.in.body\"");
      if (mappingTurn) {
        body.append(", \"mappingCoverage\": [\"$.targetPath\"]");
      }
      body.append(" }");
      if (i + 1 < missingNodeIds.size()) {
        body.append(',');
      }
      body.append('\n');
    }
    body.append("  ],\n");
    body.append("  \"rationale\": \"Filled missing script bodies for listed node ids\"\n");
    body.append("}\n");
    body.append("Do not call with null/empty scripts. Do not invent extra node ids.\n");
    if (mappingTurn) {
      body.append("Set mappingCoverage to the approved target paths.\n");
    }
    body.append('\n');
    body.append("Script node slice:\n");
    appendScriptNodeSlice(body, snapshot.chainPlanGraph(), missingNodeIds);
    body.append('\n');
    appendAddonSections(body, document);
    appendRuntimeContextPackage(body, conversationId, document, snapshot);
    return QuteUserMessageEscaping.escapeForAiServiceUserMessage(body.toString());
  }

  private void appendRuntimeContextPackage(
      StringBuilder body,
      String conversationId,
      CompilerSkillDocument document,
      CompilerSkillInputSnapshot snapshot) {
    KnowledgeContextPackage contextPackage =
        knowledgeClient.context(
            knowledgeContextProvider.forConversation(conversationId),
            new KnowledgeContextRequest(
                snapshot.rawUserRequest(),
                document.capabilityId(),
                document.phase().name(),
                activeElementTypes(snapshot.chainPlanGraph()),
                12,
                20_000));
    KnowledgePackageRef ref = contextPackage.identity().packageRef();
    body.append("\n\n").append(contextPackage.renderMarkdown());

    if (evidenceEmitter != null && conversationId != null) {
      evidenceEmitter.knowledge(
          conversationId,
          ref,
          contextPackage.objects().stream().map(CanonicalKnowledgeObject::id).toList(),
          contextPackage.contentChars());
    }
  }

  private static List<String> activeElementTypes(ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) {
      return List.of();
    }
    return graph.nodes().stream()
        .map(node -> node.type())
        .filter(Objects::nonNull)
        .map(String::trim)
        .filter(type -> !type.isEmpty())
        .distinct()
        .sorted()
        .toList();
  }

  private void appendPipelineContract(
      StringBuilder body, CompilerSkillDocument document, CaptureTool captureTool) {
    body.append("Pipeline instruction:\n");
    body.append(
        "You are executing compiler skill '"
            + document.capabilityId()
            + "' in an automated pipeline.\n");
    body.append(
        "Follow the compiler skill addon for this-turn capture steps and skill-specific rules."
            + " Upstream SKILL.md below owns domain behavior.\n");

    switch (document.phase()) {
      case DISCOVERY -> {
        body.append(
            "Call "
                + captureTool.toolName()
                + " in this turn before you finish.\n");
        body.append("Do not call captureChainPlan or captureGraphPatch.\n");
      }
      case GRAPH_CONSTRUCTION -> {
        if (captureTool == CaptureTool.CAPTURE_CHAIN_STRUCTURE) {
          body.append(
              "Call "
                  + captureTool.toolName()
                  + " with a typed ChainStructure object in this turn before you finish.\n");
        } else {
          body.append(
              "Call "
                  + captureTool.toolName()
                  + " with the typed graph object in this turn before you finish.\n");
        }
        body.append("Follow the compiler skill addon for skeleton topology and examples.\n");
        body.append("Do not call captureGraphPatch.\n");
      }
      case VALIDATOR -> {
        body.append(
            "Call "
                + captureTool.toolName()
                + " with a typed validation report in this turn before you finish.\n");
        body.append("Follow the compiler skill addon for validation scope and severity.\n");
        body.append("Do not call captureChainPlan or captureGraphPatch.\n");
      }
      default -> {
        body.append(
            "Call "
                + captureTool.toolName()
                + " with a typed GraphPatch object in this turn before you finish.\n");
        body.append(
            "Follow the compiler skill addon and global graph-patch-contract for applicability,"
                + " empty-patch rules, and patch mapping. The addon is authoritative for"
                + " skill-specific behavior.\n");
        body.append("ownerCapabilityId must be '").append(document.capabilityId()).append("'.\n");
      }
    }

    body.append(
        "Do not ask the user to confirm; downstream skills run automatically.\n\n");
  }

  private static CaptureTool defaultCaptureTool(CompilerSkillDocument document) {
    return switch (document.phase()) {
      case DISCOVERY -> CaptureTool.CAPTURE_REQUIREMENT_BRIEF;
      case GRAPH_CONSTRUCTION -> CaptureTool.CAPTURE_CHAIN_PLAN;
      case VALIDATOR -> CaptureTool.CAPTURE_VALIDATION_RESULT;
      default -> CaptureTool.CAPTURE_GRAPH_PATCH;
    };
  }

  private void appendCatalogContext(StringBuilder body, CompilerSkillDocument document) {
    repository
        .loadCompilerSkillCatalog()
        .find(document.capabilityId())
        .ifPresent(
            descriptor -> {
              body.append("Compiler skill catalog entry:\n");
              body.append("- disposition: ").append(descriptor.disposition()).append("\n");
              if (descriptor.category() != null && !descriptor.category().isBlank()) {
                body.append("- category: ").append(descriptor.category()).append("\n");
              }
              if (!descriptor.dependsOn().isEmpty()) {
                body.append("- depends-on: ").append(String.join(", ", descriptor.dependsOn())).append("\n");
              }
              body.append("\n");
            });

    repository
        .loadCompilerGeneratorSpecIndex()
        .findBySkillName(document.capabilityId())
        .filter(CompilerGeneratorSpec::hasGeneratorId)
        .ifPresent(
            spec -> {
              body.append("Generator specification:\n");
              body.append("- generator-id: ").append(spec.generatorId()).append("\n");
              if (spec.compilerStage() != null && !spec.compilerStage().isBlank()) {
                body.append("- compiler-stage: ").append(spec.compilerStage()).append("\n");
              }
              body.append("\n");
            });

    if ("cip-chain-generator".equals(document.capabilityId())) {
      appendRuntimePackageIndex(body);
    }
  }

  private void appendRuntimePackageIndex(StringBuilder body) {
    CompilerRuntimePackageIndex runtimePackageIndex = repository.loadCompilerRuntimePackageIndex();
    if (runtimePackageIndex.artifacts().isEmpty()) {
      return;
    }
    body.append("Compiler runtime package index:\n");
    for (CompilerRuntimePackageArtifact artifact : runtimePackageIndex.artifacts()) {
      body.append("- ")
          .append(artifact.artifactType())
          .append(": ")
          .append(artifact.path())
          .append("\n");
    }
    body.append("\n");
  }

  private static void appendFinalEditConstraint(
      StringBuilder body, CompilerSkillDocument document, CompilerSkillInputSnapshot snapshot) {
    if (snapshot.editContext() == null || snapshot.editContext().isBlank()) {
      return;
    }
    if (document.phase() != QipKnowledgeCapabilityPhase.GENERATOR
        && !"cip-structure-generator".equals(document.capabilityId())) {
      return;
    }
    body.append("\nFinal edit constraint (overrides earlier topology advice):\n");
    body.append(snapshot.editContext()).append('\n');
  }

  private void appendAddonSections(StringBuilder body, CompilerSkillDocument document) {
    CompilerSkillAddonContext addon = addonRepository.loadForSkill(document.capabilityId());
    if (!addon.hasContent()) {
      return;
    }

    for (CompilerSkillAddonDocument addonDocument : addon.globalDocuments()) {
      body.append("ai-service runtime addon (")
          .append(addonDocument.relativePath())
          .append("):\n");
      body.append(addonDocument.content()).append("\n\n");
    }

    if (addon.skillAddon() != null) {
      String promptMaterial =
          AddonPromptMaterialStripper.stripForPrompt(addon.skillAddon().content());
      if (!promptMaterial.isBlank()) {
        body.append("Compiler skill addon (")
            .append(addon.skillAddon().relativePath())
            .append("):\n");
        body.append(promptMaterial).append("\n\n");
      }
    }

    for (CompilerSkillAddonDocument example : addon.examples()) {
      String label =
          document.phase() == QipKnowledgeCapabilityPhase.GENERATOR
              ? "GraphPatch example"
              : "Example";
      body.append(label).append(" (").append(example.relativePath()).append("):\n");
      body.append(example.content()).append("\n\n");
    }
  }

  private static ChainPlanGraph withTruncatedScriptBodies(ChainPlanGraph graph, String capabilityId) {
    if (graph == null
        || graph.nodes() == null
        || ScriptBodyPromptRedaction.SCRIPT_GENERATOR_CAPABILITY.equals(capabilityId)) {
      return graph;
    }
    java.util.List<ChainPlanNode> redactedNodes =
        graph.nodes().stream().map(ScriptBodyPromptRedaction::stripScriptBodyProperty).toList();
    return new ChainPlanGraph(graph.schemaVersion(), graph.chain(), redactedNodes, graph.edges());
  }

  private String formatEnvelopeSourceTarget(MappingEnvelope envelope) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("source", envelope.source());
    payload.put("target", envelope.target());
    return formatJson(payload);
  }

  private String formatJson(Object value) {
    if (value == null) {
      return "(missing)";
    }
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      return "(failed to serialize mapping context: " + e.getMessage() + ")";
    }
  }

  private String formatActivePlan(GeneratorPlan activePlan) {
    try {
      return objectMapper.writeValueAsString(activePlan);
    } catch (JsonProcessingException e) {
      return "(failed to serialize active generator plan: " + e.getMessage() + ")";
    }
  }

  private String formatGraph(ChainPlanGraph graph) {
    if (graph == null) {
      return "(missing)";
    }
    try {
      return objectMapper.writeValueAsString(graph);
    } catch (JsonProcessingException e) {
      return "(failed to serialize graph: " + e.getMessage() + ")";
    }
  }

  private static void appendScriptNodeSlice(
      StringBuilder body, ChainPlanGraph graph, List<String> missingNodeIds) {
    if (graph == null || graph.nodes() == null || graph.nodes().isEmpty()) {
      body.append("(missing)\n");
      return;
    }
    for (ChainPlanNode node : graph.nodes()) {
      if (!"script".equals(node.type()) || !missingNodeIds.contains(node.nodeId())) {
        continue;
      }
      body.append("- nodeId: ").append(node.nodeId()).append('\n');
      body.append("  type: ").append(nullToEmpty(node.type())).append('\n');
      body.append("  label: ").append(nullToEmpty(node.label())).append('\n');
      body.append("  parentNodeId: ").append(nullToEmpty(node.parentNodeId())).append('\n');
      body.append("  parentType: ").append(parentType(graph, node.parentNodeId())).append('\n');
    }
  }

  private static String parentType(ChainPlanGraph graph, String parentNodeId) {
    if (parentNodeId == null || parentNodeId.isBlank() || graph.nodes() == null) {
      return "";
    }
    return graph.nodes().stream()
        .filter(node -> parentNodeId.equals(node.nodeId()))
        .map(ChainPlanNode::type)
        .findFirst()
        .orElse("");
  }

  private static ChainPlanGraph readGraph(SkillWorkspace workspace) {
    return workspace
        .get(SkillArtifactType.CHAIN_PLAN_GRAPH)
        .map(a -> ((SkillArtifactPayload.ChainPlanGraphPayload) a.payload()).graph())
        .orElse(null);
  }

  private static String readRawUserRequest(SkillWorkspace workspace) {
    RequirementBrief brief =
        workspace
            .get(SkillArtifactType.REQUIREMENT_BRIEF)
            .map(a -> ((SkillArtifactPayload.RequirementBriefPayload) a.payload()).brief())
            .orElse(null);
    if (brief != null) {
      if (brief.approvedDraftText() != null && !brief.approvedDraftText().isBlank()) {
        return brief.approvedDraftText();
      }
      String formatted = RequirementBriefText.format(brief);
      if (!formatted.isBlank()) {
        return formatted;
      }
    }
    return workspace
        .get(SkillArtifactType.RAW_USER_REQUEST)
        .map(a -> ((SkillArtifactPayload.RawUserRequestPayload) a.payload()).effectiveText())
        .orElse("");
  }

  private static String readRequirementBrief(SkillWorkspace workspace) {
    RequirementBrief brief =
        workspace
            .get(SkillArtifactType.REQUIREMENT_BRIEF)
            .map(a -> ((SkillArtifactPayload.RequirementBriefPayload) a.payload()).brief())
            .orElse(null);
    if (brief == null) {
      return "";
    }
    return RequirementBriefText.format(brief);
  }

  private static String readSelectedPatternText(SkillWorkspace workspace) {
    SelectedPattern pattern =
        workspace
            .get(SkillArtifactType.SELECTED_PATTERN)
            .map(a -> ((SkillArtifactPayload.SelectedPatternPayload) a.payload()).pattern())
            .orElse(null);
    if (pattern == null) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    sb.append("patternId: ").append(nullToEmpty(pattern.patternId())).append('\n');
    if (!nullToEmpty(pattern.name()).isBlank()) {
      sb.append("name: ").append(pattern.name()).append('\n');
    }
    if (!nullToEmpty(pattern.reason()).isBlank()) {
      sb.append("reason: ").append(pattern.reason()).append('\n');
    }
    if (!nullToEmpty(pattern.summary()).isBlank()) {
      sb.append("skeleton: ").append(pattern.summary()).append('\n');
    }
    return sb.toString().trim();
  }

  private static String readGeneratorPlanManifestSummary(SkillWorkspace workspace) {
    GeneratorPlanManifest manifest =
        workspace
            .get(SkillArtifactType.GENERATOR_PLAN_MANIFEST)
            .map(
                artifact ->
                    ((SkillArtifactPayload.GeneratorPlanManifestPayload) artifact.payload())
                        .manifest())
            .orElse(null);
    if (manifest == null || manifest.plans() == null || manifest.plans().isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    if (manifest.packVersion() != null && !manifest.packVersion().isBlank()) {
      sb.append("packVersion: ").append(manifest.packVersion()).append('\n');
    }
    manifest
        .plans()
        .forEach(
            plan -> {
              sb.append("- ")
                  .append(plan.skillId())
                  .append(": ")
                  .append(plan.status())
                  .append('\n');
            });
    return sb.toString().trim();
  }

  private static String nullToEmpty(String value) {
    return value != null ? value : "";
  }
}
