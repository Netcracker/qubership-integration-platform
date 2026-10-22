package org.qubership.integration.platform.ai.productpipeline.profile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ProductPipelineProfileOptionalArtifactsTest {

  private static final ArtifactSchemaRegistry SCHEMA_REGISTRY =
      ArtifactSchemaRegistry.of(
          new ArtifactTypeRef("user-input", 1),
          new ArtifactTypeRef("run-manifest", 1),
          new ArtifactTypeRef("ids-document", 1),
          new ArtifactTypeRef("requirement-brief", 1),
          new ArtifactTypeRef("chain-semantic-revision", 1),
          new ArtifactTypeRef("approval-record", 2));

  private static final Set<String> CAPABILITIES = Set.of("design-input", "noop");

  @Test
  void acceptsRunManifestAsRuntimeBootstrapWithoutRunInput() {
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "optional-artifacts",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "ids-entry",
                    "design-input",
                    List.of(
                        new ArtifactTypeRef("user-input", 1),
                        new ArtifactTypeRef("run-manifest", 1)),
                    List.of(),
                    List.of(),
                    List.of(new ArtifactTypeRef("ids-document", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    null)),
            new TerminalPolicy("ids-entry", "PLAN_APPROVED"),
            List.of("design-input"));

    assertDoesNotThrow(
        () -> ProductPipelineProfileValidator.validate(profile, SCHEMA_REGISTRY, CAPABILITIES));
  }

  @Test
  void rejectsOverlapBetweenRequiredAndOptionalConsumes() {
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "overlap",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "design-input",
                    "design-input",
                    List.of(new ArtifactTypeRef("requirement-brief", 1)),
                    List.of(new ArtifactTypeRef("requirement-brief", 1)),
                    List.of(new ArtifactTypeRef("chain-semantic-revision", 1)),
                    List.of(),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    null)),
            new TerminalPolicy("design-input", "PLAN_APPROVED"),
            List.of("design-input"));

    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () -> ProductPipelineProfileValidator.validate(profile, SCHEMA_REGISTRY, CAPABILITIES));
    assertTrue(ex.getMessage().toLowerCase().contains("overlap"), ex.getMessage());
  }

  @Test
  void optionalProducerSatisfiesLaterOptionalConsumer() {
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            1,
            "optional-chain",
            "2",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(
                new ProfileStage(
                    "ids-entry",
                    "design-input",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(),
                    List.of(),
                    List.of(new ArtifactTypeRef("ids-document", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    null),
                new ProfileStage(
                    "design-input",
                    "design-input",
                    List.of(new ArtifactTypeRef("user-input", 1)),
                    List.of(new ArtifactTypeRef("ids-document", 1)),
                    List.of(new ArtifactTypeRef("chain-semantic-revision", 1)),
                    List.of(new ArtifactTypeRef("ids-document", 1)),
                    null,
                    null,
                    new RetryPolicy(0, 1L),
                    null)),
            new TerminalPolicy("design-input", "PLAN_APPROVED"),
            List.of("design-input"));

    assertDoesNotThrow(
        () -> ProductPipelineProfileValidator.validate(profile, SCHEMA_REGISTRY, CAPABILITIES));
  }
}
