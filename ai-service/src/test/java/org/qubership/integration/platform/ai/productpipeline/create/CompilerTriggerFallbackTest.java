package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class CompilerTriggerFallbackTest {

  @Test
  void extractsHttpTriggerFromEndpointFact() {
    RequirementBrief brief =
        new RequirementBrief(
            "goal",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            null,
            "",
            List.of(
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.ENDPOINT,
                    "http",
                    "Inbound trigger: POST /hello")),
            List.of());

    Optional<ConfiguredTriggerSet> result = CompilerTriggerFallback.fromBrief(brief);

    assertTrue(result.isPresent());
    ConfiguredTrigger trigger = result.get().triggers().get(0);
    assertEquals("http-trigger", trigger.elementType());
    assertEquals("POST", property(trigger, "httpMethodRestrict"));
    assertEquals("/hello", property(trigger, "contextPath"));
    assertEquals("true", property(trigger, "externalRoute"));
    assertEquals("RBAC", property(trigger, "accessControlType"));
    assertEquals("default", property(trigger, "handleChainFailureAction"));
  }

  @Test
  void ignoresNegativeEndpointFacts() {
    RequirementBrief brief =
        new RequirementBrief(
            "goal",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            null,
            "",
            List.of(
                RequirementFact.of(
                    RequirementFactPolarity.NEGATIVE,
                    RequirementFactKind.ENDPOINT,
                    "http",
                    "Do not expose POST /internal")),
            List.of());

    assertFalse(CompilerTriggerFallback.fromBrief(brief).isPresent());
  }

  @Test
  void returnsEmptyWhenNoEndpointFact() {
    RequirementBrief brief =
        new RequirementBrief(
            "goal",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            null,
            "",
            List.of(
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.SERVICE_CALL,
                    "api",
                    "POST /stub/path")),
            List.of());

    assertFalse(CompilerTriggerFallback.fromBrief(brief).isPresent());
  }

  @Test
  void normalizesPathWithoutLeadingSlash() {
    RequirementBrief brief =
        new RequirementBrief(
            "goal",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "summary",
            null,
            "",
            List.of(
                RequirementFact.of(
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.ENDPOINT,
                    "http",
                    "GET health")),
            List.of());

    Optional<ConfiguredTriggerSet> result = CompilerTriggerFallback.fromBrief(brief);

    assertTrue(result.isPresent());
    assertEquals("/health", property(result.get().triggers().get(0), "contextPath"));
  }

  private static String property(ConfiguredTrigger trigger, String key) {
    return trigger.properties().stream()
        .filter(p -> p.key().equals(key))
        .findFirst()
        .orElseThrow(() -> new AssertionError("missing property " + key))
        .value();
  }
}
