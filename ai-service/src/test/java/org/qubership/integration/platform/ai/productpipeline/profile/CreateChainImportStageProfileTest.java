package org.qubership.integration.platform.ai.productpipeline.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

/** T4: create-chain profiles declare import-stage after discovery and before analysis. */
class CreateChainImportStageProfileTest {

  @Test
  void importStageSitsBetweenDiscoveryAndAnalysisOnV1() throws Exception {
    assertImportStageOrder(loadCreateChain("create-chain-v1.yaml"));
  }

  @Test
  void importStagesSitBetweenDiscoveryAndAnalysisOnV2() throws Exception {
    ProductPipelineProfile profile = loadCreateChain("create-chain-v2.yaml");
    List<String> stageIds = profile.stages().stream().map(ProfileStage::stageId).toList();

    int discovery = stageIds.indexOf("requirement-discovery");
    int importStage = stageIds.indexOf("import-stage");
    int uploaded = stageIds.indexOf("uploaded-spec-import");
    int analysis = stageIds.indexOf("requirement-analysis");

    assertTrue(discovery >= 0, "requirement-discovery missing");
    assertTrue(importStage >= 0, "import-stage missing");
    assertTrue(uploaded >= 0, "uploaded-spec-import missing");
    assertTrue(analysis >= 0, "requirement-analysis missing");
    assertEquals(discovery + 1, importStage);
    assertEquals(importStage + 1, uploaded);
    assertEquals(uploaded + 1, analysis);
  }

  @Test
  void importStageMutatesRequirementDraftInPlaceWithSkipAndConfirmGateOnV1() throws Exception {
    assertImportStageContract(loadCreateChain("create-chain-v1.yaml"));
  }

  @Test
  void importStageMutatesRequirementDraftInPlaceWithSkipAndConfirmGateOnV2() throws Exception {
    assertImportStageContract(loadCreateChain("create-chain-v2.yaml"));
  }

  private static void assertImportStageOrder(ProductPipelineProfile profile) {
    List<String> stageIds = profile.stages().stream().map(ProfileStage::stageId).toList();

    int discovery = stageIds.indexOf("requirement-discovery");
    int importStage = stageIds.indexOf("import-stage");
    int analysis = stageIds.indexOf("requirement-analysis");

    assertTrue(discovery >= 0, "requirement-discovery missing");
    assertTrue(importStage >= 0, "import-stage missing");
    assertTrue(analysis >= 0, "requirement-analysis missing");
    assertEquals(discovery + 1, importStage);
    assertEquals(importStage + 1, analysis);
  }

  private static void assertImportStageContract(ProductPipelineProfile profile) {
    ProfileStage importStage =
        profile.stages().stream()
            .filter(stage -> "import-stage".equals(stage.stageId()))
            .findFirst()
            .orElseThrow();

    assertEquals("specification-import", importStage.capabilityId());
    assertEquals(
        List.of(new ArtifactTypeRef("requirement-draft", 2)), importStage.consumes());
    assertEquals(
        List.of(new ArtifactTypeRef("requirement-draft", 2)), importStage.produces());
    assertNotNull(importStage.skip(), "skip policy required for ADR decision 9");
    assertTrue(
        importStage.skip().whenAny().contains(SkipPolicy.NO_APIHUB_CANDIDATE));
    assertTrue(
        importStage.skip().whenAny().contains(SkipPolicy.CATALOG_BINDING_PRESENT));
  }

  @Test
  void uploadedSpecImportStageKeepsApiHubImportOnV2() throws Exception {
    ProductPipelineProfile profile = loadCreateChain("create-chain-v2.yaml");
    ProfileStage uploaded =
        profile.stages().stream()
            .filter(stage -> "uploaded-spec-import".equals(stage.stageId()))
            .findFirst()
            .orElseThrow();

    assertEquals("auto-uploaded-spec-import", uploaded.capabilityId());
    assertEquals(
        List.of(new ArtifactTypeRef("requirement-draft", 2)), uploaded.consumes());
    assertEquals(
        List.of(new ArtifactTypeRef("requirement-draft", 2)), uploaded.produces());
    assertNotNull(uploaded.skip(), "skip policy required for uploaded-spec-import");
    assertTrue(uploaded.skip().whenAny().contains(SkipPolicy.NO_ALLOWED_ATTACHMENTS));
    assertTrue(uploaded.skip().whenAny().contains(SkipPolicy.CATALOG_BINDING_PRESENT));
  }

  private static ProductPipelineProfile loadCreateChain(String resourceName) throws Exception {
    try (InputStream in =
        CreateChainImportStageProfileTest.class.getResourceAsStream(
            "/product-pipelines/profiles/" + resourceName)) {
      assertNotNull(in, resourceName + " fixture missing");
      return ProductPipelineProfileParser.parse(in);
    }
  }
}
