package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.productpipeline.artifact.PatchApplicability;

class CompilerGeneratorPatchRegressionTest {

  private final GeneratorPatchRegressionHarness harness = new GeneratorPatchRegressionHarness();

  static Stream<GeneratorPatchRegressionCase> generatorCases() {
    return GeneratorPatchRegressionHarness.loadCases();
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("generatorCases")
  void replaysGeneratorPatchSeam(GeneratorPatchRegressionCase fixture) {
    GeneratorPatchRegressionResult result = harness.run(fixture);

    assertEquals(fixture.expectedApplicability(), result.artifact().applicability());
    assertEquals(
        harness.digest().sha256(fixture.inputGraph()), result.artifact().baseGraphDigest());
    assertEquals(
        harness.digest().sha256(fixture.expectedGraph()), result.artifact().resultGraphDigest());
    assertEquals(fixture.expectedGraph(), result.graph());
  }

  @Test
  void coversEveryPromotedGeneratorInBothModes() {
    Map<String, Set<PatchApplicability>> coverage =
        generatorCases()
            .collect(
                Collectors.groupingBy(
                    GeneratorPatchRegressionCase::skillId,
                    Collectors.mapping(
                        GeneratorPatchRegressionCase::expectedApplicability, Collectors.toSet())));

    assertEquals(promotedGeneratorIds(), coverage.keySet());
    assertTrue(
        coverage.values().stream()
            .allMatch(
                modes ->
                    modes.equals(
                        Set.of(PatchApplicability.APPLICABLE, PatchApplicability.NOT_APPLICABLE))));
  }

  private static Set<String> promotedGeneratorIds() {
    return Set.of(
        "cip-auth-generator",
        "cip-composition-generator",
        "cip-error-handling-generator",
        "cip-loop-generator",
        "cip-monitoring-generator",
        "cip-parallel-generator",
        "cip-retry-generator",
        "cip-routing-generator",
        "cip-script-generator",
        "cip-security-generator",
        "cip-service-call-generator",
        "cip-timeout-generator");
  }
}
