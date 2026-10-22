package org.qubership.integration.platform.ai.qipknowledge.artifact;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;

/**
 * One element a structural edit creates, declared inside the branch that holds it.
 *
 * <p>Carries no parent: the branch it is declared in decides where it nests, so a capture cannot
 * put a new element somewhere its branch does not reach. Carries no properties either, because a
 * configuration generator selected from ownership metadata writes those.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChainEditSubgraphElement(
    @Description("Id for this new element, unique across the chain and this capture") String nodeId,
    @Description("Catalog element type, e.g. script, service-call") String type,
    @Description("Human-readable element label") String label) {}
