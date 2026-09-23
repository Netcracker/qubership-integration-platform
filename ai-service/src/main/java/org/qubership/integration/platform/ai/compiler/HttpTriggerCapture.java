package org.qubership.integration.platform.ai.compiler;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/** Model input for HTTP trigger exposure on approved entry points. */
public record HttpTriggerCapture(
    @Description("One entry for every approved HTTP trigger") List<Endpoint> endpoints) {

  public HttpTriggerCapture {
    endpoints = endpoints == null ? List.of() : List.copyOf(endpoints);
  }

  public record Endpoint(
      @Description("Exact role id from the approved element skeleton") String roleId,
      @Description("Exact HTTP trigger node id from the approved design") String semanticNodeId,
      @Description("True only when the request explicitly calls for an external route")
          Boolean externalRoute) {}
}
