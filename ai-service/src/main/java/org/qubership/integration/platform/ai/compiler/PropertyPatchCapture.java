package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.output.structured.Description;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;

/** LLM-facing property patch with a structured JSON value (not JSON-in-string). */
@JsonIgnoreProperties(ignoreUnknown = true)
record PropertyPatchCapture(
    @Description("ADD, UPDATE, or REMOVE") GraphPatchOperation operation,
    @Description("Existing plan node id") String targetNodeId,
    @Description("Catalog property key from describeElementPatchSchema") String key,
    @Description("Property value as JSON: string, number, boolean, array, or object")
        JsonNode value) {}
