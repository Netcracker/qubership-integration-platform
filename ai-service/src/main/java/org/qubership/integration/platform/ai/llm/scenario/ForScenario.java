package org.qubership.integration.platform.ai.llm.scenario;

import jakarta.inject.Qualifier;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Qualifier
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD, ElementType.PARAMETER})
public @interface ForScenario {
  ScenarioType value();
}
