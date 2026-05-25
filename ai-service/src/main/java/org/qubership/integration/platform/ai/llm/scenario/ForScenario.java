package org.qubership.integration.platform.ai.llm.scenario;

import jakarta.inject.Qualifier;
import org.qubership.integration.platform.ai.model.ScenarioType;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.*;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

/**
 * CDI qualifier that binds a {@link ScenarioHandler} to a specific {@link ScenarioType}. Used by
 * {@link org.qubership.integration.platform.ai.llm.routing.ScenarioRouter} to look up handlers
 * dynamically.
 */
@Qualifier
@Retention(RUNTIME)
@Target({TYPE, FIELD, PARAMETER, METHOD})
public @interface ForScenario {
  ScenarioType value();
}
