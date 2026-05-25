package org.qubership.integration.platform.ai.llm.scenario;

import jakarta.enterprise.util.AnnotationLiteral;
import org.qubership.integration.platform.ai.model.ScenarioType;

/** {@link AnnotationLiteral} for programmatic CDI lookup of {@link ForScenario}-qualified beans. */
public final class ForScenarioLiteral extends AnnotationLiteral<ForScenario>
    implements ForScenario {

  private final ScenarioType value;

  public ForScenarioLiteral(ScenarioType value) {
    this.value = value;
  }

  @Override
  public ScenarioType value() {
    return value;
  }
}
