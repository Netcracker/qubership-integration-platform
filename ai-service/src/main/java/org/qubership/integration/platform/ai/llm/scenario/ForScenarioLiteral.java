package org.qubership.integration.platform.ai.llm.scenario;

import jakarta.enterprise.util.AnnotationLiteral;
import org.qubership.integration.platform.ai.model.ScenarioType;

public class ForScenarioLiteral extends AnnotationLiteral<ForScenario> implements ForScenario {

  private final ScenarioType value;

  public ForScenarioLiteral(ScenarioType value) {
    this.value = value;
  }

  @Override
  public ScenarioType value() {
    return value;
  }
}
