package org.qubership.integration.platform.ai.productpipeline.create.facade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfileParser;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;

class CreateChainPublicArtifactTypesTest {

  /**
   * Approval advertises a type and then reads the caller's echo of it. An asymmetric mapping made
   * design-input reject its own approval as WrongArtifactType with expected equal to provided.
   */
  @ParameterizedTest
  @EnumSource(Kind.class)
  void everyApprovalTypeReadsBackAsTheSameKind(Kind kind) {
    String advertised = CreateChainPublicArtifactTypes.toApprovalType(kind);
    Optional<Kind> readBack = CreateChainPublicArtifactTypes.toKind(advertised);
    assertEquals(
        Optional.of(expectedKind(kind)),
        readBack,
        "approval type '" + advertised + "' for " + kind + " does not read back");
  }

  @Test
  void readsTheSemanticRevisionApprovalTypeThatBlockedDesignInput() {
    assertEquals(
        Optional.of(Kind.CHAIN_SEMANTIC_REVISION),
        CreateChainPublicArtifactTypes.toKind("chain-semantic-revision"));
  }

  @Test
  void everyActiveProfileApprovalRoundTripsToItsExactKind() throws Exception {
    for (String profileName : List.of("create-chain-v1.yaml", "create-chain-v2.yaml")) {
      ProductPipelineProfile profile = parseProfile(profileName);
      for (ProfileStage stage : profile.stages()) {
        if (stage.approval() == null || stage.approval().artifact() == null) {
          continue;
        }
        Kind expected = kindFor(stage.approval().artifact().type());
        String advertised = CreateChainPublicArtifactTypes.toApprovalType(expected);
        assertEquals(
            Optional.of(expected),
            CreateChainPublicArtifactTypes.toKind(advertised),
            () -> profileName + " stage " + stage.stageId() + " does not round trip");
      }
    }
  }

  @Test
  void keepsPublicNamesAndTheirWireAliases() {
    assertEquals(
        Optional.of(Kind.IDS_DOCUMENT),
        CreateChainPublicArtifactTypes.toKind("integration-design"));
    assertEquals(
        Optional.of(Kind.IDS_DOCUMENT), CreateChainPublicArtifactTypes.toKind("ids-document"));
    assertEquals(Optional.empty(), CreateChainPublicArtifactTypes.toKind("not-an-artifact"));
  }

  /** Several kinds share one public name, so those collapse onto the name's canonical kind. */
  private static Kind expectedKind(Kind kind) {
    return CreateChainPublicArtifactTypes.toPublicType(kind)
        .flatMap(CreateChainPublicArtifactTypes::toKind)
        .orElse(kind);
  }

  private static ProductPipelineProfile parseProfile(String profileName) throws Exception {
    try (InputStream input =
        CreateChainPublicArtifactTypesTest.class.getResourceAsStream(
            "/product-pipelines/profiles/" + profileName)) {
      return ProductPipelineProfileParser.parse(input);
    }
  }

  private static Kind kindFor(String type) {
    return Kind.valueOf(type.replace('-', '_').toUpperCase(Locale.ROOT));
  }
}
