package org.qubership.integration.platform.ai.plan.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/**
 * A single element node in the plan graph.
 *
 * <p>Containment hierarchy is expressed via {@code parentNodeId} — null means the node sits
 * at the chain root level. This keeps the graph flat even for deeply nested structures like
 * try-catch-finally or condition/if/else.
 *
 * <p>{@code order} is used for ordered containers: priority on {@code catch-2} blocks,
 * position within a split or condition branch.
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
        List<PlanProperty> properties) {}
