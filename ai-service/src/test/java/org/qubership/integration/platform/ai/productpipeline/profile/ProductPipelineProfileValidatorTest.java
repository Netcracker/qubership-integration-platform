package org.qubership.integration.platform.ai.productpipeline.profile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProductPipelineProfileValidatorTest {

  private static final ArtifactSchemaRegistry SCHEMA_REGISTRY =
      ArtifactSchemaRegistry.of(
          new ArtifactTypeRef("user-input", 1),
          new ArtifactTypeRef("requirement-brief", 1),
          new ArtifactTypeRef("ids-bypass", 1),
          new ArtifactTypeRef("approval-record", 2));

  private static final Set<String> KNOWN_CAPABILITIES = Set.of("fake-collector", "fake-finisher");

  private ProductPipelineProfile validProfile;

  @BeforeEach
  void loadValidProfile() throws Exception {
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/two-stage-approval-v1.yaml")) {
      validProfile = ProductPipelineProfileParser.parse(in);
    }
  }

  @Test
  void acceptsOrderedTwoStageProfile() {
    assertDoesNotThrow(
        () ->
            ProductPipelineProfileValidator.validate(
                validProfile, SCHEMA_REGISTRY, KNOWN_CAPABILITIES));
  }

  @Test
  void rejectsDuplicateStageId() {
    ProfileStage first = validProfile.stages().get(0);
    ProfileStage duplicate =
        new ProfileStage(
            first.stageId(),
            "fake-finisher",
            List.of(new ArtifactTypeRef("requirement-brief", 1)),
            List.of(new ArtifactTypeRef("ids-bypass", 1)),
            new ApprovalPolicy(new ArtifactTypeRef("ids-bypass", 1)),
            null,
            new RetryPolicy(0, 1000));
    ProductPipelineProfile profile =
        copyWithStages(validProfile, List.of(first, duplicate));

    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () ->
                ProductPipelineProfileValidator.validate(
                    profile, SCHEMA_REGISTRY, KNOWN_CAPABILITIES));
    assertTrue(ex.getMessage().contains(profile.profileId()));
    assertTrue(ex.getMessage().contains(first.stageId()));
  }

  @Test
  void rejectsArtifactWithoutEarlierProducer() {
    ProfileStage collect = validProfile.stages().get(0);
    ProfileStage finish =
        new ProfileStage(
            "finish",
            "fake-finisher",
            List.of(new ArtifactTypeRef("missing-input", 1)),
            List.of(new ArtifactTypeRef("ids-bypass", 1)),
            new ApprovalPolicy(new ArtifactTypeRef("ids-bypass", 1)),
            null,
            new RetryPolicy(1, 1000));
    ArtifactSchemaRegistry registry =
        ArtifactSchemaRegistry.of(
            new ArtifactTypeRef("user-input", 1),
            new ArtifactTypeRef("requirement-brief", 1),
            new ArtifactTypeRef("ids-bypass", 1),
            new ArtifactTypeRef("missing-input", 1));
    ProductPipelineProfile profile = copyWithStages(validProfile, List.of(collect, finish));

    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () ->
                ProductPipelineProfileValidator.validate(
                    profile, registry, KNOWN_CAPABILITIES));
    assertTrue(ex.getMessage().contains(profile.profileId()));
    assertTrue(ex.getMessage().contains("finish"));
  }

  @Test
  void rejectsUnknownArtifactSchema() {
    ArtifactSchemaRegistry incomplete =
        ArtifactSchemaRegistry.of(
            new ArtifactTypeRef("user-input", 1), new ArtifactTypeRef("requirement-brief", 1));

    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () ->
                ProductPipelineProfileValidator.validate(
                    validProfile, incomplete, KNOWN_CAPABILITIES));
    assertTrue(ex.getMessage().contains(validProfile.profileId()));
  }

  @Test
  void rejectsMissingRetryPolicy() {
    ProfileStage collect = validProfile.stages().get(0);
    ProfileStage finish = validProfile.stages().get(1);
    ProfileStage withoutRetry =
        new ProfileStage(
            collect.stageId(),
            collect.capabilityId(),
            collect.consumes(),
            collect.produces(),
            collect.approval(),
            collect.bypass(),
            null);
    ProductPipelineProfile profile =
        copyWithStages(validProfile, List.of(withoutRetry, finish));

    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () ->
                ProductPipelineProfileValidator.validate(
                    profile, SCHEMA_REGISTRY, KNOWN_CAPABILITIES));
    assertTrue(ex.getMessage().contains(profile.profileId()));
    assertTrue(ex.getMessage().contains(collect.stageId()));
  }

  @Test
  void rejectsUnknownCapability() {
    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () ->
                ProductPipelineProfileValidator.validate(
                    validProfile, SCHEMA_REGISTRY, Set.of("fake-collector")));
    assertTrue(ex.getMessage().contains(validProfile.profileId()));
    assertTrue(ex.getMessage().contains("finish"));
  }

  @Test
  void rejectsUnreachableTerminal() {
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            validProfile.schemaVersion(),
            validProfile.profileId(),
            validProfile.profileVersion(),
            validProfile.runInputs(),
            validProfile.stages(),
            new TerminalPolicy("missing-stage", "PLAN_APPROVED"),
            validProfile.dependencyRoots());

    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () ->
                ProductPipelineProfileValidator.validate(
                    profile, SCHEMA_REGISTRY, KNOWN_CAPABILITIES));
    assertTrue(ex.getMessage().contains(profile.profileId()));
    assertTrue(ex.getMessage().contains("missing-stage"));
  }

  @Test
  void rejectsApprovalCandidateSetTypeDeclaredByNone() {
    ProfileStage collect =
        new ProfileStage(
            "collect",
            "fake-collector",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(new ArtifactTypeRef("requirement-brief", 1)),
            new ApprovalPolicy(
                new ArtifactTypeRef("requirement-brief", 1),
                List.of(
                    new ArtifactTypeRef("requirement-brief", 1),
                    new ArtifactTypeRef("ids-bypass", 1))),
            null,
            new RetryPolicy(0, 1000));
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            validProfile.schemaVersion(),
            validProfile.profileId(),
            validProfile.profileVersion(),
            validProfile.runInputs(),
            List.of(collect),
            new TerminalPolicy("collect", "PLAN_APPROVED"),
            validProfile.dependencyRoots());

    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () ->
                ProductPipelineProfileValidator.validate(
                    profile, SCHEMA_REGISTRY, KNOWN_CAPABILITIES));
    assertTrue(ex.getMessage().contains("candidateSet"));
    assertTrue(ex.getMessage().contains("ids-bypass"));
  }

  @Test
  void acceptsApprovalCandidateSetTypeDeclaredByConsumes() {
    ProfileStage collect =
        new ProfileStage(
            "collect",
            "fake-collector",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(),
            List.of(new ArtifactTypeRef("requirement-brief", 1)),
            List.of(),
            new ApprovalPolicy(
                new ArtifactTypeRef("requirement-brief", 1),
                List.of(
                    new ArtifactTypeRef("user-input", 1),
                    new ArtifactTypeRef("requirement-brief", 1)),
                ApprovalPolicy.CATALOG_FIRST_V1,
                ApprovalPolicy.CATALOG_FIRST_V1_HASH),
            null,
            new RetryPolicy(0, 1000),
            null);
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            validProfile.schemaVersion(),
            validProfile.profileId(),
            validProfile.profileVersion(),
            validProfile.runInputs(),
            List.of(collect),
            new TerminalPolicy("collect", "PLAN_APPROVED"),
            validProfile.dependencyRoots());

    assertDoesNotThrow(
        () ->
            ProductPipelineProfileValidator.validate(
                profile, SCHEMA_REGISTRY, KNOWN_CAPABILITIES));
  }

  @Test
  void rejectsPartialBindingResolutionPolicy() {
    ProfileStage collect =
        new ProfileStage(
            "collect",
            "fake-collector",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(new ArtifactTypeRef("requirement-brief", 1)),
            new ApprovalPolicy(
                new ArtifactTypeRef("requirement-brief", 1),
                List.of(new ArtifactTypeRef("requirement-brief", 1)),
                ApprovalPolicy.CATALOG_FIRST_V1,
                null),
            null,
            new RetryPolicy(0, 1000));
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            validProfile.schemaVersion(),
            validProfile.profileId(),
            validProfile.profileVersion(),
            validProfile.runInputs(),
            List.of(collect),
            new TerminalPolicy("collect", "PLAN_APPROVED"),
            validProfile.dependencyRoots());

    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () ->
                ProductPipelineProfileValidator.validate(
                    profile, SCHEMA_REGISTRY, KNOWN_CAPABILITIES));
    assertTrue(ex.getMessage().contains("bindingResolutionPolicy"));
  }

  @Test
  void rejectsCatalogFirstHashMismatch() {
    ProfileStage collect =
        new ProfileStage(
            "collect",
            "fake-collector",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(new ArtifactTypeRef("requirement-brief", 1)),
            new ApprovalPolicy(
                new ArtifactTypeRef("requirement-brief", 1),
                List.of(new ArtifactTypeRef("requirement-brief", 1)),
                ApprovalPolicy.CATALOG_FIRST_V1,
                "deadbeef"),
            null,
            new RetryPolicy(0, 1000));
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            validProfile.schemaVersion(),
            validProfile.profileId(),
            validProfile.profileVersion(),
            validProfile.runInputs(),
            List.of(collect),
            new TerminalPolicy("collect", "PLAN_APPROVED"),
            validProfile.dependencyRoots());

    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () ->
                ProductPipelineProfileValidator.validate(
                    profile, SCHEMA_REGISTRY, KNOWN_CAPABILITIES));
    assertTrue(ex.getMessage().contains(ApprovalPolicy.CATALOG_FIRST_V1_HASH));
  }

  @Test
  void multiItemApprovalMakesApprovalRecordV2AvailableToLaterStages() {
    ProfileStage collect =
        new ProfileStage(
            "collect",
            "fake-collector",
            List.of(new ArtifactTypeRef("user-input", 1)),
            List.of(new ArtifactTypeRef("requirement-brief", 1), new ArtifactTypeRef("ids-bypass", 1)),
            new ApprovalPolicy(
                new ArtifactTypeRef("requirement-brief", 1),
                List.of(
                    new ArtifactTypeRef("requirement-brief", 1),
                    new ArtifactTypeRef("ids-bypass", 1))),
            null,
            new RetryPolicy(0, 1000));
    ProfileStage consumeApprovalRecord =
        new ProfileStage(
            "finish",
            "fake-finisher",
            List.of(new ArtifactTypeRef("approval-record", 2)),
            List.of(new ArtifactTypeRef("ids-bypass", 1)),
            new ApprovalPolicy(new ArtifactTypeRef("ids-bypass", 1)),
            null,
            new RetryPolicy(0, 1000));
    ProductPipelineProfile profile =
        new ProductPipelineProfile(
            validProfile.schemaVersion(),
            validProfile.profileId(),
            validProfile.profileVersion(),
            validProfile.runInputs(),
            List.of(collect, consumeApprovalRecord),
            validProfile.terminal(),
            validProfile.dependencyRoots());

    assertDoesNotThrow(
        () -> ProductPipelineProfileValidator.validate(profile, SCHEMA_REGISTRY, KNOWN_CAPABILITIES));
  }

  @Test
  void acceptsImmutableCreateChainProfileWithImplementationGate() throws Exception {
    ProductPipelineProfile createChain;
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v1.yaml")) {
      createChain = ProductPipelineProfileParser.parse(in);
    }
    ArtifactSchemaRegistry schemas =
        ArtifactSchemaRegistry.of(
            new ArtifactTypeRef("user-input", 1),
            new ArtifactTypeRef("requirement-draft", 2),
            new ArtifactTypeRef("requirement-brief", 1),
            new ArtifactTypeRef("ids-bypass", 1),
            new ArtifactTypeRef("implementation-plan", 2),
            new ArtifactTypeRef("plan-validation-result", 1),
            new ArtifactTypeRef("chain-plan-graph", 1),
            new ArtifactTypeRef("graph-assembly-result", 1),
            new ArtifactTypeRef("compiler-validation-bundle", 1),
            new ArtifactTypeRef("approval-record", 2),
            new ArtifactTypeRef("materialization-result", 1),
            new ArtifactTypeRef("catalog-chain-snapshot", 1),
            new ArtifactTypeRef("reconcile-result", 1));

    assertDoesNotThrow(
        () ->
            ProductPipelineProfileValidator.validate(
                createChain,
                schemas,
                Set.of(
                    "requirement-discovery",
                    "specification-import",
                    "requirement-analysis",
                    "planning",
                    "materialization")));
  }

  @Test
  void rejectsImplementationGateWithUnknownWaitingState() throws Exception {
    ProductPipelineProfile createChain;
    try (InputStream in =
        getClass().getResourceAsStream("/product-pipelines/profiles/create-chain-v1.yaml")) {
      createChain = ProductPipelineProfileParser.parse(in);
    }
    ProductPipelineProfile invalid =
        new ProductPipelineProfile(
            createChain.schemaVersion(),
            createChain.profileId(),
            createChain.profileVersion(),
            createChain.runInputs(),
            createChain.stages(),
            createChain.terminal(),
            createChain.dependencyRoots(),
            createChain.compilerPipeline(),
            new ImplementationGatePolicy(
                "planning",
                new ArtifactTypeRef("implementation-plan", 2),
                "WAITING_FOR_APPROVAL"));
    ArtifactSchemaRegistry schemas =
        ArtifactSchemaRegistry.of(
            new ArtifactTypeRef("user-input", 1),
            new ArtifactTypeRef("requirement-draft", 2),
            new ArtifactTypeRef("requirement-brief", 1),
            new ArtifactTypeRef("ids-bypass", 1),
            new ArtifactTypeRef("implementation-plan", 2),
            new ArtifactTypeRef("plan-validation-result", 1),
            new ArtifactTypeRef("chain-plan-graph", 1),
            new ArtifactTypeRef("graph-assembly-result", 1),
            new ArtifactTypeRef("compiler-validation-bundle", 1),
            new ArtifactTypeRef("approval-record", 2),
            new ArtifactTypeRef("materialization-result", 1),
            new ArtifactTypeRef("catalog-chain-snapshot", 1),
            new ArtifactTypeRef("reconcile-result", 1));

    ProductPipelineProfileValidationException ex =
        assertThrows(
            ProductPipelineProfileValidationException.class,
            () ->
                ProductPipelineProfileValidator.validate(
                    invalid,
                    schemas,
                    Set.of(
                        "requirement-discovery",
                        "specification-import",
                        "requirement-analysis",
                        "planning",
                        "materialization")));
    assertTrue(ex.getMessage().contains("WAITING_FOR_IMPLEMENT"));
  }

  private static ProductPipelineProfile copyWithStages(
      ProductPipelineProfile source, List<ProfileStage> stages) {
    return new ProductPipelineProfile(
        source.schemaVersion(),
        source.profileId(),
        source.profileVersion(),
        source.runInputs(),
        List.copyOf(new ArrayList<>(stages)),
        source.terminal(),
        source.dependencyRoots(),
        source.compilerPipeline(),
        source.implementationGate());
  }
}
