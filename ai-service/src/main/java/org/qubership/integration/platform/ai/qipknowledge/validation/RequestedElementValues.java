package org.qubership.integration.platform.ai.qipknowledge.validation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.plan.RequirementElementIntent.Placement;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.productpipeline.artifact.PlanValidationFinding;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.NamingManifest;

/** Applies validated author configuration without changing compiler-owned bodies after validation. */
public final class RequestedElementValues {
  public static final class ResolvedTargetConflict extends IllegalArgumentException {
    private final String sourceFactId;

    private ResolvedTargetConflict(String sourceFactId, String nodeId) {
      super("Requirement fact '" + sourceFactId + "' sets resolver-owned chain-call-2.elementId on node '"
          + nodeId + "'. Select the target through its capability.");
      this.sourceFactId = sourceFactId;
    }

    public PlanValidationFinding finding() {
      return new PlanValidationFinding(
          "BRIEF_CONTRACT", getMessage(), true, null, sourceFactId, "/elementIntent");
    }
  }

  /** Identifies the admitted source field that must acquire native body authority. */
  public static final class UnprovenBodyBinding extends IllegalArgumentException {
    private final String sourceFactId;

    private UnprovenBodyBinding(String sourceFactId, IllegalArgumentException cause) {
      super(cause.getMessage(), cause);
      this.sourceFactId = sourceFactId;
    }

    public PlanValidationFinding finding() {
      return new PlanValidationFinding("BODY_BINDING_REQUIRED", getMessage(), true, null,
          sourceFactId, "/elementIntent/bodyBinding");
    }
  }

  private static void requireBodyBinding(RequirementFact fact) {
    try {
      org.qubership.integration.platform.ai.plan.RequirementBodySources.requireBinding(fact.elementIntent());
    } catch (IllegalArgumentException unproven) {
      throw new UnprovenBodyBinding(fact.sourceFactId(), unproven);
    }
  }

  private RequestedElementValues() {}

  public static String chainName(List<RequirementFact> facts, String fallback) {
    return facts.stream().filter(fact -> fact.polarity() == RequirementFactPolarity.POSITIVE)
        .map(RequirementFact::elementIntent).filter(java.util.Objects::nonNull)
        .filter(intent -> intent.placement() == Placement.CHAIN && intent.requestedName() != null)
        .map(intent -> intent.requestedName()).findFirst().orElse(fallback);
  }

  public static Map<String, String> names(List<RequirementFact> facts) {
    Map<String, String> names = new LinkedHashMap<>();
    for (var fact : facts) {
      var intent = fact.elementIntent();
      if (intent != null && fact.polarity() == RequirementFactPolarity.POSITIVE
          && intent.placement() != Placement.CHAIN && intent.requestedName() != null) {
        String prior = names.putIfAbsent(intent.elementRef(), intent.requestedName());
        if (prior != null && !prior.equals(intent.requestedName())) {
          throw new IllegalArgumentException("Conflicting requested names for elementRef=" + intent.elementRef());
        }
      }
    }
    return Map.copyOf(names);
  }

  public static NamingManifest naming(NamingManifest generated, List<RequirementFact> facts) {
    Map<String, String> labels = new LinkedHashMap<>(generated.labelsByRoleId());
    labels.putAll(names(facts));
    return new NamingManifest(generated.schemaVersion(), chainName(facts, generated.chainName()),
        labels, generated.sourceRequirementFactIds(), generated.knowledgeCitations());
  }

  public static ConfiguredTriggerSet triggers(ConfiguredTriggerSet generated, List<RequirementFact> facts) {
    var nodes = generated.triggers().stream().map(trigger -> new ChainPlanNode(
        trigger.semanticNodeId(), trigger.elementType(), trigger.label(), null, null, trigger.properties())).toList();
    var scoped = facts.stream().filter(fact -> fact.elementIntent() != null
        && nodes.stream().anyMatch(node -> fact.elementIntent().elementRef().equals(node.nodeId()))).toList();
    var graph = apply(new ChainPlanGraph("1.0", new ChainSection("", ""), nodes, List.of()), scoped);
    var triggers = java.util.stream.IntStream.range(0, nodes.size()).mapToObj(index -> {
      var trigger = generated.triggers().get(index);
      var node = graph.nodes().get(index);
      return new ConfiguredTrigger(trigger.roleId(), node.nodeId(), node.type(), node.label(), node.properties());
    }).toList();
    return new ConfiguredTriggerSet(generated.schemaVersion(), triggers,
        generated.sourceRequirementFactIds(), generated.knowledgeCitations());
  }

  public static ChainPlanGraph seedBodies(ChainPlanGraph graph, List<RequirementFact> facts) {
    for (var fact : facts) {
      var intent = fact.elementIntent();
      if (intent == null || fact.polarity() != RequirementFactPolarity.POSITIVE) continue;
      for (var property : intent.requestedProperties()) {
        if (!org.qubership.integration.platform.ai.plan.RequirementBodySources.ownsBody(intent, property.key())) continue;
        requireBodyBinding(fact);
        if (intent.bodyBinding().authority()
            == org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY) {
          graph = graph.withNodeProperty(intent.elementRef(), property.key(), property.value());
        }
      }
    }
    return graph;
  }

  public static ChainPlanGraph apply(ChainPlanGraph graph, List<RequirementFact> facts) {
    Map<String, String> names = names(facts);
    List<ChainPlanNode> nodes = graph.nodes().stream().map(node -> names.containsKey(node.nodeId())
        ? new ChainPlanNode(node.nodeId(), node.type(), names.get(node.nodeId()), node.parentNodeId(),
            node.order(), node.properties()) : node).toList();
    var chain = graph.chain();
    graph = new ChainPlanGraph(graph.schemaVersion(), new ChainSection(chainName(facts, chain.name()),
        chain.description(), chain.maskingEnabled(), chain.maskedFieldNames(), chain.semanticRevisionId(),
        chain.compilerContractVersion(), chain.folderPath()), nodes, graph.edges());
    for (var fact : facts) {
      var intent = fact.elementIntent();
      if (intent == null || fact.polarity() != RequirementFactPolarity.POSITIVE
          || intent.placement() == Placement.CHAIN || intent.placement() == Placement.BEFORE_HOOK
          || intent.placement() == Placement.AFTER_HOOK) {
        continue;
      }
      var node = nodes.stream().filter(candidate -> candidate.nodeId().equals(intent.elementRef()))
          .findFirst().orElseThrow(() -> new IllegalArgumentException(
              "Requested element is absent from the graph: " + intent.elementRef()));
      if (!node.type().equals(intent.elementType())) {
        throw new IllegalArgumentException("Requested element type differs for " + intent.elementRef());
      }
      for (var property : intent.requestedProperties()) {
        String value = property.value();
        if (org.qubership.integration.platform.ai.plan.RequirementBodySources.ownsBody(intent, property.key())) {
          requireBodyBinding(fact);
          if (intent.bodyBinding().authority()
              == org.qubership.integration.platform.ai.plan.RequirementBodyBinding.Authority.VERBATIM_SOURCE_BODY
              && graph.nodes().stream().filter(candidate -> candidate.nodeId().equals(intent.elementRef()))
                  .flatMap(candidate -> candidate.properties().stream()).noneMatch(candidate ->
                      candidate.key().equals(property.key()) && java.util.Objects.equals(candidate.value(), property.value()))) {
            throw new IllegalArgumentException("Compiler body differs from exact author source for " + intent.elementRef()
                + ". Restore the verified whole body before validation; final projection cannot rewrite it.");
          }
          continue;
        }
        if ("chain-call-2".equals(node.type()) && "elementId".equals(property.key())) {
          throw new ResolvedTargetConflict(fact.sourceFactId(), node.nodeId());
        }
        if ("async-api-trigger".equals(node.type()) && "integrationOperationAsyncProperties".equals(property.key())) {
          try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var current = graph.nodes().stream().filter(candidate -> candidate.nodeId().equals(intent.elementRef()))
                .flatMap(candidate -> candidate.properties().stream()).filter(p -> p.key().equals(property.key()))
                .map(p -> p.value()).findFirst().orElse("{}");
            var options = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(current);
            var classifier = options.get("maas.classifier.name");
            options.setAll((com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(value));
            if (classifier != null) {
              options.set("maas.classifier.name", classifier);
            }
            value = options.toString();
          } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Invalid approved Kafka consumer options.", error);
          }
        }
        graph = graph.withNodeProperty(intent.elementRef(), property.key(), value);
      }
    }
    return graph;
  }
}
