package org.qubership.integration.platform.ai.productpipeline.create;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.profile.ApprovalPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.ArtifactTypeRef;
import org.qubership.integration.platform.ai.productpipeline.profile.ProductPipelineProfile;
import org.qubership.integration.platform.ai.productpipeline.profile.ProfileStage;
import org.qubership.integration.platform.ai.productpipeline.profile.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.profile.TerminalPolicy;

class OwnerCandidateSetTest {

  @Test
  void firstLayerIncludesTheFailedStageAndProducersOfItsInputs() {
    List<OwnerCandidate> first = OwnerCandidateSet.firstLayer(threeStageProfile(), "planning");
    assertEquals(List.of("planning", "analysis"), OwnerCandidateSet.stageIds(first));
    assertTrue(OwnerCandidateSet.format(first).contains("planning:"));
    assertTrue(OwnerCandidateSet.format(first).contains("analysis:requirement-brief"));
    assertFalse(OwnerCandidateSet.format(first).contains("discovery"));
  }

  @Test
  void deepenAddsProducersOfTheFirstLayer() {
    List<OwnerCandidate> first = OwnerCandidateSet.firstLayer(threeStageProfile(), "planning");
    List<OwnerCandidate> deeper = OwnerCandidateSet.deepen(threeStageProfile(), first);
    assertEquals(List.of("planning", "analysis", "discovery"), OwnerCandidateSet.stageIds(deeper));
  }

  @Test
  void firstLayerIsOnlyTheFailedStageWhenInputsHaveNoProducer() {
    List<OwnerCandidate> first = OwnerCandidateSet.firstLayer(threeStageProfile(), "discovery");
    assertEquals(List.of("discovery"), OwnerCandidateSet.stageIds(first));
  }

  private static ProductPipelineProfile threeStageProfile() {
    ArtifactTypeRef draft = new ArtifactTypeRef("requirement-draft", 1);
    ArtifactTypeRef brief = new ArtifactTypeRef("requirement-brief", 1);
    ArtifactTypeRef validation = new ArtifactTypeRef("plan-validation-result", 1);
    return new ProductPipelineProfile(
        1,
        "owner-candidates",
        "1",
        List.of(new ArtifactTypeRef("user-input", 1)),
        List.of(
            new ProfileStage(
                "discovery",
                "discovery-cap",
                List.of(new ArtifactTypeRef("user-input", 1)),
                List.of(draft),
                null,
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "analysis",
                "analysis-cap",
                List.of(draft),
                List.of(brief),
                new ApprovalPolicy(brief),
                null,
                new RetryPolicy(0, 1L)),
            new ProfileStage(
                "planning",
                "planning-cap",
                List.of(brief),
                List.of(validation),
                null,
                null,
                new RetryPolicy(0, 1L))),
        new TerminalPolicy("planning", "PLAN_APPROVED"),
        List.of("discovery-cap", "analysis-cap", "planning-cap"));
  }
}
