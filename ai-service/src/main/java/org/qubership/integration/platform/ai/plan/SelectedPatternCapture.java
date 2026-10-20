package org.qubership.integration.platform.ai.plan;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ElementSkeleton;

/** Tool argument for {@link SelectedPatternTool#captureSelectedPattern}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SelectedPatternCapture(
    String patternId,
    String name,
    String reason,
    String summary,
    List<String> requiredCapabilities,
    ElementSkeleton elementSkeleton) {

  public SelectedPatternCapture(
      String patternId,
      String name,
      String reason,
      String summary,
      List<String> requiredCapabilities) {
    this(patternId, name, reason, summary, requiredCapabilities, null);
  }
}
