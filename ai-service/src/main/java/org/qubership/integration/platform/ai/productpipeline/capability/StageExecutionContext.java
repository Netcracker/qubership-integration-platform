package org.qubership.integration.platform.ai.productpipeline.capability;

import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.productpipeline.artifact.RunManifest;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;

/** Immutable inputs available to one capability invocation. */
public record StageExecutionContext(
    String runId,
    String conversationId,
    String stageId,
    String executionKey,
    String attemptId,
    ProductPipelineProfile profile,
    RunManifest runManifest,
    List<CompilationArtifacts.Reference> inputRefs,
    Map<String, Object> attributes) {

  public StageExecutionContext {
    inputRefs = inputRefs == null ? List.of() : List.copyOf(inputRefs);
    attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
  }

  public String attributeAsString(String key) {
    Object value = attributes.get(key);
    return value == null ? null : value.toString();
  }
}
