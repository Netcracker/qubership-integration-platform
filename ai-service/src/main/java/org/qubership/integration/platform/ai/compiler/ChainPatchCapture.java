package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.output.structured.Description;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;

/** LLM-facing chain-level patch with a structured JSON value (not JSON-in-string). */
@JsonIgnoreProperties(ignoreUnknown = true)
record ChainPatchCapture(
    @Description("ADD, UPDATE, or REMOVE")
        @JsonProperty(required = true) GraphPatchOperation operation,
    @Description("Chain-level field key, e.g. name, description, maskingEnabled, or maskedFieldNames")
        @JsonProperty(required = true) String key,
    @Description("Field value as JSON: string, number, boolean, array, or object") JsonNode value) {}
