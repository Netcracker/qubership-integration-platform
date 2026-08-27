package org.qubership.integration.platform.ai.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import java.util.Optional;

/**
 * A single element node in the plan graph.
 *
 * <p>Containment hierarchy is expressed via {@code parentNodeId} — null means the node sits
 * at the chain root level. This keeps the graph flat even for deeply nested structures like
 * try-catch-finally or condition/if/else.
 *
 * <p>{@code order} is used for ordered containers: priority on {@code catch-2} blocks,
 * position within a split or condition branch. It never reaches the catalog: no materializer reads
 * it, and the chain-patch path nulls it outright. Branch priority travels to the catalog as the
 * ordinary {@code priority} property, which is what {@code OrderedElementService} renumbers
 * siblings from.
 *
 * <p>{@code properties} are populated by generator {@code captureGraphPatch} merges during planning,
 * not by skeleton {@code captureChainPlan}. The implement pipeline materializes them to the catalog.
 * Each entry must be a {@code {key, value}} object (script body key is {@code script}). Raw Groovy
 * strings in the array are coerced to {@code script} by {@link PlanPropertyListDeserializer}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainPlanNode(
    @Description("Unique node id within the plan graph") String nodeId,
    @Description("Catalog element type, e.g. http-trigger, script, condition, if, else")
        String type,
    @Description("Human-readable element label") String label,
    @Description("Parent node id for container children; null at chain root. Triggers must be null.")
        String parentNodeId,
    @Description("Order within an ordered container, e.g. catch-2 priority") Integer order,
    @JsonDeserialize(using = PlanPropertyListDeserializer.class)
        @Description(
            "Catalog properties as [{key,value}, ...]. Script body uses key 'script'."
                + " Prefer [] when only changing label. Never put a bare Groovy string in this array.")
        List<PlanProperty> properties) {

  /** Owner id stored under reserved property {@code serviceCallId}. Empty when absent. */
  @JsonIgnore
  public Optional<String> serviceCallId() {
    return reservedProperty("serviceCallId");
  }

  /** Semantic node id stored under reserved property {@code semanticNodeId}. Empty when absent. */
  @JsonIgnore
  public Optional<String> semanticNodeId() {
    return reservedProperty("semanticNodeId");
  }

  /** Semantic revision id stored under reserved property {@code semanticRevisionId}. Empty when absent. */
  @JsonIgnore
  public Optional<String> semanticRevisionId() {
    return reservedProperty("semanticRevisionId");
  }

  private Optional<String> reservedProperty(String key) {
    if (properties == null) {
      return Optional.empty();
    }
    for (PlanProperty property : properties) {
      if (property == null || !key.equals(property.key())) {
        continue;
      }
      String value = property.value();
      if (value == null || value.isBlank()) {
        return Optional.empty();
      }
      return Optional.of(value);
    }
    return Optional.empty();
  }
}
