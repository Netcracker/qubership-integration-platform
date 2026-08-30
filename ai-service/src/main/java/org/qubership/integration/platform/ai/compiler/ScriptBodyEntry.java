package org.qubership.integration.platform.ai.compiler;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/** One script body for a missing script node repair. */
@JsonIgnoreProperties(ignoreUnknown = true)
record ScriptBodyEntry(
    @Description("Existing script node id that needs a script body") String targetNodeId,
    @Description(
            "Non-empty Groovy body for the target script node. Escape every \" as \\\"."
                + " Prefer groovy.json.JsonOutput.toJson([error: exception?.message]) for JSON"
                + " bodies — never embed raw JSON object literals with double quotes.")
        String script,
    @Description(
            "JSON array of implemented mapping target paths. Required when the target node has"
                + " mappingIntentId.")
        List<String> mappingCoverage) {

  ScriptBodyEntry {
    mappingCoverage = mappingCoverage == null ? null : List.copyOf(mappingCoverage);
  }

  ScriptBodyEntry(String targetNodeId, String script) {
    this(targetNodeId, script, null);
  }
}
