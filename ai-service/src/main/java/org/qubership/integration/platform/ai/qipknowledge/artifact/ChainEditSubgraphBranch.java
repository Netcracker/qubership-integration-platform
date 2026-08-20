package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/**
 * One branch of the container a structural edit adds, such as the try or the catch of a wrap.
 *
 * <p>A branch is identified by its child type rather than by its position, because the catalog
 * descriptor of the container decides which child types it allows and how many of each. Where a
 * container allows several children of one type, {@code properties} carries the value that tells
 * this branch from its siblings -- the handled exception of a catch, the condition of an if -- and
 * {@code order} carries the priority the catalog orders them by. Structure owns no other property:
 * everything else, including the body of a script, belongs to the configuration generator that owns
 * it.
 *
 * <p>{@code moveExisting} lists identifiers, and nothing else. An element the chain already has has
 * no other representation in a capture, so a capture cannot restate its type, reparent it under a
 * branch the edit did not name, drop it, or reconnect it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainEditSubgraphBranch(
    @Description("Catalog type of this branch, e.g. try-2, catch-2") String childType,
    @Description("Human-readable label for this branch") String label,
    @Description(
            "Only the properties that tell this branch from a sibling of the same type,"
                + " e.g. exception on a catch")
        List<PlanProperty> properties,
    @Description("Priority among sibling branches of the same type; null when the type occurs once")
        Integer order,
    @Description("Ids of existing chain elements that move into this branch") List<String> moveExisting,
    @Description("New elements this branch creates") ChainEditSubgraphBody body) {

  public ChainEditSubgraphBranch {
    properties = properties == null ? List.of() : List.copyOf(properties);
    moveExisting = moveExisting == null ? List.of() : List.copyOf(moveExisting);
    body = body == null ? new ChainEditSubgraphBody(List.of(), List.of()) : body;
  }
}
