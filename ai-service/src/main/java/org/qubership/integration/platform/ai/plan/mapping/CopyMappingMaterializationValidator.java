package org.qubership.integration.platform.ai.plan.mapping;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.plan.mapping.envelope.MappingEnvelope;
import org.qubership.integration.platform.ai.plan.mapping.schema.DefaultMappingBoundarySchemaResolver;
import org.qubership.integration.platform.ai.plan.mapping.schema.MappingSchemaSide;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Rechecks all script authority and deterministic native COPY bodies before catalog writes, including resume. */
@ApplicationScoped
public class CopyMappingMaterializationValidator {
  private final CompilationArtifacts artifacts;

  @Inject
  public CopyMappingMaterializationValidator(CompilationArtifacts artifacts) {
    this.artifacts = Objects.requireNonNull(artifacts, "artifacts");
  }

  public static List<MappingIntent> ordinaryMappings(RequirementBrief brief, ChainPlanGraph graph) {
    List<MappingIntent> ordinary = new ArrayList<>();
    java.util.Set<String> approved = brief.mappingIntents().stream().map(MappingIntent::mappingIntentId)
        .collect(java.util.stream.Collectors.toSet());
    for (ChainPlanNode node : graph.nodes()) {
      String id = MappingExecutionSite.mappingIntentId(node);
      if (id != null && !id.isBlank() && !approved.contains(id)) {
        throw new IllegalArgumentException("Graph mapping site has no approved mapping intent");
      }
    }
    for (MappingIntent intent : brief.mappingIntents()) {
      var sites = graph.nodes().stream().filter(node ->
          intent.mappingIntentId().equals(MappingExecutionSite.mappingIntentId(node))).toList();
      if (sites.size() > 1) throw new IllegalArgumentException("Approved mapping has duplicate execution sites");
      boolean supplied = sites.size() == 1 && CopyMappingPlanner.hasVerbatimBody(brief, sites.getFirst());
      if (!supplied) ordinary.add(intent);
    }
    return List.copyOf(ordinary);
  }

  public void validate(String runId, RequirementBrief brief,
      ChainSemanticRevision semantic, ChainPlanGraph graph) {
    ScriptExecutionAuthority.requireSupported(brief, graph);
    List<MappingIntent> ordinary = ordinaryMappings(brief, graph);
    if (ordinary.isEmpty()) {
      if (graph.nodes().stream().anyMatch(CopyMappingPlanner::nativeRetentionSite)) {
        throw new IllegalArgumentException("Retention site has no current ordinary COPY owner");
      }
      return;
    }
    Map<String, String> triggerOwners = new LinkedHashMap<>();
    for (SemanticNode node : semantic.nodes()) {
      if (node instanceof SemanticNode.Trigger trigger
          && brief.catalogBindings().stream().anyMatch(hint -> trigger.interactionId().equals(hint.interactionId()))) {
        triggerOwners.put(trigger.nodeId(), trigger.interactionId());
      }
    }
    DefaultMappingBoundarySchemaResolver resolver =
        new DefaultMappingBoundarySchemaResolver(artifacts, runId, triggerOwners);
    Map<String, MappingSchemaSide> outputs = new LinkedHashMap<>();
    for (MappingIntent intent : ordinary) {
      MappingSchemaSide target = resolver.resolve(semantic, List.of(), intent, outputs).target();
      outputs.put(intent.mappingIntentId(), target);
      outputs.put("transform-" + intent.mappingIntentId(), target);
      for (ChainPlanNode node : graph.nodes()) {
        if (intent.mappingIntentId().equals(MappingExecutionSite.mappingIntentId(node))) outputs.put(node.nodeId(), target);
      }
    }
    Map<String, CopyMappingPlan> expected = CopyMappingPlanner.prepare(
        semantic, ordinary, resolver, List.of(), outputs, graph);
    ScriptExecutionAuthority.requireRetentionOwners(graph, expected);
    Map<String, MappingEnvelope> envelopes = new LinkedHashMap<>();
    for (var revision : artifacts.history(runId, Kind.MAPPING_ENVELOPE)) {
      MappingEnvelope envelope = artifacts.payload(revision, MappingEnvelope.class);
      if (envelope.mappingIntentId() != null) envelopes.put(envelope.mappingIntentId(), envelope);
    }
    for (var entry : expected.entrySet()) {
      MappingEnvelope envelope = envelopes.get(entry.getKey());
      if (envelope == null || !entry.getValue().equals(envelope.verifiedCopyPlan())) {
        throw new IllegalArgumentException("COPY execution requires its current frozen origin and retention plan");
      }
      List<ChainPlanNode> sites;
      String expectedBody;
      if (entry.getValue().reads().isEmpty()) {
        sites = graph.nodes().stream().filter(node -> entry.getKey().equals(node.nodeId())
            && CopyMappingPlanner.nativeRetentionSite(node)).toList();
        expectedBody = DirectFieldMappingScript.saveOnly(entry.getValue()).script();
      } else {
        sites = graph.nodes().stream().filter(node ->
            entry.getKey().equals(MappingExecutionSite.mappingIntentId(node))).toList();
        MappingIntent intent = ordinary.stream().filter(item -> entry.getKey().equals(item.mappingIntentId()))
            .findFirst().orElseThrow();
        expectedBody = DirectFieldMappingScript.from(intent, envelope).orElseThrow().script();
      }
      if (sites.size() != 1 || !"script".equals(sites.getFirst().type())) {
        throw new IllegalArgumentException("COPY execution site must resolve uniquely to its script");
      }
      if (sites.getFirst().properties() == null) {
        throw new IllegalArgumentException("COPY execution site has no frozen script body");
      }
      var bodies = sites.getFirst().properties().stream().filter(property -> "script".equals(property.key())).toList();
      if (bodies.size() != 1 || bodies.getFirst().value() == null
          || !expectedBody.equals(bodies.getFirst().value())) {
        throw new IllegalArgumentException("COPY script body differs from its approved origin plan");
      }
    }
  }
}
