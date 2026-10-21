package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/** LLM-facing script-only repair input. */
@JsonIgnoreProperties(ignoreUnknown = true)
record ScriptBodyRepairCapture(
    @Description("Unique repair patch id") String patchId,
    @JsonProperty(required = true)
    @Description("Script bodies for every missing script node id") List<ScriptBodyEntry> scripts,
    @Description("Why these script bodies satisfy the chain behavior") String rationale) {}
