package org.qubership.integration.platform.ai.chain.imports;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogDependency;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogElement;
import org.qubership.integration.platform.ai.chain.presentation.ChainCatalogFacts;
import org.qubership.integration.platform.ai.integration.catalog.materialize.MaterializationMap;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.CanonicalGraphDigest;

/**
 * Reads a catalog chain snapshot into the plan model so an existing chain can be patched.
 *
 * <p>The inverse direction of materialization, and the only one that starts from a chain the service
 * did not create. Node ids are the catalog element ids they were read from: the skeleton
 * materializer refuses to create an element for a node already in the {@link MaterializationMap}, so
 * an imported element cannot be duplicated by a later patch.
 *
 * <p>Not to be confused with specification import, which brings an API Hub specification into the
 * catalog. This import reads the catalog into the plan model.
 */
@ApplicationScoped
public class ChainPlanGraphImporter {

  private static final String SCHEMA_VERSION = "1.0";

  private final ObjectMapper objectMapper;
  private final CanonicalGraphDigest canonicalGraphDigest;

  @Inject
  public ChainPlanGraphImporter(
      ObjectMapper objectMapper, CanonicalGraphDigest canonicalGraphDigest) {
    this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    this.canonicalGraphDigest = Objects.requireNonNull(canonicalGraphDigest, "canonicalGraphDigest");
  }

  public ImportedChainPlan importChain(ChainCatalogFacts facts) {
    Objects.requireNonNull(facts, "facts");

    List<ChainPlanNode> nodes = new ArrayList<>();
    Map<String, String> nodeIdToElementId = new LinkedHashMap<>();
    for (ChainCatalogElement element : facts.elements()) {
      if (element == null || isBlank(element.elementId())) {
        continue;
      }
      nodes.add(toNode(element));
      nodeIdToElementId.put(element.elementId(), element.elementId());
    }

    List<ChainPlanEdge> edges = new ArrayList<>();
    for (ChainCatalogDependency dependency : facts.dependencies()) {
      if (dependency == null
          || isBlank(dependency.fromElementId())
          || isBlank(dependency.toElementId())) {
        continue;
      }
      edges.add(toEdge(dependency));
    }

    // Sorted, so the digest identifies the chain's content rather than the order the catalog
    // happened to return its rows in: runtime-catalog reads elements without an ORDER BY, and an
    // unchanged chain re-read under a different row order must digest the same.
    nodes.sort(Comparator.comparing(ChainPlanNode::nodeId));
    edges.sort(Comparator.comparing(ChainPlanEdge::edgeId));

    ChainPlanGraph graph =
        new ChainPlanGraph(
            SCHEMA_VERSION,
            new ChainSection(facts.chainName(), facts.chainDescription()),
            List.copyOf(nodes),
            List.copyOf(edges));

    return new ImportedChainPlan(
        graph,
        new MaterializationMap(facts.chainId(), Map.copyOf(nodeIdToElementId)),
        canonicalGraphDigest.sha256(graph));
  }

  private ChainPlanNode toNode(ChainCatalogElement element) {
    // order stays null: the catalog has no order field of its own, and container priority arrives
    // as an ordinary property (see OrderedElementUtils in runtime-catalog).
    return new ChainPlanNode(
        element.elementId(),
        element.type(),
        element.name(),
        blankToNull(element.parentElementId()),
        null,
        toPlanProperties(element.properties()));
  }

  private static ChainPlanEdge toEdge(ChainCatalogDependency dependency) {
    // scopeNodeId stays null: the connections materializer derives an edge's scope from the parents
    // of the nodes it joins, and never reads the field.
    return new ChainPlanEdge(
        dependency.fromElementId() + "->" + dependency.toElementId(),
        dependency.fromElementId(),
        dependency.toElementId(),
        null);
  }

  private List<PlanProperty> toPlanProperties(Map<String, Object> properties) {
    if (properties == null || properties.isEmpty()) {
      return List.of();
    }
    List<PlanProperty> planProperties = new ArrayList<>();
    for (Map.Entry<String, Object> entry : properties.entrySet()) {
      if (isBlank(entry.getKey())) {
        continue;
      }
      planProperties.add(new PlanProperty(entry.getKey(), toPropertyValue(entry.getValue())));
    }
    return List.copyOf(planProperties);
  }

  /**
   * Renders a catalog property value as the string a {@link PlanProperty} carries.
   *
   * <p>The properties materializer parses JSON objects, arrays, and booleans back out of these
   * strings, but not numbers: a numeric property written back through a patch reaches the catalog as
   * a string. Only patched elements are written, so this shows up only when a patch touches an
   * element that also carries a numeric property. Fixing it means teaching the materializer to parse
   * numbers, which changes what generator-produced plans write.
   */
  private String toPropertyValue(Object value) {
    return switch (value) {
      case null -> null;
      case String text -> text;
      case Boolean flag -> flag.toString();
      case Number number -> number.toString();
      default -> writeJson(value);
    };
  }

  private String writeJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JsonProcessingException e) {
      throw new IllegalStateException("Cannot read catalog property value as JSON", e);
    }
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  private static String blankToNull(String value) {
    return isBlank(value) ? null : value;
  }
}
