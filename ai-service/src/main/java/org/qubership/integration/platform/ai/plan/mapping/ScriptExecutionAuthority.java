package org.qubership.integration.platform.ai.plan.mapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding;
import org.qubership.integration.platform.ai.plan.RequirementBodySources;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Checks declared script ownership; native plans and final bytes require their stage validators. */
public final class ScriptExecutionAuthority {
  private ScriptExecutionAuthority() {}

  public static Optional<String> declaredSuppliedBody(RequirementBrief brief, String nodeId) {
    if (brief == null || nodeId == null) return Optional.empty();
    var matches = brief.facts().stream()
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .map(fact -> fact.elementIntent()).filter(java.util.Objects::nonNull)
        .filter(intent -> nodeId.equals(intent.elementRef()) && RequirementBodySources.ownsBody(intent, "script"))
        .toList();
    RequirementBodyBinding agreedBinding = null;
    String agreedBody = null;
    for (var intent : matches) {
      try {
        RequirementBodySources.requireBinding(intent);
      } catch (IllegalArgumentException unproven) {
        return Optional.empty();
      }
      var binding = intent.bodyBinding();
      if (binding.authority() != RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY
          || binding.sourceGeneration() == null || binding.sourceGeneration().isBlank()
          || binding.sourceTurnId() == null || binding.sourceTurnId().isBlank()
          || binding.startOffset() == null || binding.endOffset() == null
          || binding.startOffset() < 0 || binding.endOffset() <= binding.startOffset()
          || binding.encoding() == null || intent.requestedProperties() == null) return Optional.empty();
      var bodies = intent.requestedProperties().stream().filter(property -> "script".equals(property.key())).toList();
      if (bodies.size() != 1 || bodies.getFirst().value() == null || bodies.getFirst().value().isBlank()) {
        return Optional.empty();
      }
      String body = bodies.getFirst().value();
      if (agreedBinding != null && (!agreedBinding.equals(binding) || !agreedBody.equals(body))) {
        return Optional.empty();
      }
      agreedBinding = binding;
      agreedBody = body;
    }
    return Optional.ofNullable(agreedBody);
  }

  public static List<MappingIntent> mappingsRequiringGeneration(
      ChainSemanticRevision revision, RequirementBrief brief) {
    Map<String, SemanticNode> nodes = new LinkedHashMap<>();
    revision.nodes().forEach(node -> nodes.put(node.nodeId(), node));
    return revision.mappingBodies(brief).stream().filter(intent -> {
      var sites = revision.executionEdges().stream()
          .filter(edge -> intent.mappingIntentId().equals(edge.mappingId()))
          .map(edge -> DefaultChainSemanticRevisionValidator.transformSiteNodeId(edge, nodes)).toList();
      return sites.size() != 1 || declaredSuppliedBody(brief, sites.getFirst()).isEmpty();
    }).toList();
  }

  public static boolean hasVerbatimBody(RequirementBrief brief, ChainPlanNode node) {
    if (node == null || node.properties() == null) return false;
    var bodies = node.properties().stream().filter(property -> "script".equals(property.key())).toList();
    return bodies.size() == 1 && declaredSuppliedBody(brief, node.nodeId())
        .filter(body -> body.equals(bodies.getFirst().value())).isPresent();
  }

  public static void requireSupported(RequirementBrief brief, ChainPlanGraph graph) {
    if (graph == null || graph.nodes() == null) return;
    for (ChainPlanNode node : graph.nodes()) {
      if (!"script".equals(node.type())) continue;
      String mappingId = MappingExecutionSite.mappingIntentId(node);
      boolean mapping = brief != null && mappingId != null && brief.mappingIntents().stream()
          .anyMatch(intent -> mappingId.equals(intent.mappingIntentId()));
      if (CopyMappingPlanner.nativeRetentionSite(node)) {
        if (declaredSuppliedBody(brief, node.nodeId()).isPresent()) {
          throw new IllegalArgumentException("Retention metadata conflicts with supplied script authority: " + node.nodeId());
        }
        continue;
      }
      if (!mapping && !hasVerbatimBody(brief, node)) {
        throw new IllegalArgumentException(unsupportedMessage(node.nodeId()));
      }
      if (declaredSuppliedBody(brief, node.nodeId()).isPresent() && !hasVerbatimBody(brief, node)) {
        throw new IllegalArgumentException("Supplied script body differs from its approved source: " + node.nodeId());
      }
    }
  }

  public static void requireRetentionOwners(ChainPlanGraph graph, Map<String, CopyMappingPlan> plans) {
    if (graph == null || graph.nodes() == null) return;
    for (ChainPlanNode node : graph.nodes()) {
      if (!CopyMappingPlanner.nativeRetentionSite(node)) continue;
      CopyMappingPlan plan = plans.get(node.nodeId());
      if (!"script".equals(node.type()) || plan == null || !plan.reads().isEmpty() || plan.saves().isEmpty()) {
        throw new IllegalArgumentException("Retention site is not part of the approved COPY dependency plan: " + node.nodeId());
      }
    }
  }

  public static String unsupportedMessage(String nodeId) {
    return "Capture a supported field mapping or supply an exact script body with source evidence for script node '"
        + nodeId + "'. This script has no executable authority.";
  }
}
