package org.qubership.integration.platform.ai.plan.mapping;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.qubership.integration.platform.ai.plan.RequirementBodyBinding;
import org.qubership.integration.platform.ai.plan.RequirementBodySources;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
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
      if (agreedBinding != null && !agreedBinding.equals(binding)) return Optional.empty();
      agreedBinding = binding;
      if (bodies.isEmpty()) continue;
      if (bodies.size() != 1 || bodies.getFirst().value() == null || bodies.getFirst().value().isBlank()) {
        return Optional.empty();
      }
      String body = bodies.getFirst().value();
      if (agreedBody != null && !agreedBody.equals(body)) return Optional.empty();
      agreedBody = body;
    }
    return Optional.ofNullable(agreedBody);
  }

  public static Optional<RequirementFact> declaredGeneratedBehavior(RequirementBrief brief, String nodeId) {
    if (brief == null || nodeId == null) return Optional.empty();
    var facts = brief.facts().stream()
        .filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .filter(fact -> RequirementBodySources.ownsBody(fact.elementIntent(), "script")
            && nodeId.equals(fact.elementIntent().elementRef()))
        .toList();
    RequirementFact behavior = null;
    RequirementBodyBinding agreedBinding = null;
    for (var fact : facts) {
      var intent = fact.elementIntent();
      if (intent.placement() != RequirementElementIntent.Placement.LOCAL_NODE
          || intent.ownerInteractionId() == null || !fact.interactionIds().contains(intent.ownerInteractionId())
          || intent.requestedProperties() == null
          || intent.requestedProperties().stream().anyMatch(property -> property == null || "script".equals(property.key()))) {
        return Optional.empty();
      }
      try {
        RequirementBodySources.requireBinding(intent);
      } catch (IllegalArgumentException unproven) {
        return Optional.empty();
      }
      var binding = intent.bodyBinding();
      if (binding.authority() != RequirementBodyBinding.Authority.GENERATED_CANDIDATE
          || binding.sourceGeneration() == null || binding.sourceGeneration().isBlank()
          || binding.sourceTurnId() == null || binding.sourceTurnId().isBlank()
          || binding.startOffset() != null || binding.endOffset() != null || binding.encoding() != null
          || agreedBinding != null && !agreedBinding.equals(binding)) return Optional.empty();
      agreedBinding = binding;
      if (fact.kind() == RequirementFactKind.BEHAVIOR && !fact.text().isBlank() && behavior == null) behavior = fact;
    }
    return Optional.ofNullable(behavior);
  }

  public static Map<String, List<String>> behaviorSites(ChainSemanticRevision revision) {
    return behaviorSites(revision, null);
  }

  public static Map<String, List<String>> behaviorSites(ChainSemanticRevision revision, RequirementBrief brief) {
    if (revision == null) return Map.of();
    Map<String, SemanticNode> nodes = new LinkedHashMap<>();
    revision.nodes().forEach(node -> nodes.put(node.nodeId(), node));
    var mappings = new java.util.HashSet<String>();
    revision.executionEdges().stream().filter(edge -> edge.mappingId() != null && !edge.mappingId().isBlank())
        .forEach(edge -> mappings.add(DefaultChainSemanticRevisionValidator.transformSiteNodeId(edge, nodes, brief)));
    Map<String, List<String>> sites = new LinkedHashMap<>();
    revision.nodes().stream().filter(node -> node instanceof SemanticNode.Operation operation
        && "script".equals(operation.elementType()) && !mappings.contains(node.nodeId()))
        .forEach(node -> sites.put(node.nodeId(), node.provenance().sourceFactIds()));
    return Map.copyOf(sites);
  }

  /** Resolves approved behavior for a server-bound Script site while preserving explicit script requirements. */
  public static Optional<RequirementFact> declaredGeneratedBehavior(
      RequirementBrief brief, String nodeId, Map<String, List<String>> sites) {
    var declared = declaredGeneratedBehavior(brief, nodeId);
    if (declared.isPresent() || brief == null) return declared;
    if (brief.facts().stream().anyMatch(fact -> fact.elementIntent() != null
        && nodeId.equals(fact.elementIntent().elementRef()))) return Optional.empty();
    var references = sites.getOrDefault(nodeId, List.of());
    var behaviors = brief.facts().stream().filter(fact -> references.contains(fact.sourceFactId())
        && fact.polarity() == RequirementFactPolarity.POSITIVE && fact.kind() == RequirementFactKind.BEHAVIOR
        && fact.elementIntent() == null && !fact.text().isBlank()).toList();
    if (behaviors.stream().anyMatch(fact -> RequirementBodySources.hasBodyDeclaration(fact.text()))) return Optional.empty();
    return behaviors.stream().findFirst();
  }

  /** Rejects additional behavior whose only implementation is a generated native COPY site. */
  public static void requireMappingBehaviorSites(RequirementBrief brief, ChainSemanticRevision revision) {
    if (brief == null || revision == null) return;
    var sites = behaviorSites(revision, brief);
    for (var fact : brief.facts()) {
      if (fact.polarity() != RequirementFactPolarity.POSITIVE || fact.kind() != RequirementFactKind.BEHAVIOR) continue;
      var owners = revision.nodes().stream()
          .filter(node -> node.provenance().sourceFactIds().contains(fact.sourceFactId())).toList();
      boolean mapped = owners.stream().anyMatch(node -> node instanceof SemanticNode.Operation operation
          && "script".equals(operation.elementType()) && !sites.containsKey(node.nodeId())
          && declaredSuppliedBody(brief, node.nodeId()).isEmpty());
      boolean implemented = owners.stream().anyMatch(node -> node instanceof SemanticNode.Operation operation
          && "script".equals(operation.elementType())
          && (declaredSuppliedBody(brief, node.nodeId()).isPresent()
              || sites.containsKey(node.nodeId()) && declaredGeneratedBehavior(brief, node.nodeId(), sites).isPresent()));
      if (!mapped || implemented) continue;
      throw new IllegalArgumentException("Native COPY scripts cannot implement additional behavior '" + fact.sourceFactId()
          + "'. Add a separate processing operation bound to this approved behavior, with its own sourceFactIds"
          + " and execution edges. Do not combine response composition, computation or manual retention with COPY.");
    }
  }

  public static List<MappingIntent> mappingsRequiringGeneration(
      ChainSemanticRevision revision, RequirementBrief brief) {
    Map<String, SemanticNode> nodes = new LinkedHashMap<>();
    revision.nodes().forEach(node -> nodes.put(node.nodeId(), node));
    return revision.mappingBodies(brief).stream().filter(intent -> {
      var sites = revision.executionEdges().stream()
          .filter(edge -> intent.mappingIntentId().equals(edge.mappingId()))
          .map(edge -> DefaultChainSemanticRevisionValidator.transformSiteNodeId(edge, nodes, brief)).toList();
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
    requireSupported(brief, graph, Map.of());
  }

  public static void requireSupported(RequirementBrief brief, ChainPlanGraph graph, Map<String, List<String>> sites) {
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
      if (!mapping && !hasVerbatimBody(brief, node)
          && declaredGeneratedBehavior(brief, node.nodeId(), sites).isEmpty()) {
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
    return "Bind approved behavior, a supported field mapping, or an exact source-backed body to script node '"
        + nodeId + "'. This script has no executable authority.";
  }
}
